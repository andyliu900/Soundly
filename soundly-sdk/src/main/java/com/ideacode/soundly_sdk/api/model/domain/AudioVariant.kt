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
    val durationMs: Long,

    /** Stable version identifier. Empty only for records created by schema v1. */
    val id: String = "",

    /** Owning project identifier. Empty only for records created by schema v1. */
    val projectId: String = "",

    val format: AudioFormat = AudioFormat.UNKNOWN,
    val mimeType: String? = null,
    val fileSizeBytes: Long = 0L,
    val createdAt: Long = 0L,
    val variantType: VariantType = if (isOriginal) {
        VariantType.ORIGINAL
    } else {
        VariantType.ENHANCED
    },

    /** Variant used to derive this version. Null for an original or legacy version. */
    val sourceVariantId: String? = null,

    /** Requested source-timeline range for a trimmed version. */
    val trimStartMs: Long? = null,
    val trimEndMs: Long? = null

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
