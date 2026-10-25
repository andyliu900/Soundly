package com.ideacode.soundly_sdk.core.audio.analyze

import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFeatureAccumulatorTest {
    @Test
    fun chunkedAnalysisMatchesWholeBufferAnalysis() {
        val pcm = FloatArray(20_003) { index ->
            (sin(index * 0.017) * 0.2).toFloat()
        }
        val expected = DefaultAudioAnalyzer().analyze(pcm, 44_100)
        val accumulator = AudioFeatureAccumulator()
        var offset = 0
        val chunkSizes = intArrayOf(1, 7, 257, 1_024, 31)
        var chunkIndex = 0
        while (offset < pcm.size) {
            val end = minOf(pcm.size, offset + chunkSizes[chunkIndex % chunkSizes.size])
            accumulator.addMono(pcm.copyOfRange(offset, end))
            offset = end
            chunkIndex++
        }

        val actual = accumulator.finish()
        assertEquals(expected.rms, actual.rms, 1e-6f)
        assertEquals(expected.zeroCrossingRate, actual.zeroCrossingRate, 1e-6f)
        assertEquals(expected.spectralFlatness, actual.spectralFlatness, 1e-6f)
        assertEquals(expected.speechConfidence, actual.speechConfidence, 1e-6f)
        assertTrue(actual.isValid)
    }

    @Test
    fun stereoAnalysisDownmixesPerFrameWithoutAllocatingMonoTrack() {
        val accumulator = AudioFeatureAccumulator()
        accumulator.addInterleaved(
            floatArrayOf(0.4f, 0.2f, -0.4f, -0.2f, 0.2f, 0.0f),
            channels = 2
        )
        val expected = DefaultAudioAnalyzer().analyze(
            floatArrayOf(0.3f, -0.3f, 0.1f),
            44_100
        )

        val actual = accumulator.finish()
        assertEquals(expected.rms, actual.rms, 1e-6f)
        assertEquals(expected.zeroCrossingRate, actual.zeroCrossingRate, 1e-6f)
        assertEquals(expected.spectralFlatness, actual.spectralFlatness, 1e-6f)
    }
}
