package com.ideacode.soundly.feature.editing

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ideacode.soundly.common.error.AppError
import com.ideacode.soundly.common.error.AppResult
import com.ideacode.soundly.data.sdk.EditingGateway
import com.ideacode.soundly.data.sdk.ProjectGateway
import com.ideacode.soundly_sdk.api.capability.TrimCapabilities
import com.ideacode.soundly_sdk.api.capability.TrimRange
import com.ideacode.soundly_sdk.api.model.domain.AudioProject
import com.ideacode.soundly_sdk.api.model.domain.AudioVariant
import com.ideacode.soundly_sdk.api.model.playback.PlaybackState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class TrimUiState(
    val loading: Boolean = true, val restoring: Boolean = true, val missing: Boolean = false,
    val project: AudioProject? = null, val variant: AudioVariant? = null,
    val startMs: Long = 0, val endMs: Long = 0, val waveform: List<Float> = emptyList(),
    val waveformError: Boolean = false, val waveformLoading: Boolean = false,
    val busy: Boolean = false, val previewPreparing: Boolean = false, val playing: Boolean = false,
    val error: AppError? = null, val uncertain: Boolean = false, val resultId: String? = null
) {
    val range: TrimRange get() = TrimRange(startMs, endMs)
    val valid: Boolean get() = variant?.let { TrimCapabilities.supports(it.format) && TrimCapabilities.isValidRange(range, it.durationMs) } == true
}

