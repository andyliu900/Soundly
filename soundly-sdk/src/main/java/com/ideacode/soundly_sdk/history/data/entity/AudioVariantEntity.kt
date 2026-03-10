package com.ideacode.soundly_sdk.history.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ideacode.soundly_model.audio.dsp.PresetOption
import com.ideacode.soundly_model.domain.AudioVariant

@Entity(
    tableName = "audio_variant",
    foreignKeys = [
        ForeignKey(
            entity = AudioHistoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["historyId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("historyId")]
)
data class AudioVariantEntity(

    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val historyId: String,

    val filePath: String,

    val presetOption: PresetOption,

    val sampleRate: Int,
    val channels: Int,
    val durationMs: Long

)

fun AudioVariantEntity.toDomain():
        AudioVariant = AudioVariant(
    filePath = filePath,
    presetOption = presetOption,
    isOriginal = false,
    sampleRate = sampleRate,
    channels = channels,
    durationMs = durationMs
)
