package com.ideacode.soundly_sdk.core.audio.analyze

import kotlin.math.sqrt

/** Constant-memory feature aggregation for interleaved PCM chunks. */
internal class AudioFeatureAccumulator {
    private var sampleCount = 0L
    private var energySum = 0.0
    private var differenceEnergySum = 0.0
    private var zeroCrossings = 0L
    private var previousMonoSample: Float? = null

    fun addInterleaved(pcm: FloatArray, channels: Int) {
        require(channels > 0) { "Channel count must be positive" }
        var offset = 0
        while (offset + channels <= pcm.size) {
            var mixed = 0.0
            for (channel in 0 until channels) mixed += pcm[offset + channel]
            addMonoSample((mixed / channels).toFloat())
            offset += channels
        }
    }

    fun addMono(pcm: FloatArray) {
        pcm.forEach(::addMonoSample)
    }

    fun finish(): AudioFeatures {
        if (sampleCount == 0L) return invalidFeatures()
        val rms = sqrt(energySum / sampleCount).toFloat()
        val zeroCrossingRate = zeroCrossings.toFloat() / sampleCount.toFloat()
        val spectralFlatness = (differenceEnergySum / (energySum + EPSILON))
            .toFloat()
            .coerceIn(0f, 1f)
        val speechConfidence = estimateSpeechConfidence(rms, zeroCrossingRate, spectralFlatness)
        val valid = rms.isFinite() &&
            spectralFlatness.isFinite() &&
            zeroCrossingRate.isFinite() &&
            speechConfidence.isFinite()
        return AudioFeatures(
            rms = rms,
            spectralFlatness = spectralFlatness,
            zeroCrossingRate = zeroCrossingRate,
            speechConfidence = speechConfidence,
            isValid = valid
        )
    }

    private fun addMonoSample(sample: Float) {
        val value = sample.coerceIn(-1f, 1f)
        energySum += value.toDouble() * value
        previousMonoSample?.let { previous ->
            if (previous * value < 0f) zeroCrossings++
            val difference = value - previous
            differenceEnergySum += difference.toDouble() * difference
        }
        previousMonoSample = value
        sampleCount++
    }

    private fun estimateSpeechConfidence(rms: Float, zcr: Float, flatness: Float): Float {
        val rmsScore = when {
            rms < 0.01f -> 0.2f
            rms < 0.05f -> 0.6f
            rms < 0.15f -> 1.0f
            rms < 0.3f -> 0.7f
            else -> 0.4f
        }
        val zcrScore = when {
            zcr < 0.02f -> 0.4f
            zcr < 0.08f -> 1.0f
            zcr < 0.15f -> 0.7f
            else -> 0.3f
        }
        val flatnessScore = when {
            flatness < 0.2f -> 1.0f
            flatness < 0.4f -> 0.7f
            flatness < 0.6f -> 0.4f
            else -> 0.2f
        }
        return (rmsScore * 0.4f + zcrScore * 0.3f + flatnessScore * 0.3f)
            .coerceIn(0f, 1f)
    }

    private fun invalidFeatures() = AudioFeatures(
        rms = 0f,
        spectralFlatness = 0f,
        zeroCrossingRate = 0f,
        speechConfidence = 0f,
        isValid = false
    )

    private companion object {
        const val EPSILON = 1e-9
    }
}
