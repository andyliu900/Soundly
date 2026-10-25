package com.ideacode.soundly_sdk.api.model

import com.ideacode.soundly_sdk.api.model.domain.AudioFormat
import com.ideacode.soundly_sdk.api.model.domain.RecordingScene
import com.ideacode.soundly_sdk.api.model.recording.RecordingConfig
import com.ideacode.soundly_sdk.api.model.recording.RecordingQuality
import com.ideacode.soundly_sdk.api.model.settings.SoundlySettings
import com.ideacode.soundly_sdk.api.model.settings.RecordingNamingStrategy
import com.ideacode.soundly_sdk.api.model.settings.createRecordingTitle
import com.ideacode.soundly_sdk.api.model.settings.toRecordingConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class DomainModelTest {

    @Test
    fun recordingConfigHasProductSafeDefaults() {
        val config = RecordingConfig()

        assertEquals(RecordingScene.AUTO, config.scene)
        assertEquals(AudioFormat.M4A, config.format)
        assertEquals(RecordingQuality.HIGH, config.quality)
        assertEquals(44_100, config.sampleRate)
        assertEquals(1, config.channels)
    }

    @Test
    fun settingsMapToRecordingDefaults() {
        val config = SoundlySettings(
            defaultScene = RecordingScene.INTERVIEW,
            recordingQuality = RecordingQuality.STANDARD,
            recordInBackground = false,
            recordingFormat = AudioFormat.AAC
        ).toRecordingConfig("Interview")

        assertEquals("Interview", config.title)
        assertEquals(RecordingScene.INTERVIEW, config.scene)
        assertEquals(RecordingQuality.STANDARD, config.quality)
        assertEquals(AudioFormat.AAC, config.format)
        assertEquals(false, config.enableBackgroundNotification)
    }

    @Test
    fun timestampNamingStrategyCreatesDeterministicFallbackTitle() {
        val settings = SoundlySettings(
            namingStrategy = RecordingNamingStrategy.TIMESTAMP
        )

        assertEquals("Recording 123456", settings.createRecordingTitle(123_456L))
    }
}
