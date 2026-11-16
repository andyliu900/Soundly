package com.ideacode.soundly.domain.mainflow

import com.ideacode.soundly_sdk.api.model.audio.dsp.PresetOption
import com.ideacode.soundly_sdk.api.model.domain.AudioFormat
import com.ideacode.soundly_sdk.api.model.domain.RecordingScene
import com.ideacode.soundly_sdk.api.model.recording.RecordingQuality
import com.ideacode.soundly_sdk.api.model.settings.RecordingNamingStrategy
import com.ideacode.soundly_sdk.api.model.settings.SoundlySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MainFlowPolicyTest {
    @Test
    fun `recording defaults and naming are used without changing persisted settings`() {
        val defaults = SoundlySettings(
            defaultScene = RecordingScene.MEETING,
            recordingQuality = RecordingQuality.STANDARD,
            recordingFormat = AudioFormat.AAC,
            recordInBackground = false,
            namingStrategy = RecordingNamingStrategy.TIMESTAMP
        )
        val config = RecordingDraft(title = "  ").toConfig(defaults, 1234L)
        assertEquals("Recording 1234", config.title)
        assertEquals(RecordingScene.MEETING, config.scene)
        assertEquals(RecordingQuality.STANDARD, config.quality)
        assertEquals(AudioFormat.AAC, config.format)
        assertEquals(false, config.enableBackgroundNotification)
        assertEquals(RecordingScene.MEETING, defaults.defaultScene)
    }

    @Test
    fun `single recording override takes precedence over home selection and defaults`() {
        val defaults = SoundlySettings(defaultScene = RecordingScene.CLASSROOM)
        val home = RecordingDraft(homeScene = RecordingScene.MEETING)
        assertEquals(RecordingScene.MEETING, home.toConfig(defaults, 0).scene)
        val draft = home.copy(
            sceneOverride = RecordingScene.INTERVIEW,
            qualityOverride = RecordingQuality.STANDARD,
            title = "  Interview  "
        )
        val config = draft.toConfig(defaults, 0)
        assertEquals(RecordingScene.INTERVIEW, config.scene)
        assertEquals(RecordingQuality.STANDARD, config.quality)
        assertEquals("Interview", config.title)
        assertEquals(RecordingScene.CLASSROOM, defaults.defaultScene)
    }

    @Test
    fun `automatic mode uses feature driven AI and alternatives use specific presets`() {
        assertEquals(PresetOption.AI, EnhancementMode.default.preset)
        assertEquals(PresetOption.SPEECH_FOCUS, EnhancementMode.CLEAR_SPEECH.preset)
        assertEquals(PresetOption.DEFAULT, EnhancementMode.NOISE_REDUCTION.preset)
        assertEquals(PresetOption.NATURAL_SMOOTH, EnhancementMode.NATURAL.preset)
        assertEquals(RecordingScene.OTHER, RecordingScenario.VOICE_MEMO.sdkScene)
    }

    @Test
    fun `export format choices preserve source or decode compressed audio to wav`() {
        assertEquals(listOf(AudioFormat.WAV), ExportFormatOptions.availableFor(AudioFormat.WAV))
        for (source in listOf(AudioFormat.M4A, AudioFormat.AAC, AudioFormat.MP3)) {
            assertEquals(listOf(source, AudioFormat.WAV), ExportFormatOptions.availableFor(source))
            assertEquals(AudioFormat.WAV, ExportFormatOptions.initialFormat(source, AudioFormat.WAV))
            assertEquals(source, ExportFormatOptions.initialFormat(source, AudioFormat.UNKNOWN))
        }
        assertEquals(AudioFormat.WAV, ExportFormatOptions.initialFormat(AudioFormat.WAV, AudioFormat.M4A))
        assertEquals(emptyList<AudioFormat>(), ExportFormatOptions.availableFor(AudioFormat.UNKNOWN))
        assertNull(ExportFormatOptions.initialFormat(AudioFormat.UNKNOWN, AudioFormat.WAV))
    }
}
