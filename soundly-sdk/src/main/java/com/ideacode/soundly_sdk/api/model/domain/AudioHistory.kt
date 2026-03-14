package com.ideacode.soundly_sdk.api.model.domain


data class AudioHistory(

    /**
     * 唯一ID，一次导入一条
     */
    val id: String,

    /**
     * 原始音频
     */
    val original: AudioVariant,

    /**
     * 不同策略生成的结果
     */
    val variants: List<AudioVariant>,

    /**
     * 创建时间
     */
    val createAt: Long

)

//fun AudioHistory.toEntity(): AudioHistoryEntity {
//    val originalVariant = original
//
//    return AudioHistoryEntity(
//        id = id,
//        originalPath = originalVariant.filePath,
//
//        sampleRate = originalVariant.sampleRate,
//        channels = originalVariant.channels,
//        durationMs = originalVariant.durationMs,
//
//        createAt = createAt
//    )
//}
