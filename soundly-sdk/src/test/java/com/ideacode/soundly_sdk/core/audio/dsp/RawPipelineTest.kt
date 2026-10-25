package com.ideacode.soundly_sdk.core.audio.dsp

import com.ideacode.soundly_sdk.core.audio.dsp.model.DspPreset
import com.ideacode.soundly_sdk.core.audio.dsp.model.LimiterPreset
import com.ideacode.soundly_sdk.core.audio.dsp.node.LimiterNode
import com.ideacode.soundly_sdk.core.audio.dsp.pipeline.DefaultDspPipeline
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertSame
import org.junit.Test

class RawPipelineTest {

    @Test
    fun limiterWithoutPresetDoesNotAlterPcm() {
        val input = floatArrayOf(-1.0f, -0.96f, 0.0f, 0.96f, 1.0f)
        val limiter = LimiterNode()

        val output = limiter.process(input, SAMPLE_RATE)

        assertSame(input, output)
        assertArrayEquals(input, output, 0.0f)
    }

    @Test
    fun configuredLimiterClampsPcm() {
        val limiter = LimiterNode().apply {
            configure(LimiterPreset(limit = 0.9f))
        }

        val output = limiter.process(
            floatArrayOf(-1.0f, -0.5f, 0.5f, 1.0f),
            SAMPLE_RATE
        )

        assertArrayEquals(
            floatArrayOf(-0.9f, -0.5f, 0.5f, 0.9f),
            output,
            0.0f
        )
    }

    @Test
    fun rawPresetResetsPreviousConfigurationAndPreservesPcm() {
        val pipeline = DefaultDspPipeline()
        val input = floatArrayOf(-1.0f, -0.5f, 0.0f, 0.5f, 1.0f)

        pipeline.configure(DspPreset.default())
        pipeline.process(input, SAMPLE_RATE)
        pipeline.configure(DspPreset.raw())

        val rawOutput = pipeline.process(input, SAMPLE_RATE)

        assertSame(input, rawOutput)
        assertArrayEquals(input, rawOutput, 0.0f)
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
    }
}

