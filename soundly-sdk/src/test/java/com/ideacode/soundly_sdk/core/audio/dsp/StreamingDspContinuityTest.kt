package com.ideacode.soundly_sdk.core.audio.dsp

import com.ideacode.soundly_sdk.core.audio.dsp.model.DspPreset
import com.ideacode.soundly_sdk.core.audio.dsp.pipeline.DefaultDspPipeline
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class StreamingDspContinuityTest {
    @Test
    fun chunkedProcessingIsSampleIdenticalToContinuousProcessing() {
        val input = FloatArray(40_000) { index ->
            (sin(index * 0.031) * if (index < 20_000) 0.02 else 0.9).toFloat()
        }
        val continuous = DefaultDspPipeline().apply { configure(DspPreset.podcastInterview()) }
            .process(input, SAMPLE_RATE, CHANNELS)
        val chunkedPipeline = DefaultDspPipeline().apply { configure(DspPreset.podcastInterview()) }
        val chunks = mutableListOf<FloatArray>()
        var offset = 0
        val frameChunks = intArrayOf(127, 1_024, 333, 2_048)
        var index = 0
        while (offset < input.size) {
            val sampleCount = frameChunks[index % frameChunks.size] * CHANNELS
            val end = minOf(input.size, offset + sampleCount)
            chunks += chunkedPipeline.process(
                input.copyOfRange(offset, end),
                SAMPLE_RATE,
                CHANNELS
            )
            offset = end
            index++
        }
        val chunked = FloatArray(input.size)
        var destination = 0
        chunks.forEach { chunk ->
            chunk.copyInto(chunked, destination)
            destination += chunk.size
        }

        assertArrayEquals(continuous, chunked, 0f)
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val CHANNELS = 2
    }
}
