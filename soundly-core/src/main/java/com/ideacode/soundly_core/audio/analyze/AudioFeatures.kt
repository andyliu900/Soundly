package com.ideacode.soundly_core.audio.analyze

data class AudioFeatures(
    val rms: Float, // 整体响度
    val spectralFlatness: Float, // 0.0 ~ 0.3 有结构（人声），0.5 ~ 1.0 （白噪声/风声）
    val zeroCrossingRate: Float, // 嘈杂程度
    val speechConfidence: Float, // 0 ~ 1 人声可信度
    val isValid: Boolean, // 是否可用
) {
    override fun toString(): String {
        return "AudioFeatures(rms=$rms, spectralFlatness=$spectralFlatness, zeroCrossingRate=$zeroCrossingRate, speechConfidence=$speechConfidence, isValid=$isValid)"
    }
}