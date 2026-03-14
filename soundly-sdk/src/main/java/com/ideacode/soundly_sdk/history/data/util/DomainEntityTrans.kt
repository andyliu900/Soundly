package com.ideacode.soundly_sdk.history.data.util

import com.ideacode.soundly_sdk.api.model.domain.AudioHistory
import com.ideacode.soundly_sdk.api.model.domain.AudioVariant
import com.ideacode.soundly_sdk.history.data.entity.AudioHistoryEntity
import com.ideacode.soundly_sdk.history.data.entity.AudioVariantEntity

/**
 * Copyright (C), 2021-2026
 * @ProjectName:    Soundly
 * @Package:        com.ideacode.soundly_sdk.history.data.util
 * @ClassName:      DomainEntityTrans
 * @Description:
 * @Author:         randysu
 * @CreateDate:     2026/3/2 00:13
 * @UpdateUser:
 * @UpdateDate:     2026/3/2 00:13
 * @UpdateRemark:
 * @Version:        1.0
 */
object DomainEntityTrans {

    fun historyDomainToEntity(history: AudioHistory): AudioHistoryEntity {
        val originalVariant = history.original

        return AudioHistoryEntity(
            id = history.id,
            originalPath = originalVariant.filePath,

            sampleRate = originalVariant.sampleRate,
            channels = originalVariant.channels,
            durationMs = originalVariant.durationMs,

            createAt = history.createAt
        )
    }

    fun variantDomainToEntity(historyId: String, variant: AudioVariant): AudioVariantEntity {
        return AudioVariantEntity(
            historyId = historyId,
            filePath = variant.filePath,
            presetOption = variant.presetOption,
            sampleRate = variant.sampleRate,
            channels = variant.channels,
            durationMs = variant.durationMs
        )
    }

}