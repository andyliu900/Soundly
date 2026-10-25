package com.ideacode.soundly_sdk.editing

import com.ideacode.soundly_sdk.core.util.WavWriter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WavTrimmerTest {

    private val directory = Files.createTempDirectory("soundly-wav-trim").toFile()

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun trimWritesValidPcmWavWithExpectedDuration() {
        val source = WavWriter(directory).write(
            pcm = FloatArray(44_100) { 0.1f },
            sampleRate = 44_100,
            channels = 1,
            fileName = "source"
        )
        val destination = File(directory, "trimmed.wav")

        WavTrimmer.trim(source, destination, startMs = 250L, endMs = 750L)

        assertTrue(destination.isFile)
        assertEquals("RIFF", destination.inputStream().use {
            val header = ByteArray(4)
            it.read(header)
            String(header, Charsets.US_ASCII)
        })
        val expectedDataBytes = 44_100L * 2L / 2L
        assertEquals(44L + expectedDataBytes, destination.length())
    }

    @Test(expected = IllegalArgumentException::class)
    fun trimRejectsEmptyRange() {
        val source = WavWriter(directory).write(
            pcm = FloatArray(8_000),
            sampleRate = 8_000,
            channels = 1,
            fileName = "empty-range-source"
        )

        WavTrimmer.trim(source, File(directory, "out.wav"), 200L, 200L)
    }
}
