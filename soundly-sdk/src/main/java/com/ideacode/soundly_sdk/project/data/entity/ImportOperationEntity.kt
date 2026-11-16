package com.ideacode.soundly_sdk.project.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "import_operation", indices = [Index(value = ["projectId"], unique = true)])
internal data class ImportOperationEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val variantId: String,
    val phase: String
)
