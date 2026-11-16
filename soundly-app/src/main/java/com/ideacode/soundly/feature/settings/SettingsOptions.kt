package com.ideacode.soundly.feature.settings

import androidx.annotation.StringRes
import com.ideacode.soundly.R
import com.ideacode.soundly_sdk.api.model.domain.AudioFormat
import com.ideacode.soundly_sdk.api.model.recording.RecordingQuality
import com.ideacode.soundly_sdk.api.model.settings.RecordingNamingStrategy

/** Supported choices mirror the SDK validation. Export still checks the source-specific capabilities. */
internal object SettingsOptions {
    val recordingFormats = listOf(AudioFormat.M4A, AudioFormat.AAC)
    val outputFormats = listOf(AudioFormat.WAV, AudioFormat.M4A, AudioFormat.AAC, AudioFormat.MP3)
    val qualities = listOf(RecordingQuality.STANDARD, RecordingQuality.HIGH)
    val naming = listOf(RecordingNamingStrategy.DATE_TIME, RecordingNamingStrategy.TIMESTAMP)
    @StringRes fun quality(value: RecordingQuality) = when (value) {
        RecordingQuality.STANDARD -> R.string.s4_quality_standard
        RecordingQuality.HIGH -> R.string.s4_quality_high
    }
    @StringRes fun naming(value: RecordingNamingStrategy) = when (value) {
        RecordingNamingStrategy.DATE_TIME -> R.string.s4_naming_date
        RecordingNamingStrategy.TIMESTAMP -> R.string.s4_naming_timestamp
    }
}
