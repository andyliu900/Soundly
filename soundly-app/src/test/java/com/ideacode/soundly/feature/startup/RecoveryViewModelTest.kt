package com.ideacode.soundly.feature.startup

import androidx.lifecycle.SavedStateHandle
import com.ideacode.soundly.common.error.AppError
import com.ideacode.soundly.common.error.AppResult
import com.ideacode.soundly.data.sdk.RecordingGateway
import com.ideacode.soundly_sdk.api.model.domain.AudioMarker
import com.ideacode.soundly_sdk.api.model.recording.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecoveryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun teardown() = Dispatchers.resetMain()
    @Test fun `discard failure stays on session and never returns home`() = runTest(dispatcher) {
        val fake = FakeRecovery(); fake.failDiscard = true
        val model = RecoveryViewModel(fake, SavedStateHandle(), "a")
        advanceUntilIdle(); model.dispatch(RecoveryAction.Discard); advanceUntilIdle()
        assertEquals("a", model.uiState.value.session?.sessionId)
        assertTrue(model.effects.value.single().value is RecoveryEffect.Error)
        assertEquals(listOf("a"), fake.discarded)
    }
    @Test fun `discard advances to next session and restores selection`() = runTest(dispatcher) {
        val fake = FakeRecovery(); val saved = SavedStateHandle()
        val model = RecoveryViewModel(fake, saved, "a")
        advanceUntilIdle(); model.dispatch(RecoveryAction.Discard); advanceUntilIdle()
        assertEquals("b", model.uiState.value.session?.sessionId)
        val restored = RecoveryViewModel(fake, saved, "a")
        advanceUntilIdle(); assertEquals("b", restored.uiState.value.session?.sessionId)
        restored.dispatch(RecoveryAction.Discard); advanceUntilIdle()
        assertEquals(RecoveryEffect.Home, restored.effects.value.single().value)
    }
    private class FakeRecovery : RecordingGateway {
        override val state = MutableStateFlow<RecordingState>(RecordingState.Idle)
        override val currentConfig = MutableStateFlow<RecordingConfig?>(null)
        override val lastSaved = MutableStateFlow<RecordingResult?>(null)
        override val waveform = emptyFlow<WaveformFrame>()
        override val markers = MutableStateFlow(emptyList<AudioMarker>())
        val sessions = mutableListOf(RecoverableRecording("a", "pa", 100, 10, 1), RecoverableRecording("b", "pb", 200, 20, 2))
        val discarded = mutableListOf<String>(); var failDiscard = false
        private val failure = AppResult.Failure(AppError("failed", true))
        override fun acknowledgeSaved(sessionId: String) = Unit
        override suspend fun prepare(config: RecordingConfig) = AppResult.Success(Unit)
        override suspend fun prepareUsingDefaults(title: String?) = AppResult.Success(Unit)
        override suspend fun start() = AppResult.Success("id")
        override suspend fun pause() = AppResult.Success(Unit)
        override suspend fun resume() = AppResult.Success(Unit)
        override suspend fun addMarker() = AppResult.Success(Unit)
        override suspend fun stopAndSave(): AppResult<RecordingResult> = failure
        override suspend fun discard() = AppResult.Success(Unit)
        override suspend fun recoverable(): AppResult<List<RecoverableRecording>> = AppResult.Success(sessions.toList())
        override suspend fun recover(sessionId: String): AppResult<RecordingResult> = failure
        override suspend fun discardRecovery(sessionId: String): AppResult<Unit> {
            discarded += sessionId
            if (failDiscard) return failure
            sessions.removeAll { it.sessionId == sessionId }
            return AppResult.Success(Unit)
        }
    }
}
