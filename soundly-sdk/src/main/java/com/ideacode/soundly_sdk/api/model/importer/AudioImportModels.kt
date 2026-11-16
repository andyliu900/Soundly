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
    val bitRate: Int? = null,
    /** Provider display name only, without URI/path-derived fallback. */
    val sourceDisplayName: String? = null
)

data class AudioImportResult(
    val project: AudioProject,
    val copiedBytes: Long
)

/** A checked state for an explicitly identified import operation. */
sealed interface ImportOperationStatus {
    data object NotStarted : ImportOperationStatus
    data object Interrupted : ImportOperationStatus
    data class Completed(val result: AudioImportResult) : ImportOperationStatus
}
