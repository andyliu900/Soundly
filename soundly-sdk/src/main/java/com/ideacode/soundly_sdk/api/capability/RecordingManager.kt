package com.ideacode.soundly_sdk.api.capability

import com.ideacode.soundly_sdk.api.model.domain.AudioMarker
import com.ideacode.soundly_sdk.api.model.recording.AudioMeter
import com.ideacode.soundly_sdk.api.model.recording.RecordingConfig
import com.ideacode.soundly_sdk.api.model.recording.RecordingResult
import com.ideacode.soundly_sdk.api.model.recording.RecoverableRecording
import com.ideacode.soundly_sdk.api.model.recording.RecordingSession
import com.ideacode.soundly_sdk.api.model.recording.RecordingState
import com.ideacode.soundly_sdk.api.model.recording.StaticWaveform
import com.ideacode.soundly_sdk.api.model.recording.WaveformFrame
import com.ideacode.soundly_sdk.api.result.SoundlyResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface RecordingManager {
    val state: StateFlow<RecordingState>
    val meter: Flow<AudioMeter>
    val waveform: Flow<WaveformFrame>
    val markers: StateFlow<List<AudioMarker>>

    suspend fun prepare(config: RecordingConfig): SoundlyResult<Unit>
    suspend fun prepareUsingDefaults(title: String? = null): SoundlyResult<Unit>
    suspend fun start(): SoundlyResult<RecordingSession>
    suspend fun pause(): SoundlyResult<Unit>
    suspend fun resume(): SoundlyResult<Unit>
    suspend fun addMarker(label: String? = null): SoundlyResult<AudioMarker>
    suspend fun deleteMarker(markerId: String): SoundlyResult<Unit>
    suspend fun stopAndSave(): SoundlyResult<RecordingResult>
    suspend fun discard(): SoundlyResult<Unit>
    suspend fun getRecoverableRecordings(): SoundlyResult<List<RecoverableRecording>>
    suspend fun recover(sessionId: String): SoundlyResult<RecordingResult>
    suspend fun loadStaticWaveform(
        projectId: String,
        variantId: String
    ): SoundlyResult<StaticWaveform>
}
