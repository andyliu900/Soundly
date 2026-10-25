package com.ideacode.soundly_sdk.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingSignalAccumulatorTest {

    @Test
    fun batchesNormalizedSamplesAndFlushesTheTail() {
        val accumulator = RecordingSignalAccumulator(samplesPerFrame = 3)

        val first = accumulator.sample(0, 100L, 1_000L)
        val second = accumulator.sample(16_384, 150L, 1_050L)
        val third = accumulator.sample(32_767, 200L, 1_100L)

        assertEquals(0f, first.meter.peak, 0f)
        assertTrue(second.meter.peak in 0.49f..0.51f)
        assertEquals(1f, third.meter.peak, 0f)
        assertNull(first.waveformFrame)
        assertNull(second.waveformFrame)
        assertEquals(listOf(0f, second.meter.peak, 1f), third.waveformFrame?.amplitudes)
        assertEquals(100L, third.waveformFrame?.startPositionMs)
        assertEquals(200L, third.waveformFrame?.endPositionMs)

        accumulator.sample(8_192, 250L, 1_150L)
        val tail = accumulator.flush(275L)

        assertEquals(1, tail?.amplitudes?.size)
        assertEquals(250L, tail?.startPositionMs)
        assertEquals(275L, tail?.endPositionMs)
        assertNull(accumulator.flush(300L))
    }

    @Test
    fun resetDropsPendingSamples() {
        val accumulator = RecordingSignalAccumulator(samplesPerFrame = 2)
        accumulator.sample(1_000, 10L, 10L)

        accumulator.reset()

        assertNull(accumulator.flush(20L))
    }
}
