package com.ideacode.soundly.feature.recording

import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.savedstate.SavedStateRegistryOwner
import com.ideacode.soundly.app.AppContainer
import com.ideacode.soundly.common.error.AppError
import com.ideacode.soundly.common.error.AppResult
import com.ideacode.soundly.common.state.StateActionViewModel
import com.ideacode.soundly.common.state.UiAction
import com.ideacode.soundly.common.state.UiEffect
import com.ideacode.soundly.common.state.UiState
import com.ideacode.soundly.domain.mainflow.RecordingDraft
import com.ideacode.soundly_sdk.api.model.domain.RecordingScene
import com.ideacode.soundly_sdk.api.model.recording.RecordingConfig
import com.ideacode.soundly_sdk.api.model.recording.RecordingQuality
import com.ideacode.soundly_sdk.api.model.settings.SoundlySettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class PrepareUiState(val defaults: SoundlySettings? = null, val scene: RecordingScene? = null,
    val quality: RecordingQuality? = null, val loading: Boolean = true, val busy: Boolean = false,
    val loadError: Boolean = false) : UiState
sealed interface PrepareAction : UiAction {
    data class Scene(val value: RecordingScene) : PrepareAction
    data class Quality(val value: RecordingQuality) : PrepareAction
    data object Load : PrepareAction
    data object Start : PrepareAction
}
sealed interface PrepareEffect : UiEffect {
    data object RecordingStarted : PrepareEffect
    data class Error(val error: AppError) : PrepareEffect
}

/** Owns one draft and one start operation independently of the Fragment view lifecycle. */
class PrepareRecordingViewModel(
    private val saved: SavedStateHandle,
    private val settings: () -> Flow<SoundlySettings>,
    private val startRecording: suspend (RecordingConfig) -> AppResult<Unit>
) : StateActionViewModel<PrepareUiState, PrepareAction, PrepareEffect>(PrepareUiState()) {
    init { load() }
    override fun dispatch(action: PrepareAction) {
        when (action) {
            PrepareAction.Load -> load()
            is PrepareAction.Scene -> if (!uiState.value.busy) {
                saved[SCENE] = action.value.name; updateState { it.copy(scene = action.value) }
            }
            is PrepareAction.Quality -> if (!uiState.value.busy) {
                saved[QUALITY] = action.value.name; updateState { it.copy(quality = action.value) }
            }
            PrepareAction.Start -> start()
        }
    }
    fun recordingNavigationHandled() { updateState { it.copy(busy = false) } }

    /** The activity may have routed to an active session while this view was stopped. */
    fun clearFinishedNavigation() {
        val pending = effects.value.firstOrNull { it.value == PrepareEffect.RecordingStarted } ?: return
        acknowledgeEffect(pending.id)
        recordingNavigationHandled()
    }

    private fun load() {
        if (uiState.value.busy) return
        updateState { it.copy(loading = true, loadError = false) }
        viewModelScope.launch {
            try {
                val defaults = settings().first()
                val scene = saved.get<String>(SCENE)?.let { name -> RecordingScene.entries.firstOrNull { it.name == name } }
                    ?: saved.get<String>("homeScene")?.let { name -> RecordingScene.entries.firstOrNull { it.name == name } }
                    ?: defaults.defaultScene
                val quality = saved.get<String>(QUALITY)?.let { name -> RecordingQuality.entries.firstOrNull { it.name == name } } ?: defaults.recordingQuality
                updateState { it.copy(defaults = defaults, scene = scene, quality = quality, loading = false) }
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                updateState { it.copy(loading = false, loadError = true) }
            }
        }
    }
    private fun start() {
        val current = uiState.value
        val defaults = current.defaults ?: return
        if (current.busy || current.loading) return
        val config = RecordingDraft(sceneOverride = current.scene, qualityOverride = current.quality)
            .toConfig(defaults, System.currentTimeMillis())
        updateState { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                when (val result = startRecording(config)) {
                    is AppResult.Success -> postEffect(PrepareEffect.RecordingStarted)
                    is AppResult.Failure -> { updateState { it.copy(busy = false) }; postEffect(PrepareEffect.Error(result.error)) }
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                updateState { it.copy(busy = false) }; postEffect(PrepareEffect.Error(AppError("recording_start_failed", true)))
            }
        }
    }
    companion object {
        private const val SCENE = "prepare_scene"
        private const val QUALITY = "prepare_quality"
        fun factory(owner: SavedStateRegistryOwner, args: android.os.Bundle?, container: AppContainer) =
            object : AbstractSavedStateViewModelFactory(owner, args) {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(key: String, modelClass: Class<T>, handle: SavedStateHandle): T {
                    require(modelClass == PrepareRecordingViewModel::class.java)
                    val useCase = StartRecordingUseCase(container.recordingGateway)
                    return PrepareRecordingViewModel(handle, container.settingsStorageGateway::observeSettings, useCase::invoke) as T
                }
            }
    }
}
