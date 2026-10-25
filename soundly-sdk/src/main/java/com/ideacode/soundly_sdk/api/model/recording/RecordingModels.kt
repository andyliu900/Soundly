package com.ideacode.soundly_sdk.api.model.recording

import com.ideacode.soundly_sdk.api.error.SoundlyError
import com.ideacode.soundly_sdk.api.model.domain.AudioFormat
import com.ideacode.soundly_sdk.api.model.domain.AudioMarker
import com.ideacode.soundly_sdk.api.model.domain.RecordingScene

data class RecordingConfig(
    val title: String? = null,
    val scene: RecordingScene = RecordingScene.AUTO,
    val format: AudioFormat = AudioFormat.M4A,
    val quality: RecordingQuality = RecordingQuality.HIGH,
    val sampleRate: Int = 44_100,
    val channels: Int = 1,
    val enableBackgroundNotification: Boolean = true
)

enum class RecordingQuality {
    STANDARD,
    HIGH
}

data class RecordingSession(
    val id: String,
    val projectId: String,
    val startedAt: Long,
    val config: RecordingConfig
)

data class RecordingResult(
    val sessionId: String,
    val projectId: String,
    val originalVariantId: String,
    val durationMs: Long,
    val fileSizeBytes: Long,
    val waveform: StaticWaveform
)

data class RecoverableRecording(
    val sessionId: String,
    val projectId: String,
    val recordedDurationMs: Long,
    val recordedBytes: Long,
    val updatedAt: Long
)

sealed interface RecordingState {
    data object Idle : RecordingState
    data object Preparing : RecordingState

    data class Ready(
        val sessionId: String,
        val projectId: String
    ) : RecordingState

    data class Recording(
        val sessionId: String,
        val durationMs: Long,
        val recordedBytes: Long,
        val markerCount: Int
    ) : RecordingState

    data class Paused(
        val sessionId: String,
        val durationMs: Long,
        val recordedBytes: Long,
        val markerCount: Int
    ) : RecordingState

    data class Interrupted(
        val sessionId: String,
        val reason: RecordingInterruptionReason,
        val durationMs: Long = 0L,
        val recordedBytes: Long = 0L,
        val markerCount: Int = 0
    ) : RecordingState

    data class Finalizing(
        val sessionId: String,
        val percent: Int? = null
    ) : RecordingState

    data class Failed(
        val sessionId: String?,
        val error: SoundlyError
    ) : RecordingState
}

enum class RecordingInterruptionReason {
    AUDIO_FOCUS_LOST,
    PHONE_CALL,
    INPUT_DEVICE_DISCONNECTED,
    MICROPHONE_UNAVAILABLE,
    INSUFFICIENT_STORAGE,
    UNKNOWN
}

data class AudioMeter(
    val peak: Float,
    val rms: Float,
    val measuredAtMs: Long
)

data class WaveformFrame(
    val amplitudes: List<Float>,
    val startPositionMs: Long,
    val endPositionMs: Long
)

/** Downsampled, normalized waveform persisted for a completed audio version. */
data class StaticWaveform(
    val durationMs: Long,
    val sampleIntervalMs: Int,
    val amplitudes: List<Float>
)

data class MarkerResult(
    val marker: AudioMarker
)
