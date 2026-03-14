package com.ideacode.soundly_sdk.core.audio.dsp.model

data class CompressorPreset(

    /**
     * 启动压缩的能量阈值
     */
    val threshold: Float? = null,

    /**
     * 压缩比（建议 <= 2.0）
     * 1.3 = 非常保守
     * 2.0 = 播客级别
     */
    val ratio: Float? = null
)
