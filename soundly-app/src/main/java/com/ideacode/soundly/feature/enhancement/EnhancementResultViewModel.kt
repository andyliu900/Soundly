package com.ideacode.soundly.feature.enhancement

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.ideacode.soundly.common.error.AppError
import com.ideacode.soundly.common.error.AppResult
import com.ideacode.soundly.common.state.*
import com.ideacode.soundly.data.sdk.ProjectGateway
import com.ideacode.soundly_sdk.api.model.domain.AudioProject
import com.ideacode.soundly_sdk.api.model.domain.AudioVariant
import com.ideacode.soundly_sdk.api.model.domain.VariantType
import com.ideacode.soundly_sdk.api.model.playback.PlaybackState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

data class ResultState(val loading: Boolean = true, val missing: Boolean = false, val project: AudioProject? = null,
    val source: AudioVariant? = null, val enhanced: AudioVariant? = null, val selectedId: String = "",
    val waveform: List<Float> = emptyList(), val waveformError: Boolean = false,
    val playback: PlaybackState = PlaybackState.Idle, val busy: Boolean = false) : UiState {
    val selected: AudioVariant? get() = listOfNotNull(source, enhanced).firstOrNull { it.id == selectedId }
}
sealed interface ResultAction : UiAction { data class Select(val id: String) : ResultAction; data object Toggle : ResultAction; data class Seek(val position: Long) : ResultAction }
sealed interface ResultEffect : UiEffect { data class Error(val error: AppError) : ResultEffect }

