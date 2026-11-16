package com.ideacode.soundly.feature.preview

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.ideacode.soundly.common.error.AppError
import com.ideacode.soundly.common.error.AppResult
import com.ideacode.soundly.common.state.*
import com.ideacode.soundly.data.sdk.ProjectGateway
import com.ideacode.soundly.domain.mainflow.EnhancementMode
import com.ideacode.soundly.feature.enhancement.StartEnhancementUseCase
import com.ideacode.soundly_sdk.api.model.domain.AudioProject
import com.ideacode.soundly_sdk.api.model.domain.AudioVariant
import com.ideacode.soundly_sdk.api.model.playback.PlaybackState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class PreviewState(
    val loading: Boolean = true, val project: AudioProject? = null, val variant: AudioVariant? = null,
    val missing: Boolean = false, val waveform: List<Float> = emptyList(), val waveformError: Boolean = false, val waveformLoading: Boolean = false,
    val playback: PlaybackState = PlaybackState.Idle, val preparing: Boolean = false,
    val renaming: Boolean = false, val renameError: String? = null, val starting: Boolean = false
) : UiState
sealed interface PreviewAction : UiAction {
    data object Toggle : PreviewAction
    data class Seek(val position: Long) : PreviewAction
    data class Rename(val title: String) : PreviewAction
    data object Enhance : PreviewAction
    data object RetryWaveform : PreviewAction
}
sealed interface PreviewEffect : UiEffect {
    data class Error(val error: AppError) : PreviewEffect
    data class EnhancementStarted(val taskId: String) : PreviewEffect
    data object Renamed : PreviewEffect
}

