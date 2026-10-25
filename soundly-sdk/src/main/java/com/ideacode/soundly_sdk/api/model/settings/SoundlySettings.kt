package com.ideacode.soundly_sdk.api.model.settings

import com.ideacode.soundly_sdk.api.model.domain.AudioFormat
import com.ideacode.soundly_sdk.api.model.domain.RecordingScene
import com.ideacode.soundly_sdk.api.model.recording.RecordingConfig
import com.ideacode.soundly_sdk.api.model.recording.RecordingQuality
import com.ideacode.soundly_sdk.api.result.SoundlyResult
import kotlinx.coroutines.flow.Flow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class RecordingNamingStrategy {
    /** Human-readable local date and time, for example Recording 2026-10-25 14-30. */
    DATE_TIME,

    /** Stable epoch-millisecond suffix, useful when recordings are created rapidly. */
    TIMESTAMP
}

data class SoundlySettings(
    val defaultScene: RecordingScene = RecordingScene.AUTO,
    val recordingQuality: RecordingQuality = RecordingQuality.HIGH,
    val outputFormat: AudioFormat = AudioFormat.WAV,
    val recordInBackground: Boolean = true,
    val recordingFormat: AudioFormat = AudioFormat.M4A,
    val namingStrategy: RecordingNamingStrategy = RecordingNamingStrategy.DATE_TIME
)

interface SoundlySettingsRepository {
    fun observe(): Flow<SoundlySettings>
    suspend fun get(): SoundlyResult<SoundlySettings>
    suspend fun update(settings: SoundlySettings): SoundlyResult<Unit>
    suspend fun reset(): SoundlyResult<Unit>
}

fun SoundlySettings.toRecordingConfig(title: String? = null): RecordingConfig =
    RecordingConfig(
        title = title,
        scene = defaultScene,
        format = recordingFormat,
        quality = recordingQuality,
        enableBackgroundNotification = recordInBackground
    )

/** Creates the SDK fallback title used when the host does not provide one. */
fun SoundlySettings.createRecordingTitle(timestampMs: Long): String = when (namingStrategy) {
    RecordingNamingStrategy.DATE_TIME -> {
        val formatted = SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.US).format(Date(timestampMs))
        "Recording $formatted"
    }

    RecordingNamingStrategy.TIMESTAMP -> "Recording $timestampMs"
}
