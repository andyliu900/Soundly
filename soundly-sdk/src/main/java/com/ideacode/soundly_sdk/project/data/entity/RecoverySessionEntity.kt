package com.ideacode.soundly_sdk.project.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "recovery_session",
    indices = [Index(value = ["projectId"], unique = true)]
)
internal data class RecoverySessionEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val tempFilePath: String,
    val recordedDurationMs: Long,
    val recordedBytes: Long,
    val status: String,
    val updatedAt: Long
)
