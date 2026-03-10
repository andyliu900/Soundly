package com.ideacode.soundly_core.audio.analyze

import kotlin.math.sqrt

class DefaultAudioAnalyzer: AudioAnalyzer {

    override fun analyze(
        pcm: FloatArray,
        sampleRate: Int
    ): AudioFeatures {

        // ========= 基础合法性校验 =========
        if (pcm.isEmpty() || sampleRate <= 0) {
            return invalid()
        }

        // -------- 1. RMS（整体响度）--------
        var energySum = 0f
        for (v in pcm) {
            energySum += v * v
        }
        val rms = sqrt(energySum / pcm.size)

        // -------- 2. Zero Crossing Rate（嘈杂 / 高频程度）--------
        var zeroCrossings = 0
        for (i in 1 until pcm.size) {
            if (pcm[i - 1] * pcm[i] < 0) {
                zeroCrossings++
            }
        }
        val zeroCrossingRate =
            zeroCrossings.toFloat() / pcm.size.toFloat()

        // -------- 3. Spectral Flatness（工程近似版）--------
        // 用“瞬时变化能量 / 总能量”来近似
        var diffEnergy = 0f
        for (i in 1 until pcm.size) {
            val diff = pcm[i] - pcm[i - 1]
            diffEnergy += diff * diff
        }
        val spectralFlatness =
            (diffEnergy / (energySum + 1e-9f))
                .coerceIn(0f, 1f)

        // -------- 4. Speech Confidence（人声可信度）--------
        val speechConfidence = estimateSpeechConfidence(
            rms = rms,
            zcr = zeroCrossingRate,
            flatness = spectralFlatness
        )

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

    /**
     * 人声可信度估计（0~1）
     *
     * 原则：
     * - 人声 ≈ 中等响度 + 中等 ZCR + 非平坦频谱
     * - 噪声 ≈ 高 ZCR + 高 flatness
     */
    private fun estimateSpeechConfidence(
        rms: Float,
        zcr: Float,
        flatness: Float
    ): Float {

        // 响度评分（过小 or 过大都不像干净人声）
        val rmsScore = when {
            rms < 0.01f -> 0.2f
            rms < 0.05f -> 0.6f
            rms < 0.15f -> 1.0f
            rms < 0.3f -> 0.7f
            else -> 0.4f
        }

        // ZCR 评分（过高通常是噪声）
        val zcrScore = when {
            zcr < 0.02f -> 0.4f
            zcr < 0.08f -> 1.0f
            zcr < 0.15f -> 0.7f
            else -> 0.3f
        }

        // Flatness 评分（越平越像噪声）
        val flatnessScore = when {
            flatness < 0.2f -> 1.0f
            flatness < 0.4f -> 0.7f
            flatness < 0.6f -> 0.4f
            else -> 0.2f
        }

        // 加权平均（经验权重）
        val confidence =
            rmsScore * 0.4f +
                    zcrScore * 0.3f +
                    flatnessScore * 0.3f

        return confidence.coerceIn(0f, 1f)
    }

    private fun invalid(): AudioFeatures {
        return AudioFeatures(
            rms = 0f,
            spectralFlatness = 0f,
            zeroCrossingRate = 0f,
            speechConfidence = 0f,
            isValid = false
        )
    }

}