internal class TrimViewModel(
    private val saved: SavedStateHandle,
    private val projects: ProjectGateway,
    private val editing: EditingGateway,
    private val journal: TrimJournal
) : ViewModel() {
    private val projectId = saved.get<String>("projectId").orEmpty()
    private val sourceId = saved.get<String>("variantId").orEmpty()
    private val mutable = MutableStateFlow(TrimUiState())
    val state = mutable.asStateFlow()
    private var active = false
    private var ownsPlayback = false
    private var previewEndJob: Job? = null
    private var rangeInitialized = false

    init {
        viewModelScope.launch {
            try {
                projects.observeProject(projectId).collect { project ->
                    val variant = project?.let { (listOf(it.original) + it.variants).firstOrNull { v -> v.id == sourceId && sourceId.isNotBlank() } }
                    if (variant != null && !rangeInitialized) {
                        rangeInitialized = true
                        val start = (saved.get<Long>("trim_start_ms") ?: 0L).coerceIn(0L, variant.durationMs.coerceAtLeast(0))
                        val end = (saved.get<Long>("trim_end_ms") ?: variant.durationMs).coerceIn(0L, variant.durationMs.coerceAtLeast(0))
                        mutable.update { it.copy(startMs = start, endMs = end) }
                        loadWaveform()
                    }
                    mutable.update { it.copy(loading = false, project = project, variant = variant, missing = project == null || variant == null) }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { mutable.update { it.copy(loading = false, missing = true) } }
        }
        viewModelScope.launch {
            try {
                val entry = journal.read()
                if (entry == null) mutable.update { it.copy(restoring = false) }
                else if (entry.resultVariantId == null) mutable.update { it.copy(restoring = false, uncertain = true) }
                else {
                    when (val project = projects.getProject(projectId)) {
                        is AppResult.Success -> {
                            val found = (listOf(project.value.original) + project.value.variants).firstOrNull { it.id == entry.resultVariantId }
                            val verified = found != null && (projects.isVariantReadable(found) as? AppResult.Success)?.value == true
                            mutable.update { it.copy(restoring = false, resultId = entry.resultVariantId.takeIf { verified }, uncertain = !verified) }
                        }
                        is AppResult.Failure -> mutable.update { it.copy(restoring = false, uncertain = true) }
                    }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { mutable.update { it.copy(restoring = false, uncertain = true) } }
        }
        viewModelScope.launch {
            projects.playbackState.collect { playback ->
                if (!ownsPlayback) return@collect
                val playing = playback is PlaybackState.Playing
                val position = (playback as? PlaybackState.Playing)?.positionMs
                if (position != null && position >= mutable.value.endMs) {
                    previewEndJob?.cancel(); previewEndJob = null
                    projects.pausePlayback(); projects.seekTo(mutable.value.startMs)
                    mutable.update { it.copy(playing = false) }
                } else if (playback is PlaybackState.Completed) {
                    previewEndJob?.cancel(); previewEndJob = null
                    projects.seekTo(mutable.value.startMs)
                    mutable.update { it.copy(playing = false) }
                } else if (playback is PlaybackState.Failed) {
                    previewEndJob?.cancel(); previewEndJob = null
                    ownsPlayback = false
                    mutable.update { it.copy(playing = false, error = AppError(playback.error.code, playback.error.retryable)) }
                } else mutable.update { it.copy(playing = playing) }
            }
        }
    }

    fun setActive(value: Boolean) {
        active = value
        if (!value) stopPreview()
    }
    fun setRange(start: Long, end: Long) {
        if (mutable.value.busy || mutable.value.restoring || mutable.value.uncertain) return
        val duration = mutable.value.variant?.durationMs ?: return
        val safeStart = start.coerceIn(0, duration.coerceAtLeast(0))
        val safeEnd = end.coerceIn(0, duration.coerceAtLeast(0))
        stopPreview()
        saved["trim_start_ms"] = safeStart; saved["trim_end_ms"] = safeEnd
        mutable.update { it.copy(startMs = safeStart, endMs = safeEnd, error = null) }
    }
    fun loadWaveform() {
        if (mutable.value.waveformLoading) return
        mutable.update { it.copy(waveformLoading = true, waveformError = false) }
        viewModelScope.launch {
            try {
                when (val result = projects.getOrGenerateWaveform(projectId, sourceId)) {
                    is AppResult.Success -> mutable.update { it.copy(waveform = result.value.amplitudes, waveformError = false) }
                    is AppResult.Failure -> mutable.update { it.copy(waveformError = true) }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { mutable.update { it.copy(waveformError = true) } }
            finally { mutable.update { it.copy(waveformLoading = false) } }
        }
    }
    fun togglePreview() {
        val s = mutable.value
        val variant = s.variant ?: return
        if (!s.valid || s.busy || s.restoring || s.uncertain || s.previewPreparing) return
        if (s.playing || (ownsPlayback && projects.playbackState.value is PlaybackState.Playing)) { stopPreview(); return }
        val range = s.range
        mutable.update { it.copy(previewPreparing = true, error = null) }
        viewModelScope.launch {
            try {
                val preview = editing.previewTrimDetails(projectId, sourceId, range)
                if (preview is AppResult.Failure) {
                    mutable.update { it.copy(error = preview.error) }
                    return@launch
                }
                val prepared = projects.preparePlayback(variant)
                if (prepared is AppResult.Failure) {
                    mutable.update { it.copy(error = prepared.error) }
                    return@launch
                }
                if (!active || mutable.value.range != range) return@launch
                ownsPlayback = true
                val seek = projects.seekTo(range.startMs)
                if (seek is AppResult.Failure) { mutable.update { it.copy(error = seek.error) }; return@launch }
                val played = projects.play()
                if (played is AppResult.Failure) mutable.update { it.copy(error = played.error) }
                else previewEndJob = viewModelScope.launch {
                    delay((range.endMs - range.startMs).coerceAtLeast(1L))
                    if (active && ownsPlayback && mutable.value.range == range && projects.playbackState.value is PlaybackState.Playing) {
                        projects.pausePlayback(); projects.seekTo(range.startMs)
                        mutable.update { it.copy(playing = false) }
                    }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { mutable.update { it.copy(error = AppError("playback_failed", true)) } }
            finally { mutable.update { it.copy(previewPreparing = false) } }
        }
    }
    fun apply() {
        val s = mutable.value
        if (!s.valid || s.busy || s.restoring || s.uncertain || s.resultId != null || s.previewPreparing) return
        val range = s.range
        stopPreview()
        mutable.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            var pending = false
            try {
                when (val preview = editing.previewTrimDetails(projectId, sourceId, range)) {
                    is AppResult.Failure -> { mutable.update { it.copy(error = preview.error) }; return@launch }
                    is AppResult.Success -> Unit
                }
                journal.markPending(); pending = true
                when (val result = editing.applyTrim(projectId, sourceId, range)) {
                    is AppResult.Success -> {
                        if (result.value.projectId != projectId || result.value.variantId.isBlank()) {
                            mutable.update { it.copy(error = AppError("trim_failed", true), uncertain = true) }
                            return@launch
                        }
                        try { journal.markComplete(result.value.variantId) }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) { /* pending record remains conservative */ }
                        mutable.update { it.copy(resultId = result.value.variantId) }
                    }
                    is AppResult.Failure -> {
                        if (result.error.code in SAFE_PRECOMMIT_ERRORS) {
                            journal.clear(); pending = false
                            mutable.update { it.copy(error = result.error) }
                        } else mutable.update { it.copy(error = result.error, uncertain = true) }
                    }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { mutable.update { it.copy(error = AppError("trim_failed", true), uncertain = pending) } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun acknowledgeUncertain() {
        if (mutable.value.busy) return
        viewModelScope.launch {
            try { journal.clear(); mutable.update { it.copy(uncertain = false, error = null) } }
            catch (_: Exception) { mutable.update { it.copy(error = AppError("file_operation_failed", true)) } }
        }
    }
    suspend fun resultDelivered() { journal.clear() }
    private fun stopPreview() {
        previewEndJob?.cancel(); previewEndJob = null
        if (ownsPlayback) { projects.pausePlayback(); ownsPlayback = false }
        mutable.update { it.copy(playing = false) }
    }
    private companion object { val SAFE_PRECOMMIT_ERRORS = setOf("insufficient_storage", "invalid_request", "unsupported_format", "file_not_found", "project_not_found") }
}
