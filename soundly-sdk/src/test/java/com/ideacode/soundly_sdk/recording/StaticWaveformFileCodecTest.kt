package com.ideacode.soundly_sdk.recording

import com.ideacode.soundly_sdk.api.model.recording.StaticWaveform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StaticWaveformFileCodecTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun roundTripsWaveformWithoutLosingSamples() {
        val waveform = StaticWaveform(
            durationMs = 2_000L,
            sampleIntervalMs = 50,
            amplitudes = listOf(0f, 0.25f, 0.75f, 1f)
        )
        val file = temporaryFolder.newFile("waveform.bin")
        file.writeBytes(StaticWaveformFileCodec.encode(waveform))

        assertEquals(waveform, StaticWaveformFileCodec.decode(file))
    }

    @Test
    fun rejectsCorruptWaveformPayload() {
        val file = temporaryFolder.newFile("corrupt.bin").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }

        assertThrows(Exception::class.java) {
            StaticWaveformFileCodec.decode(file)
        }
    }
}
