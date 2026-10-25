package com.ideacode.soundly_sdk.core.audio.dsp.node

import com.ideacode.soundly_sdk.core.audio.dsp.model.CompressorPreset
import kotlin.math.abs
import kotlin.math.exp

/** Stateful envelope compressor whose gain remains continuous across PCM chunks. */
internal class CompressorNode : DspNodeInterface<CompressorPreset> {
    private var preset: CompressorPreset? = null
    private var envelopes = FloatArray(0)
    private var gains = FloatArray(0)

    override fun configure(preset: CompressorPreset?) {
        this.preset = preset
        envelopes = FloatArray(0)
        gains = FloatArray(0)
    }

    override fun process(input: FloatArray, sampleRate: Int, channels: Int): FloatArray {
        val configured = preset ?: return input
        require(sampleRate > 0 && channels > 0) { "Invalid PCM format" }
        ensureChannelState(channels)
        val threshold = (configured.threshold ?: DEFAULT_THRESHOLD).coerceIn(0.01f, 1f)
        val ratio = (configured.ratio ?: DEFAULT_RATIO).coerceAtLeast(1f)
        val attack = coefficient(ATTACK_SECONDS, sampleRate)
        val release = coefficient(RELEASE_SECONDS, sampleRate)
        val output = FloatArray(input.size)

        for (index in input.indices) {
            val channel = index % channels
            val level = abs(input[index])
            val envelopeCoefficient = if (level > envelopes[channel]) attack else release
            envelopes[channel] = envelopeCoefficient * envelopes[channel] +
                (1f - envelopeCoefficient) * level
            val targetGain = if (envelopes[channel] > threshold) {
                val compressed = threshold + (envelopes[channel] - threshold) / ratio
                compressed / envelopes[channel].coerceAtLeast(MIN_LEVEL)
            } else {
                1f
            }
            val gainCoefficient = if (targetGain < gains[channel]) attack else release
            gains[channel] = gainCoefficient * gains[channel] +
                (1f - gainCoefficient) * targetGain
            output[index] = (input[index] * gains[channel]).coerceIn(-1f, 1f)
        }
        return output
    }

    private fun ensureChannelState(channels: Int) {
        if (envelopes.size == channels) return
        envelopes = FloatArray(channels)
        gains = FloatArray(channels) { 1f }
    }

    private fun coefficient(seconds: Float, sampleRate: Int): Float =
        exp(-1.0 / (seconds * sampleRate)).toFloat()

    private companion object {
        const val DEFAULT_THRESHOLD = 0.7f
        const val DEFAULT_RATIO = 1.8f
        const val ATTACK_SECONDS = 0.005f
        const val RELEASE_SECONDS = 0.100f
        const val MIN_LEVEL = 1e-6f
    }
}
