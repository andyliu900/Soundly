package com.ideacode.soundly.feature.recording

import com.ideacode.soundly.common.error.AppError
import com.ideacode.soundly.common.error.AppResult
import com.ideacode.soundly.data.sdk.RecordingGateway
import com.ideacode.soundly_sdk.api.error.SoundlyError
import com.ideacode.soundly_sdk.api.model.domain.AudioMarker
import com.ideacode.soundly_sdk.api.model.recording.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class StartRecordingUseCaseTest {
    @Test fun `prepare failure does not start`() = runTest {
        val gateway = FakeGateway().apply { prepareFailure = true }
        assertTrue(StartRecordingUseCase(gateway)(RecordingConfig()) is AppResult.Failure)
        assertEquals(0, gateway.starts)
    }
    @Test fun `start failure clears only an empty ready session`() = runTest {
        val gateway = FakeGateway().apply { failStart = true }
        assertTrue(StartRecordingUseCase(gateway)(RecordingConfig()) is AppResult.Failure)
        assertEquals(1, gateway.discards)
        val recovery = FakeGateway().apply { failStart = true; failWithRecovery = true }
        assertTrue(StartRecordingUseCase(recovery)(RecordingConfig()) is AppResult.Failure)
        assertEquals(0, recovery.discards)
        assertTrue(StartRecordingUseCase(recovery)(RecordingConfig()) is AppResult.Failure)
        assertEquals(1, recovery.prepares)
    }
    private class FakeGateway : RecordingGateway {
        override val state = MutableStateFlow<RecordingState>(RecordingState.Idle)
        override val currentConfig = MutableStateFlow<RecordingConfig?>(null)
        override fun acknowledgeSaved(sessionId: String) = Unit
        override val lastSaved = MutableStateFlow<RecordingResult?>(null)
        override val waveform = emptyFlow<WaveformFrame>()
        override val markers = MutableStateFlow(emptyList<AudioMarker>())
        var prepareFailure = false; var failStart = false; var failWithRecovery = false
        var prepares = 0; var starts = 0; var discards = 0
        private val failure = AppResult.Failure(AppError("failed", true))
        override suspend fun prepare(config: RecordingConfig): AppResult<Unit> {
            prepares++; if (prepareFailure) return failure
            state.value = RecordingState.Ready("session", "project"); return AppResult.Success(Unit)
        }
        override suspend fun start(): AppResult<String> {
            starts++; if (failWithRecovery) state.value = RecordingState.Failed("session", SoundlyError.MicrophoneUnavailable)
            return if (failStart) failure else AppResult.Success("project")
        }
        override suspend fun discard(): AppResult<Unit> { discards++; state.value = RecordingState.Idle; return AppResult.Success(Unit) }
        override suspend fun prepareUsingDefaults(title: String?) = AppResult.Success(Unit)
        override suspend fun pause() = AppResult.Success(Unit)
        override suspend fun resume() = AppResult.Success(Unit)
        override suspend fun addMarker() = AppResult.Success(Unit)
        override suspend fun stopAndSave(): AppResult<RecordingResult> = failure
        override suspend fun recoverable(): AppResult<List<RecoverableRecording>> = AppResult.Success(emptyList())
        override suspend fun discardRecovery(sessionId: String) = AppResult.Success(Unit)
        override suspend fun recover(sessionId: String): AppResult<RecordingResult> = failure
    }
}