class EnhancementResultViewModel(private val saved: SavedStateHandle, private val projects: ProjectGateway) :
    StateActionViewModel<ResultState, ResultAction, ResultEffect>(ResultState(selectedId = saved.get<String>("h6_selected") ?: saved.get<String>("enhancedVariantId").orEmpty())) {
    private val projectId = saved.get<String>("projectId").orEmpty()
    private val sourceId = saved.get<String>("originalVariantId").orEmpty()
    private val enhancedId = saved.get<String>("enhancedVariantId").orEmpty()
    private var ownsPlayback = false
    private var active = false
    init {
        viewModelScope.launch {
            try {
                projects.observeProject(projectId).collect { project ->
                    val variants = project?.let { listOf(it.original) + it.variants }.orEmpty()
                    val source = variants.firstOrNull { it.id == sourceId && sourceId.isNotBlank() }
                    val enhanced = variants.firstOrNull { it.id == enhancedId && enhancedId.isNotBlank() }
                    val paired = source != null && enhanced != null && source.id != enhanced.id &&
                        enhanced.variantType == VariantType.ENHANCED && enhanced.sourceVariantId == source.id
                    if (!paired && ownsPlayback) releaseOwnership()
                    updateState { state -> state.copy(loading = false, missing = project == null || !paired,
                        project = project, source = source, enhanced = enhanced,
                        selectedId = if (paired && state.selectedId !in setOf(sourceId, enhancedId)) enhancedId else state.selectedId) }
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { updateState { it.copy(loading = false, missing = true) } }
        }
        viewModelScope.launch {
            uiState.map { it.selected?.id }.distinctUntilChanged().collectLatest { id ->
                updateState { it.copy(waveform = emptyList(), waveformError = false) }
                if (id != null) try {
                    when (val result = projects.getOrGenerateWaveform(projectId, id)) {
                        is AppResult.Success -> updateState { it.copy(waveform = result.value.amplitudes) }
                        is AppResult.Failure -> updateState { it.copy(waveformError = true) }
                    }
                } catch (e: CancellationException) { throw e } catch (_: Exception) { updateState { it.copy(waveformError = true) } }
            }
        }
        viewModelScope.launch { projects.playbackState.collect { if (ownsPlayback && !uiState.value.busy) updateState { s -> s.copy(playback = it) } } }
    }
    fun setActive(value: Boolean) {
        active = value
        if (!value && ownsPlayback && !uiState.value.busy) releaseOwnership()
    }
    private fun releaseOwnership() {
        if (projects.playbackState.value is PlaybackState.Playing) projects.pausePlayback()
        ownsPlayback = false
        updateState { it.copy(playback = PlaybackState.Idle) }
    }
    override fun dispatch(action: ResultAction) {
        val state = uiState.value
        if (state.busy || state.missing) return
        when (action) {
            is ResultAction.Select -> select(action.id)
            ResultAction.Toggle -> {
                val selected = state.selected ?: return
                if (ownsPlayback && projects.playbackState.value is PlaybackState.Playing) report(projects.pausePlayback())
                else if (ownsPlayback && projects.playbackState.value !is PlaybackState.Failed) report(projects.play())
                else prepare(selected) { report(projects.play()) }
            }
            is ResultAction.Seek -> {
                val selected = state.selected ?: return
                val position = action.position.coerceIn(0, selected.durationMs.coerceAtLeast(0))
                if (ownsPlayback && projects.playbackState.value !is PlaybackState.Failed) report(projects.seekTo(position))
                else prepare(selected) { report(projects.seekTo(position)) }
            }
        }
    }
    private fun select(id: String) {
        val state = uiState.value
        val old = state.selected ?: return
        val next = listOfNotNull(state.source, state.enhanced).firstOrNull { it.id == id } ?: return
        if (id == old.id) return
        if (!ownsPlayback) { saveSelection(id); return }
        val before = projects.playbackState.value
        val position = when (before) { is PlaybackState.Playing -> before.positionMs; is PlaybackState.Paused -> before.positionMs; PlaybackState.Completed -> old.durationMs; else -> 0L }
        val wasPlaying = before is PlaybackState.Playing
        updateState { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val switched = try { projects.switchPlaybackKeepingPosition(next) } catch (e: CancellationException) { throw e } catch (_: Exception) { AppResult.Failure(AppError("playback_failed", true)) }
                val adjusted = if (switched is AppResult.Success && (before is PlaybackState.Completed || position > next.durationMs))
                    projects.seekTo(position.coerceIn(0L, next.durationMs.coerceAtLeast(0L))) else switched
                when (adjusted) {
                    is AppResult.Success -> { saveSelection(id); updateState { it.copy(playback = projects.playbackState.value) } }
                    is AppResult.Failure -> {
                        // The SDK may have released the old source before a failed prepare. Restore it explicitly.
                        val restored = projects.preparePlayback(old) is AppResult.Success && projects.seekTo(position) is AppResult.Success
                        ownsPlayback = restored
                        if (restored && wasPlaying && active) projects.play()
                        updateState { it.copy(playback = if (restored) projects.playbackState.value else PlaybackState.Idle) }
                        postEffect(ResultEffect.Error(AppError("h6_switch_failed", true)))
                    }
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                ownsPlayback = false; updateState { it.copy(playback = PlaybackState.Idle) }
                postEffect(ResultEffect.Error(AppError("h6_switch_failed", true)))
            } finally {
                updateState { it.copy(busy = false) }
                if (!active && ownsPlayback) releaseOwnership()
            }
        }
    }
    private fun saveSelection(id: String) { saved["h6_selected"] = id; updateState { it.copy(selectedId = id) } }
    private fun prepare(variant: AudioVariant, after: () -> Unit) {
        updateState { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                when (val result = projects.preparePlayback(variant)) {
                    is AppResult.Success -> if (active && uiState.value.selectedId == variant.id) { ownsPlayback = true; after(); updateState { it.copy(playback = projects.playbackState.value) } }
                    is AppResult.Failure -> postEffect(ResultEffect.Error(result.error))
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { postEffect(ResultEffect.Error(AppError("playback_failed", true))) }
            finally { updateState { it.copy(busy = false) }; if (!active && ownsPlayback) releaseOwnership() }
        }
    }
    private fun report(result: AppResult<Unit>) { if (result is AppResult.Failure) postEffect(ResultEffect.Error(result.error)) }
}
