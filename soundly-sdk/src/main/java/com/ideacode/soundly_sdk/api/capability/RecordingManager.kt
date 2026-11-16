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
    /** Most recent successful save in this process, including notification actions. */
    val lastSaved: StateFlow<RecordingResult?>
    val currentConfig: StateFlow<RecordingConfig?>
    fun acknowledgeSaved(sessionId: String)

    suspend fun prepare(config: RecordingConfig): SoundlyResult<Unit>
    suspend fun prepareUsingDefaults(title: String? = null): SoundlyResult<Unit>
    suspend fun start(): SoundlyResult<RecordingSession>
    suspend fun pause(): SoundlyResult<Unit>
    suspend fun pauseSession(sessionId: String): SoundlyResult<Unit> =
        SoundlyResult.Failure(com.ideacode.soundly_sdk.api.error.SoundlyError.InvalidState("pauseSession"))
    suspend fun resume(): SoundlyResult<Unit>
    suspend fun resumeSession(sessionId: String): SoundlyResult<Unit> =
        SoundlyResult.Failure(com.ideacode.soundly_sdk.api.error.SoundlyError.InvalidState("resumeSession"))
    suspend fun addMarker(label: String? = null): SoundlyResult<AudioMarker>
    suspend fun deleteMarker(markerId: String): SoundlyResult<Unit>
    suspend fun stopAndSave(): SoundlyResult<RecordingResult>
    suspend fun stopAndSaveSession(sessionId: String): SoundlyResult<RecordingResult> =
        SoundlyResult.Failure(com.ideacode.soundly_sdk.api.error.SoundlyError.InvalidState("stopAndSaveSession"))
    suspend fun discard(): SoundlyResult<Unit>
    /** Discards only if this is still the active session. */
    suspend fun discardSession(sessionId: String): SoundlyResult<Unit> =
        SoundlyResult.Failure(com.ideacode.soundly_sdk.api.error.SoundlyError.InvalidState("discardSession"))
    suspend fun getRecoverableRecordings(): SoundlyResult<List<RecoverableRecording>>
    suspend fun recover(sessionId: String): SoundlyResult<RecordingResult>
    /** Deletes only the specified inactive recovery session. */
    suspend fun discardRecovery(sessionId: String): SoundlyResult<Unit>
    suspend fun loadStaticWaveform(
        projectId: String,
        variantId: String
    ): SoundlyResult<StaticWaveform>
    /** Loads a cached waveform or generates one from the selected local version. */
    suspend fun getOrGenerateStaticWaveform(projectId: String, variantId: String): SoundlyResult<StaticWaveform> =
        loadStaticWaveform(projectId, variantId)
}
