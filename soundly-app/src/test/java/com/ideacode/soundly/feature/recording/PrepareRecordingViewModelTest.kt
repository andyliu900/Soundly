package com.ideacode.soundly.feature.recording

import androidx.lifecycle.SavedStateHandle
import com.ideacode.soundly.common.error.AppError
import com.ideacode.soundly.common.error.AppResult
import com.ideacode.soundly_sdk.api.model.domain.RecordingScene
import com.ideacode.soundly_sdk.api.model.recording.RecordingConfig
import com.ideacode.soundly_sdk.api.model.recording.RecordingQuality
import com.ideacode.soundly_sdk.api.model.settings.SoundlySettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PrepareRecordingViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun teardown() = Dispatchers.resetMain()

    @Test fun `home scene is overridden by F07 settings and restored without changing defaults`() = runTest(dispatcher) {
        val handle = SavedStateHandle(mapOf("homeScene" to RecordingScene.MEETING.name))
        val defaults = SoundlySettings(defaultScene = RecordingScene.AUTO, recordingQuality = RecordingQuality.HIGH)
        val first = PrepareRecordingViewModel(handle, { flowOf(defaults) }) { AppResult.Success(Unit) }
        advanceUntilIdle()
        assertEquals(RecordingScene.MEETING, first.uiState.value.scene)
        first.dispatch(PrepareAction.Scene(RecordingScene.INTERVIEW))
        first.dispatch(PrepareAction.Quality(RecordingQuality.STANDARD))
        var config: RecordingConfig? = null
        val restored = PrepareRecordingViewModel(handle, { flowOf(defaults) }) { config = it; AppResult.Success(Unit) }
        advanceUntilIdle()
        restored.dispatch(PrepareAction.Start)
        advanceUntilIdle()
        assertEquals(RecordingScene.INTERVIEW, config?.scene)
        assertEquals(RecordingQuality.STANDARD, config?.quality)
        assertEquals(RecordingScene.AUTO, defaults.defaultScene)
        assertEquals(PrepareEffect.RecordingStarted, restored.effects.value.single().value)
    }

    @Test fun `rapid starts create one operation and a failure allows retry`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val model = PrepareRecordingViewModel(SavedStateHandle(), { flowOf(SoundlySettings()) }) {
            calls++; gate.await(); AppResult.Failure(AppError("failed", true))
        }
        advanceUntilIdle()
        model.dispatch(PrepareAction.Start); model.dispatch(PrepareAction.Start)
        runCurrent()
        assertEquals(1, calls); assertTrue(model.uiState.value.busy)
        gate.complete(Unit); advanceUntilIdle()
        assertFalse(model.uiState.value.busy)
        model.dispatch(PrepareAction.Start); advanceUntilIdle()
        assertEquals(2, calls)
    }
}