class AudioPreviewViewModel(
    private val saved: SavedStateHandle,
    private val projects: ProjectGateway,
    private val startEnhancement: StartEnhancementUseCase
) : StateActionViewModel<PreviewState, PreviewAction, PreviewEffect>(PreviewState()) {
    private val projectId = saved.get<String>("projectId").orEmpty()
    private val requestedId = MutableStateFlow(saved.get<String>("preview_selected_variant") ?: saved.get<String>("variantId").orEmpty())
    private var ownsPlayback = false
    private var active = false
    private val waveformRetry = MutableStateFlow(0)

    init {
        viewModelScope.launch {
            try {
                combine(projects.observeProject(projectId), requestedId) { project, id -> project to id }.collect { (project, requested) ->
                    val variant = project?.let { p ->
                        if (requested.isBlank()) p.original else (listOf(p.original) + p.variants).firstOrNull { it.id == requested }
                    }
                    updateState { it.copy(loading = false, project = project, variant = variant, missing = project == null || variant == null) }
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                updateState { it.copy(loading = false, missing = true) }
            }
        }
        viewModelScope.launch {
            combine(uiState.map { it.variant?.id }.distinctUntilChanged(), waveformRetry) { id, _ -> id }
                .collectLatest { id ->
                updateState { it.copy(waveform = emptyList(), waveformError = false, waveformLoading = id != null) }
                if (id != null) {
                    try {
                        when (val result = projects.getOrGenerateWaveform(projectId, id)) {
                            is AppResult.Success -> updateState { it.copy(waveform = result.value.amplitudes) }
                            is AppResult.Failure -> updateState { it.copy(waveformError = true) }
                        }
                    } catch (e: CancellationException) { throw e } catch (_: Exception) {
                        updateState { it.copy(waveformError = true) }
                    } finally { updateState { it.copy(waveformLoading = false) } }
                }
            }
        }
        viewModelScope.launch { projects.playbackState.collect { state -> if (ownsPlayback) updateState { it.copy(playback = state) } } }
        saved.get<String>("h5_task_id")?.let { updateState { s -> s.copy(starting = true) }; postEffect(PreviewEffect.EnhancementStarted(it)) }
    }
    fun setActive(value: Boolean) {
        active = value
        if (!value && ownsPlayback) {
            if (projects.playbackState.value is PlaybackState.Playing) projects.pausePlayback()
            ownsPlayback = false
            updateState { it.copy(playback = PlaybackState.Idle) }
        }
    }
    fun selectVariant(id: String) {
        if (id.isBlank() || id == requestedId.value) return
        if (ownsPlayback) { projects.pausePlayback(); ownsPlayback = false; updateState { it.copy(playback = PlaybackState.Idle) } }
        saved["preview_selected_variant"] = id
        requestedId.value = id
    }
    fun clearRenameError() { updateState { it.copy(renameError = null) } }
    fun navigationHandled() { saved.remove<String>("h5_task_id"); updateState { it.copy(starting = false) } }
    override fun dispatch(action: PreviewAction) {
        when (action) {
            PreviewAction.Toggle -> toggle()
            is PreviewAction.Seek -> seek(action.position)
            is PreviewAction.Rename -> rename(action.title)
            PreviewAction.Enhance -> enhance()
            PreviewAction.RetryWaveform -> waveformRetry.value += 1
        }
    }
    private fun toggle() {
        val state = uiState.value
        val variant = state.variant ?: return
        if (state.preparing) return
        if (ownsPlayback && projects.playbackState.value is PlaybackState.Playing) { report(projects.pausePlayback()); return }
        if (ownsPlayback && projects.playbackState.value !is PlaybackState.Failed) { report(projects.play()); return }
        prepareThen(variant) { report(projects.play()) }
    }
    private fun seek(position: Long) {
        val state = uiState.value
        val variant = state.variant ?: return
        if (state.preparing) return
        val target = position.coerceIn(0, variant.durationMs.coerceAtLeast(0))
        if (ownsPlayback && projects.playbackState.value !is PlaybackState.Failed) report(projects.seekTo(target))
        else prepareThen(variant) { report(projects.seekTo(target)) }
    }
    private fun prepareThen(variant: AudioVariant, after: () -> Unit) {
        updateState { it.copy(preparing = true) }
        viewModelScope.launch {
            try {
                when (val result = projects.preparePlayback(variant)) {
                    is AppResult.Success -> if (active && uiState.value.variant?.id == variant.id) {
                        ownsPlayback = true; updateState { it.copy(playback = projects.playbackState.value) }; after()
                    }
                    is AppResult.Failure -> postEffect(PreviewEffect.Error(result.error))
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                postEffect(PreviewEffect.Error(AppError("playback_failed", true)))
            } finally { updateState { it.copy(preparing = false) } }
        }
    }
    private fun report(result: AppResult<Unit>) { if (result is AppResult.Failure) postEffect(PreviewEffect.Error(result.error)) }
    private fun rename(input: String) {
        val title = input.trim()
        if (uiState.value.renaming) return
        if (title.isEmpty() || title.length > 50) { updateState { it.copy(renameError = if (title.isEmpty()) "empty" else "length") }; return }
        updateState { it.copy(renaming = true, renameError = null) }
        viewModelScope.launch {
            try {
                when (projects.renameProject(projectId, title)) {
                    is AppResult.Success -> postEffect(PreviewEffect.Renamed)
                    is AppResult.Failure -> updateState { it.copy(renameError = "failed") }
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { updateState { it.copy(renameError = "failed") } }
            finally { updateState { it.copy(renaming = false) } }
        }
    }
    private fun enhance() {
        val variant = uiState.value.variant ?: return
        if (uiState.value.starting) return
        updateState { it.copy(starting = true) }
        viewModelScope.launch {
            try {
                when (val result = startEnhancement(projectId, variant.id, EnhancementMode.default)) {
                    is AppResult.Success -> { saved["h5_task_id"] = result.value.taskId; postEffect(PreviewEffect.EnhancementStarted(result.value.taskId)) }
                    is AppResult.Failure -> { updateState { it.copy(starting = false) }; postEffect(PreviewEffect.Error(result.error)) }
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                updateState { it.copy(starting = false) }; postEffect(PreviewEffect.Error(AppError("processing_failed", true)))
            }
        }
    }
}
