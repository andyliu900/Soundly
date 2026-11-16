package com.ideacode.soundly.feature.recording

import androidx.lifecycle.SavedStateHandle
import com.ideacode.soundly.common.error.AppError
import com.ideacode.soundly.common.error.AppResult
import com.ideacode.soundly.data.sdk.RecordingGateway
import com.ideacode.soundly_sdk.api.model.domain.AudioMarker
import com.ideacode.soundly_sdk.api.model.recording.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun teardown() = Dispatchers.resetMain()

    @Test fun `notification save navigates once only for current session`() = runTest(dispatcher) {
        val gateway = FakeGateway()
        val model = RecordingViewModel(gateway, SavedStateHandle())
        advanceUntilIdle()
        gateway.lastSaved.value = result("other")
        runCurrent(); assertTrue(model.effects.value.isEmpty())
        gateway.lastSaved.value = result("session")
        runCurrent(); assertEquals(RecordingEffect.Preview(result("session")), model.effects.value.single().value)
        gateway.state.value = RecordingState.Idle
        runCurrent(); assertEquals(1, model.effects.value.size)
    }
    @Test fun `save is serialized and failure is not treated as success`() = runTest(dispatcher) {
        val gateway = FakeGateway(); val model = RecordingViewModel(gateway, SavedStateHandle())
        advanceUntilIdle()
        model.dispatch(RecordingAction.Save); model.dispatch(RecordingAction.Save)
        runCurrent(); assertEquals(1, gateway.saves); assertTrue(model.uiState.value.busy)
        gateway.gate.complete(Unit); advanceUntilIdle()
        assertFalse(model.uiState.value.busy)
        assertTrue(model.effects.value.single().value is RecordingEffect.Error)
    }
    @Test fun `exit pauses before showing confirmation and return stays paused`() = runTest(dispatcher) {
        val gateway = FakeGateway(); val saved = SavedStateHandle()
        val model = RecordingViewModel(gateway, saved); advanceUntilIdle()
        model.requestDiscardConfirmation(); model.requestDiscardConfirmation(); advanceUntilIdle()
        assertEquals(1, gateway.pauses); assertTrue(gateway.state.value is RecordingState.Paused)
        assertEquals("session", model.uiState.value.discardSessionId)
        val restored = RecordingViewModel(gateway, saved); advanceUntilIdle()
        assertEquals("session", restored.uiState.value.discardSessionId)
        restored.clearDiscardConfirmation(); advanceUntilIdle()
        assertNull(restored.uiState.value.discardSessionId); assertTrue(gateway.state.value is RecordingState.Paused)
        assertEquals(0, gateway.resumes)
    }
    @Test fun `failed pause or changed session cannot discard`() = runTest(dispatcher) {
        val gateway = FakeGateway(); gateway.failPause = true
        val model = RecordingViewModel(gateway, SavedStateHandle()); advanceUntilIdle()
        model.requestDiscardConfirmation(); advanceUntilIdle()
        assertNull(model.uiState.value.discardSessionId); assertTrue(model.effects.value.single().value is RecordingEffect.Error)
        gateway.failPause = false; model.requestDiscardConfirmation(); advanceUntilIdle()
        gateway.state.value = RecordingState.Paused("other", 10, 10, 0); runCurrent()
        model.dispatch(RecordingAction.Discard); advanceUntilIdle(); assertEquals(0, gateway.discards)
    }
    @Test fun `background guard pauses only opted out active recording`() = runTest(dispatcher) {
        val gateway = FakeGateway()
        gateway.currentConfig.value = RecordingConfig(enableBackgroundNotification = true)
        pauseIfBackgroundDisabled(gateway); assertEquals(0, gateway.pauses)
        gateway.currentConfig.value = RecordingConfig(enableBackgroundNotification = false)
        pauseIfBackgroundDisabled(gateway); assertEquals(1, gateway.pauses)
        pauseIfBackgroundDisabled(gateway); assertEquals(1, gateway.pauses)
    }
    private fun result(session: String) = RecordingResult(session, "project", "variant", 1000, 12,
        StaticWaveform(1000, 50, listOf(.2f)))
    private class FakeGateway : RecordingGateway {
        override val state = MutableStateFlow<RecordingState>(RecordingState.Recording("session", 1000, 12, 0))
        override val currentConfig = MutableStateFlow<RecordingConfig?>(null)
        override val lastSaved = MutableStateFlow<RecordingResult?>(null)
        override val waveform = MutableSharedFlow<WaveformFrame>()
        override val markers = MutableStateFlow(emptyList<AudioMarker>())
        val gate = CompletableDeferred<Unit>(); var saves = 0; var pauses = 0; var resumes = 0; var discards = 0; var failPause = false
        private val failure = AppResult.Failure(AppError("failed", true))
        override fun acknowledgeSaved(sessionId: String) { if (lastSaved.value?.sessionId == sessionId) lastSaved.value = null }
        override suspend fun prepare(config: RecordingConfig) = AppResult.Success(Unit)
        override suspend fun prepareUsingDefaults(title: String?) = AppResult.Success(Unit)
        override suspend fun start() = AppResult.Success("project")
        override suspend fun pause(): AppResult<Unit> {
            pauses++
            if (failPause) return failure
            val current = state.value as? RecordingState.Recording ?: return failure
            state.value = RecordingState.Paused(current.sessionId, current.durationMs, current.recordedBytes, current.markerCount)
            return AppResult.Success(Unit)
        }
        override suspend fun pauseSession(sessionId: String): AppResult<Unit> = if ((state.value as? RecordingState.Recording)?.sessionId == sessionId) pause() else failure
        override suspend fun resume(): AppResult<Unit> { resumes++; return AppResult.Success(Unit) }
        override suspend fun addMarker() = AppResult.Success(Unit)
        override suspend fun stopAndSave(): AppResult<RecordingResult> { saves++; gate.await(); return failure }
        override suspend fun discard(): AppResult<Unit> { discards++; return AppResult.Success(Unit) }
        override suspend fun discardSession(sessionId: String): AppResult<Unit> { discards++; return AppResult.Success(Unit) }
        override suspend fun recoverable(): AppResult<List<RecoverableRecording>> = AppResult.Success(emptyList())
        override suspend fun recover(sessionId: String): AppResult<RecordingResult> = failure
        override suspend fun discardRecovery(sessionId: String) = AppResult.Success(Unit)
    }
}
