package com.ideacode.soundly_core.audio.dsp.model

/**
 * NoiseReduction.strength     0.05 ~ 0.30    过高会导致水声 / 能量掏空
 * NoiseReduction.noiseFloor  0.008 ~ 0.025   低于下限关键频噪被带进人声
 *
 * Eq.lowGain                 0.90 ~ 1.05     低频过高→浑厚 过低→薄
 * Eq.midGain                 1.00 ~ 1.15     核心人声频段，过高刺耳
 * Eq.highGain                0.95 ~ 1.10     高频太高→刺耳；太低→沉闷
 *
 * Compressor.thresholdDb     0.60 ~ 0.75     阈值越小越容易压顶
 * Compressor.ratio           1.0 ~ 1.4       太大压缩推进→炸音
 *
 * Limiter.limit              0.985 ~ 1.000   过低 → 人声闷；过高 → 不保护
 */
data class DspPreset(
    val noiseReductionPreset: NoiseReductionPreset? = null,
    val eqPreset: EqPreset? = null,
    val compressorPreset: CompressorPreset? = null,
    val limiterPreset: LimiterPreset? = null
) {

    companion object {

        /**
         * 原始音频（完全不处理）
         */
        fun raw(): DspPreset = DspPreset()

        /**
         * 默认增强（产品默认）
         */
        fun default(): DspPreset = DspPreset(
            noiseReductionPreset = NoiseReductionPreset(
                strength = 0.15f,
                noiseFloor = 0.012f
            ),
            eqPreset = EqPreset(
                lowGain = 0.98f,
                midGain = 1.08f,
                highGain = 1.00f
            ),
            compressorPreset = CompressorPreset(
                threshold = 0.70f,
                ratio = 1.0f
            ),
            limiterPreset = LimiterPreset(
                limit = 0.99f
            )
        )

        /**
         * AI 智能增强
         */
        fun ai(): DspPreset = default()


        /***********  常规优化策略  **********/

        /**
         * 人声稳态增强
         */
        fun voiceStable(): DspPreset = DspPreset(
            noiseReductionPreset = NoiseReductionPreset(
                strength = 0.22f
            ),
            eqPreset = EqPreset(
                lowGain = 0.98f,
                midGain = 1.18f,
                highGain = 1.03f
            ),
            compressorPreset = CompressorPreset(
                threshold = 0.68f
            ),
            limiterPreset = LimiterPreset()
        )

        /**
         * 清晰优先
         */
        fun speechFocus(): DspPreset = DspPreset(
            noiseReductionPreset = NoiseReductionPreset(
                strength = 0.30f
            ),
            eqPreset = EqPreset(
                lowGain = 0.94f,
                midGain = 1.20f,
                highGain = 1.00f
            ),
            compressorPreset = CompressorPreset(
                threshold = 0.72f
            ),
            limiterPreset = LimiterPreset()
        )

        /**
         * 自然顺滑
         */
        fun naturalSmooth(): DspPreset = DspPreset(
            noiseReductionPreset = NoiseReductionPreset(
                strength = 0.18f
            ),
            eqPreset = EqPreset(
                lowGain = 1.0f,
                midGain = 1.12f,
                highGain = 1.06f
            ),
            compressorPreset = CompressorPreset(
                threshold = 0.65f
            ),
            limiterPreset = LimiterPreset()
        )


        /***********  特定场景优化策略  **********/

        /**
         * 播客/访谈场景
         */
        fun podcastInterview(): DspPreset = DspPreset(
            noiseReductionPreset = NoiseReductionPreset(
                strength = 0.18f
            ),
            eqPreset = EqPreset(
                lowGain = 0.98f,
                midGain = 1.12f,
                highGain = 1.02f
            ),
            compressorPreset = CompressorPreset(
                threshold = 0.65f
            ),
            limiterPreset = LimiterPreset(
                limit = 0.99f
            )
        )

        /**
         * 会议/电话场景
         */
        fun meetingCall(): DspPreset = DspPreset(
            noiseReductionPreset = NoiseReductionPreset(
                strength = 0.28f
            ),
            eqPreset = EqPreset(
                lowGain = 0.94f,
                midGain = 1.15f,
                highGain = 1.00f
            ),
            compressorPreset = CompressorPreset(
                threshold = 0.70f
            ),
            limiterPreset = LimiterPreset(
                limit = 0.985f
            )
        )

        /**
         * 音乐/嘈杂场景
         */
        fun musicNoise(): DspPreset = DspPreset(
            noiseReductionPreset = NoiseReductionPreset(
                strength = 0.22f
            ),
            eqPreset = EqPreset(
                lowGain = 0.96f,
                midGain = 1.08f,
                highGain = 1.05f
            ),
            compressorPreset = CompressorPreset(
                threshold = 0.72f
            ),
            limiterPreset = LimiterPreset(
                limit = 0.995f
            )
        )

    }

}