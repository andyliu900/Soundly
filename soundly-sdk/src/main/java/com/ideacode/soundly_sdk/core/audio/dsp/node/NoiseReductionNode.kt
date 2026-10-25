package com.ideacode.soundly_sdk.core.audio.dsp.node

import com.ideacode.soundly_sdk.core.audio.dsp.model.NoiseReductionPreset
import kotlin.math.abs
import kotlin.math.exp

/** Smooth noise-gate attenuation with per-channel state retained across chunks. */
internal class NoiseReductionNode : DspNodeInterface<NoiseReductionPreset> {
    private var preset: NoiseReductionPreset? = null
    private var gains = FloatArray(0)

    override fun configure(preset: NoiseReductionPreset?) {
        this.preset = preset
        gains = FloatArray(0)
    }

    override fun process(input: FloatArray, sampleRate: Int, channels: Int): FloatArray {
        val configured = preset ?: return input
        require(sampleRate > 0 && channels > 0) { "Invalid PCM format" }
        ensureChannelState(channels)
        val strength = (configured.strength ?: DEFAULT_STRENGTH).coerceIn(0f, 1f)
        val noiseFloor = (configured.noiseFloor ?: DEFAULT_NOISE_FLOOR).coerceAtLeast(0f)
        val attenuation = 1f - strength
        val closeCoefficient = coefficient(CLOSE_SECONDS, sampleRate)
        val openCoefficient = coefficient(OPEN_SECONDS, sampleRate)
        val output = FloatArray(input.size)

        for (index in input.indices) {
            val channel = index % channels
            val target = if (abs(input[index]) < noiseFloor) attenuation else 1f
            val smoothing = if (target < gains[channel]) closeCoefficient else openCoefficient
            gains[channel] = smoothing * gains[channel] + (1f - smoothing) * target
            output[index] = input[index] * gains[channel]
        }
        return output
    }

    private fun ensureChannelState(channels: Int) {
        if (gains.size == channels) return
        gains = FloatArray(channels) { 1f }
    }

    private fun coefficient(seconds: Float, sampleRate: Int): Float =
        exp(-1.0 / (seconds * sampleRate)).toFloat()

    private companion object {
        const val DEFAULT_STRENGTH = 0.2f
        const val DEFAULT_NOISE_FLOOR = 0.015f
        const val CLOSE_SECONDS = 0.020f
        const val OPEN_SECONDS = 0.005f
    }
}
