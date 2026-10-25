package com.ideacode.soundly_sdk.project.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "project_audio_variant",
    foreignKeys = [
        ForeignKey(
            entity = AudioProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("projectId")]
)
internal data class ProjectAudioVariantEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val variantType: String,
    val presetOption: String,
    val filePath: String,
    val format: String,
    val mimeType: String?,
    val sampleRate: Int,
    val channels: Int,
    val durationMs: Long,
    val fileSizeBytes: Long,
    val createdAt: Long,
    val sourceVariantId: String?,
    val trimStartMs: Long?,
    val trimEndMs: Long?
)
