package com.ideacode.soundly_sdk.api.model.domain

import com.ideacode.soundly_sdk.api.model.audio.dsp.PresetOption


data class AudioVariant(

    /**
     * 文件路径
     */
    val filePath: String,

    /**
     * 使用的策略
     */
    val presetOption: PresetOption,

    /**
     * 是否原始音频
     */
    val isOriginal: Boolean,

    val sampleRate: Int,
    val channels: Int,
    val durationMs: Long

)

//fun AudioVariant.toEntity(historyId: String) : AudioVariantEntity {
//    return AudioVariantEntity(
//        historyId = historyId,
//        filePath = filePath,
//        presetOption = presetOption,
//        sampleRate = sampleRate,
//        channels = channels,
//        durationMs = durationMs
//    )
//}
