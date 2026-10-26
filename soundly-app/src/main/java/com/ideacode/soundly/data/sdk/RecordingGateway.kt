package com.ideacode.soundly.data.sdk

import com.ideacode.soundly.common.error.AppResult
import com.ideacode.soundly_sdk.api.model.domain.AudioMarker
import com.ideacode.soundly_sdk.api.model.recording.RecordingConfig
import com.ideacode.soundly_sdk.api.model.recording.RecordingResult
import com.ideacode.soundly_sdk.api.model.recording.RecordingState
import com.ideacode.soundly_sdk.api.model.recording.RecoverableRecording
import com.ideacode.soundly_sdk.api.model.recording.WaveformFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface RecordingGateway {
    val state: StateFlow<RecordingState>
    val waveform: Flow<WaveformFrame>
    val markers: StateFlow<List<AudioMarker>>

    suspend fun prepare(config: RecordingConfig): AppResult<Unit>
    suspend fun prepareUsingDefaults(title: String?): AppResult<Unit>
    suspend fun start(): AppResult<String>
    suspend fun pause(): AppResult<Unit>
    suspend fun resume(): AppResult<Unit>
    suspend fun addMarker(): AppResult<Unit>
    suspend fun stopAndSave(): AppResult<RecordingResult>
    suspend fun discard(): AppResult<Unit>
    suspend fun recoverable(): AppResult<List<RecoverableRecording>>
    suspend fun recover(sessionId: String): AppResult<RecordingResult>
}

class DefaultRecordingGateway(
    private val provider: SoundlySdkProvider
) : RecordingGateway {
    private val recorder get() = provider.requireSdk().recorder

    override val state: StateFlow<RecordingState>
        get() = recorder.state
    override val waveform: Flow<WaveformFrame>
        get() = recorder.waveform
    override val markers: StateFlow<List<AudioMarker>>
        get() = recorder.markers

    override suspend fun prepare(config: RecordingConfig): AppResult<Unit> =
        recorder.prepare(config).toAppResult()
    override suspend fun prepareUsingDefaults(title: String?): AppResult<Unit> =
        recorder.prepareUsingDefaults(title).toAppResult()

    override suspend fun start(): AppResult<String> = recorder.start().toAppResult().map { it.projectId }
    override suspend fun pause(): AppResult<Unit> = recorder.pause().toAppResult()
    override suspend fun resume(): AppResult<Unit> = recorder.resume().toAppResult()
    override suspend fun addMarker(): AppResult<Unit> = recorder.addMarker().toAppResult().map { Unit }
    override suspend fun stopAndSave(): AppResult<RecordingResult> = recorder.stopAndSave().toAppResult()
    override suspend fun discard(): AppResult<Unit> = recorder.discard().toAppResult()
    override suspend fun recoverable(): AppResult<List<RecoverableRecording>> =
        recorder.getRecoverableRecordings().toAppResult()
    override suspend fun recover(sessionId: String): AppResult<RecordingResult> =
        recorder.recover(sessionId).toAppResult()
}
