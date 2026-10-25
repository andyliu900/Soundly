package com.ideacode.soundly_sdk.api.model.importer

import android.net.Uri
import com.ideacode.soundly_sdk.api.model.domain.AudioFormat
import com.ideacode.soundly_sdk.api.model.domain.AudioProject

data class AudioImportRequest(
    val source: Uri,
    val title: String? = null
)

data class AudioMetadata(
    val displayName: String,
    val format: AudioFormat,
    val mimeType: String,
    val durationMs: Long,
    val fileSizeBytes: Long,
    val sampleRate: Int,
    val channels: Int,
    val bitRate: Int? = null
)

data class AudioImportResult(
    val project: AudioProject,
    val copiedBytes: Long
)
