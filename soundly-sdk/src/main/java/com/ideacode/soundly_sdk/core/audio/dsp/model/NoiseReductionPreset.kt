package com.ideacode.soundly_sdk.core.audio.dsp.model

data class NoiseReductionPreset(
    val strength: Float? = null,    // 降噪强度
    val noiseFloor: Float? = null   // 噪声阈值
)
