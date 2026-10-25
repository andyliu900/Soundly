package com.ideacode.soundly_sdk.project.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "audio_project")
internal data class AudioProjectEntity(
    @PrimaryKey val id: String,
    val title: String,
    val sourceType: String,
    val recordingScene: String?,
    val status: String,
    val originalVariantId: String,
    val createdAt: Long,
    val updatedAt: Long
)
