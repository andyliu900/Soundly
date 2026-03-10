package com.ideacode.soundly_core.audio.dsp.model

data class EqPreset(

    /**
     * 低频增益（<200Hz）
     * 通常 < 1，避免混浊
     */
    val lowGain: Float? = null,

    /**
     * 中频增益（200~4k）
     * 人声清晰度核心
     */
    val midGain: Float? = null,

    /**
     * 高频增益（>4k）
     * 只允许极轻微提升
     */
    val highGain: Float? = null
)
