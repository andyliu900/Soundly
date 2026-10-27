package com.ideacode.soundly_sdk.core.audio.denoise

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudxNoiseSuppressorSessionTest {

    @Test
    fun arbitraryChunksAreReframedAndTailKeepsOriginalLength() {
        val processors = mutableListOf<PassThroughProcessor>()
        val session = AudxNoiseSuppressorSession(
            sampleRate = SAMPLE_RATE,
            channels = 1,
            processorFactory = processorFactory(processors)
        )
        val input = pcmFloatSamples(15)

        val first = session.process(input.copyOfRange(0, 7))
        val second = session.process(input.copyOfRange(7, input.size))
        val tail = session.flush()
        session.close()

        assertEquals(0, first.size)
        assertEquals(FRAME_SAMPLES, second.size)
        assertEquals(input.size - FRAME_SAMPLES, tail.size)
        assertArrayEquals(input, second + tail, 0f)
        assertEquals(2, processors.single().processCalls)
        assertTrue(processors.single().closed)
    }

    @Test
    fun stereoUsesIndependentStateAndPreservesInterleaving() {
        val processors = mutableListOf<PassThroughProcessor>()
        val session = AudxNoiseSuppressorSession(
            sampleRate = SAMPLE_RATE,
            channels = 2,
            processorFactory = processorFactory(processors)
        )
        val left = pcmFloatSamples(13)
        val right = pcmFloatSamples(13).map { -it }.toFloatArray()
        val interleaved = FloatArray(left.size * 2) { index ->
            if (index % 2 == 0) left[index / 2] else right[index / 2]
        }

        val output = session.process(interleaved) + session.flush()
        session.close()

        assertArrayEquals(interleaved, output, 0f)
        assertEquals(2, processors.size)
        assertTrue(processors.all { it.processCalls == 2 && it.closed })
    }

    private fun processorFactory(
        processors: MutableList<PassThroughProcessor>
    ) = Pcm16NoiseProcessorFactory {
        PassThroughProcessor().also(processors::add)
    }

    private fun pcmFloatSamples(size: Int): FloatArray = FloatArray(size) { index ->
        ((index - size / 2) * 256).toShort() / PCM16_SCALE
    }

    private class PassThroughProcessor : Pcm16NoiseProcessor {
        var processCalls = 0
        var closed = false

        override fun process(input: ShortArray, output: ShortArray) {
            processCalls++
            input.copyInto(output)
        }

        override fun close() {
            closed = true
        }
    }

    private companion object {
        const val SAMPLE_RATE = 1_000
        const val FRAME_SAMPLES = SAMPLE_RATE / 100
        const val PCM16_SCALE = 32_768f
    }
}
