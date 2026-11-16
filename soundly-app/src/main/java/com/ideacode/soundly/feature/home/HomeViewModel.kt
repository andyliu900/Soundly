package com.ideacode.soundly.feature.home

import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.savedstate.SavedStateRegistryOwner
import com.ideacode.soundly.app.AppContainer
import com.ideacode.soundly.common.state.StateActionViewModel
import com.ideacode.soundly.common.state.UiAction
import com.ideacode.soundly.common.state.UiEffect
import com.ideacode.soundly.common.state.UiState
import com.ideacode.soundly_sdk.api.model.domain.AudioProject
import com.ideacode.soundly_sdk.api.model.domain.RecordingScene
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal data class HomeUiState(
    val loading: Boolean = true,
    val recent: List<AudioProject> = emptyList(),
    val loadError: Boolean = false,
    val defaultScene: RecordingScene = RecordingScene.AUTO,
    val selectedScene: RecordingScene? = null
) : UiState {
    val displayedScene: RecordingScene get() = selectedScene ?: defaultScene
}

internal sealed interface HomeAction : UiAction {
    data class SelectScene(val scene: RecordingScene) : HomeAction
    data object StartRecording : HomeAction
    data object OpenScenePicker : HomeAction
    data object OpenImportPicker : HomeAction
    data class OpenProject(val projectId: String) : HomeAction
    data object OpenLibrary : HomeAction
    data object RetryProjects : HomeAction
}
internal sealed interface HomeEffect : UiEffect {
    data class OpenRecording(val scene: RecordingScene?) : HomeEffect
    data class OpenScenePicker(val scene: RecordingScene) : HomeEffect
    data object OpenImportPicker : HomeEffect
    data class OpenProject(val projectId: String) : HomeEffect
    data object OpenLibrary : HomeEffect
}

internal class HomeViewModel(
    private val savedState: SavedStateHandle,
    private val observeProjects: () -> Flow<List<AudioProject>>,
    private val observeDefaultScene: () -> Flow<RecordingScene>
) : StateActionViewModel<HomeUiState, HomeAction, HomeEffect>(
    HomeUiState(selectedScene = savedState.get<String>(SCENE_KEY)?.let { name ->
        RecordingScene.entries.firstOrNull { it.name == name }
    })
) {
    private var projectsJob: Job? = null
    private var effectPending = false

    init {
        observeRecent()
        viewModelScope.launch {
            try {
                observeDefaultScene().collect { scene -> updateState { it.copy(defaultScene = scene) } }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // Keep AUTO or the last observed setting; recent projects remain usable.
            }
        }
    }

    override fun dispatch(action: HomeAction) {
        when (action) {
            is HomeAction.SelectScene -> {
                savedState[SCENE_KEY] = action.scene.name
                updateState { it.copy(selectedScene = action.scene) }
            }
            HomeAction.StartRecording -> emitOnce(HomeEffect.OpenRecording(uiState.value.selectedScene))
            HomeAction.OpenScenePicker -> emitOnce(HomeEffect.OpenScenePicker(uiState.value.displayedScene))
            HomeAction.OpenImportPicker -> emitOnce(HomeEffect.OpenImportPicker)
            is HomeAction.OpenProject -> if (uiState.value.recent.any { it.id == action.projectId }) {
                emitOnce(HomeEffect.OpenProject(action.projectId))
            }
            HomeAction.OpenLibrary -> emitOnce(HomeEffect.OpenLibrary)
            HomeAction.RetryProjects -> observeRecent()
        }
    }

    fun effectHandled(id: Long, effect: HomeEffect, recordingOpened: Boolean = true) {
        if (effect is HomeEffect.OpenRecording && recordingOpened) {
            savedState.remove<String>(SCENE_KEY)
            updateState { it.copy(selectedScene = null) }
        }
        acknowledgeEffect(id)
        effectPending = false
    }

    private fun emitOnce(effect: HomeEffect) {
        if (effectPending) return
        effectPending = true
        postEffect(effect)
    }

    private fun observeRecent() {
        projectsJob?.cancel()
        projectsJob = viewModelScope.launch {
            updateState { it.copy(loading = true, loadError = false) }
            try {
                observeProjects().collect { projects ->
                    updateState { it.copy(loading = false, loadError = false,
                        recent = projects.sortedByDescending { project -> project.updatedAt }.take(2)) }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                updateState { it.copy(loading = false, loadError = true) }
            }
        }
    }

    companion object {
        private const val SCENE_KEY = "home_recording_scene"
        fun factory(owner: SavedStateRegistryOwner, args: android.os.Bundle?, container: AppContainer) =
            object : AbstractSavedStateViewModelFactory(owner, args) {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(key: String, modelClass: Class<T>, handle: SavedStateHandle): T {
                    require(modelClass == HomeViewModel::class.java)
                    return HomeViewModel(handle, container.projectGateway::observeProjects,
                        { container.settingsStorageGateway.observeSettings().map { it.defaultScene } }) as T
                }
            }
    }
}
