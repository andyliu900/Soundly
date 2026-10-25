package com.ideacode.soundly_sdk.project.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "audio_marker",
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
internal data class AudioMarkerEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val positionMs: Long,
    val label: String?,
    val createdAt: Long
)
