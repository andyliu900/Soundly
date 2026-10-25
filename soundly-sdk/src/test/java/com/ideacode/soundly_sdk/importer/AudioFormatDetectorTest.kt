package com.ideacode.soundly_sdk.importer

import com.ideacode.soundly_sdk.api.model.domain.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioFormatDetectorTest {

    @Test
    fun detectsSupportedContainerMimeTypes() {
        assertEquals(AudioFormat.WAV, AudioFormatDetector.detect("audio/x-wav", null))
        assertEquals(AudioFormat.M4A, AudioFormatDetector.detect("audio/mp4", null))
        assertEquals(AudioFormat.AAC, AudioFormatDetector.detect("audio/aac", null))
        assertEquals(AudioFormat.MP3, AudioFormatDetector.detect("audio/mpeg", null))
    }

    @Test
    fun fallsBackToExtensionForRawExtractorMime() {
        assertEquals(AudioFormat.WAV, AudioFormatDetector.detect("audio/raw", "voice.WAV"))
        assertEquals(AudioFormat.M4A, AudioFormatDetector.detect("audio/mp4a-latm", "voice.m4a"))
        assertEquals(AudioFormat.AAC, AudioFormatDetector.detect("audio/mp4a-latm", "voice.aac"))
        assertEquals(
            AudioFormat.M4A,
            AudioFormatDetector.detect("audio/mp4a-latm", "session.m4a.partial")
        )
        assertEquals(
            AudioFormat.AAC,
            AudioFormatDetector.detect("audio/mp4a-latm", "session.aac.partial")
        )
    }

    @Test
    fun rejectsUnsupportedContainers() {
        assertNull(AudioFormatDetector.detect("video/mp4", "clip.mp4v"))
        assertNull(AudioFormatDetector.detect("application/octet-stream", "audio.flac"))
    }
}
