package com.ideacode.soundly_sdk.core.audio.denoise

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudxNoiseSuppressorInstrumentedTest {
    @Test
    fun packagedNativeLibraryProcessesAContinuous48kMonoStream() {
        val session = AudxNoiseSuppressorSession(sampleRate = SAMPLE_RATE, channels = 1)
        val input = FloatArray(FRAME_SAMPLES * 2) { index ->
            val carrier = sin(2.0 * PI * 220.0 * index / SAMPLE_RATE).toFloat()
            val noise = (((index * 31) % 101) - 50) / 500f
            (carrier * 0.35f + noise).coerceIn(-1f, 1f)
        }

        val output = try {
            session.process(input) + session.flush()
        } finally {
            session.close()
        }

        assertEquals(input.size, output.size)
        assertTrue(output.all(Float::isFinite))
        assertTrue(output.drop(FRAME_SAMPLES).any { sample -> sample != 0f })
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val FRAME_SAMPLES = 480
    }
}
