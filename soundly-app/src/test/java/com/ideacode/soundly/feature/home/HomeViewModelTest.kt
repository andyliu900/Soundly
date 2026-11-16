package com.ideacode.soundly.feature.home

import androidx.lifecycle.SavedStateHandle
import com.ideacode.soundly_sdk.api.model.audio.dsp.PresetOption
import com.ideacode.soundly_sdk.api.model.domain.AudioProject
import com.ideacode.soundly_sdk.api.model.domain.AudioSourceType
import com.ideacode.soundly_sdk.api.model.domain.AudioVariant
import com.ideacode.soundly_sdk.api.model.domain.RecordingScene
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    @Test fun `projects update live and show two most recent`() = runTest(dispatcher) {
        val projects = MutableStateFlow(emptyList<AudioProject>())
        val model = create(projects = projects)
        advanceUntilIdle()
        assertEquals(emptyList<AudioProject>(), model.uiState.value.recent)
        projects.value = listOf(project("a", 1), project("c", 3), project("b", 2))
        advanceUntilIdle()
        assertEquals(listOf("c", "b"), model.uiState.value.recent.map { it.id })
        projects.value = listOf(project("a", 5))
        advanceUntilIdle()
        assertEquals(listOf("a"), model.uiState.value.recent.map { it.id })
    }

    @Test fun `scene override survives recreation and applies to only one start`() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val defaults = MutableStateFlow(RecordingScene.CLASSROOM)
        val model = create(handle = handle, defaults = defaults)
        advanceUntilIdle()
        assertEquals(RecordingScene.CLASSROOM, model.uiState.value.displayedScene)
        model.dispatch(HomeAction.SelectScene(RecordingScene.MEETING))
        val restored = create(handle = handle, defaults = defaults)
        advanceUntilIdle()
        assertEquals(RecordingScene.MEETING, restored.uiState.value.displayedScene)
        restored.dispatch(HomeAction.StartRecording)
        restored.dispatch(HomeAction.StartRecording)
        assertEquals(1, restored.effects.value.size)
        val effect = restored.effects.value.single()
        assertEquals(HomeEffect.OpenRecording(RecordingScene.MEETING), effect.value)
        restored.effectHandled(effect.id, effect.value)
        assertNull(restored.uiState.value.selectedScene)
        assertEquals(RecordingScene.CLASSROOM, restored.uiState.value.displayedScene)
        assertNull(handle.get<String>("home_recording_scene"))
    }

    @Test fun `recent project tap only navigates to a currently visible project`() = runTest(dispatcher) {
        val model = create(projects = MutableStateFlow(listOf(project("valid", 3))))
        advanceUntilIdle()
        model.dispatch(HomeAction.OpenProject("missing"))
        assertEquals(0, model.effects.value.size)
        model.dispatch(HomeAction.OpenProject("valid"))
        assertEquals(HomeEffect.OpenProject("valid"), model.effects.value.single().value)
    }

    private fun create(
        handle: SavedStateHandle = SavedStateHandle(),
        projects: MutableStateFlow<List<AudioProject>> = MutableStateFlow(emptyList()),
        defaults: MutableStateFlow<RecordingScene> = MutableStateFlow(RecordingScene.AUTO)
    ) = HomeViewModel(handle, { projects }, { defaults })

    private fun project(id: String, updated: Long): AudioProject {
        val original = AudioVariant(filePath = "", presetOption = PresetOption.RAW, isOriginal = true,
            sampleRate = 44100, channels = 1, durationMs = 1000, id = "original-$id", projectId = id)
        return AudioProject(id = id, title = id, sourceType = AudioSourceType.RECORDED, scene = null,
            original = original, createdAt = updated, updatedAt = updated)
    }
}
