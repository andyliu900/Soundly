package com.ideacode.soundly_sdk.history.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.ideacode.soundly_sdk.api.model.audio.dsp.PresetOption
import com.ideacode.soundly_sdk.api.model.domain.AudioHistory
import com.ideacode.soundly_sdk.api.model.domain.AudioVariant

@Entity(tableName = "audio_history")
data class AudioHistoryEntity (

    @PrimaryKey
    val id: String,

    /**
     * 原始音频路径
     */
    val originalPath: String,

    val sampleRate: Int,
    val channels: Int,
    val durationMs: Long,

    val createAt: Long
)

fun AudioHistoryEntity.toDomain(
    variants: List<AudioVariantEntity>
): AudioHistory =
    AudioHistory(
        id = id,
        original = AudioVariant(
            filePath = originalPath,
            presetOption = PresetOption.RAW,
            isOriginal = true,
            sampleRate = sampleRate,
            channels = channels,
            durationMs = durationMs
        ),
        variants = variants.map { it.toDomain() },
        createAt = createAt
    )
