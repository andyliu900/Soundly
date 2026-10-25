package com.ideacode.soundly_sdk.api.model.export

import android.content.Intent
import com.ideacode.soundly_sdk.api.model.domain.AudioFormat

/**
 * Exports an existing project version to public or user-selected storage.
 *
 * Matching source/target formats are copied without re-encoding. Compressed M4A, AAC and MP3
 * sources can be decoded to PCM16 WAV. Other format conversions are rejected explicitly rather
 * than writing source bytes under a misleading extension.
 */
data class ExportRequest(
    val projectId: String,
    val variantId: String,
    val displayName: String,
    val format: AudioFormat,
    val destination: ExportDestination,
    val conflictStrategy: FileConflictStrategy = FileConflictStrategy.RENAME,
    /** Encoding policy. [ExportQuality.AUTOMATIC] selects a truthful supported strategy. */
    val quality: ExportQuality = ExportQuality.AUTOMATIC
)

enum class ExportQuality {
    /** Copy source bytes when formats match; otherwise use PCM16 only for a WAV target. */
    AUTOMATIC,

    /** Require the target format to match the source and copy bytes without re-encoding. */
    PRESERVE_SOURCE,

    /** Produce a 16-bit PCM WAV while preserving the decoded sample rate and channel count. */
    PCM_16_BIT
}

enum class ExportStage {
    PREPARING,
    ENCODING,
    WRITING,
    COMPLETED
}

data class ExportProgress(
    val stage: ExportStage,
    val percent: Int,
    val bytesWritten: Long = 0L,
    val totalBytes: Long = 0L
)

sealed interface ExportDestination {
    /** Saves under the public Music/Soundly directory. */
    data object Music : ExportDestination

    /** Saves under the public Download/Soundly directory. */
    data object Downloads : ExportDestination

    /** URI returned by an ACTION_CREATE_DOCUMENT flow. */
    data class DocumentUri(val uri: String) : ExportDestination
}

enum class FileConflictStrategy {
    /** Selects an available numbered name for MediaStore and legacy-directory exports. */
    RENAME,

    /** Replaces an existing MediaStore or legacy-directory item after a successful copy. */
    REPLACE,

    /** Returns DestinationConflict instead of writing over a non-empty target. */
    FAIL
}

data class ExportResult(
    val destinationUri: String,
    val displayName: String,
    val format: AudioFormat,
    val fileSizeBytes: Long,
    /** Actual strategy used; never [ExportQuality.AUTOMATIC]. */
    val quality: ExportQuality
)

/**
 * Prepares a project version for Android's system share sheet.
 *
 * Sharing is always lossless: the selected version is copied without transcoding.
 * When [displayName] is null, the project title is used.
 */
data class ShareRequest(
    val projectId: String,
    val variantId: String,
    val displayName: String? = null
)

/**
 * A read-only content URI and SEND intent ready for the host app to launch.
 *
 * The host should wrap [sendIntent] with [Intent.createChooser]. The URI grant is
 * scoped to receiving apps and remains valid while the cached file exists.
 */
data class ShareResult(
    val sendIntent: Intent,
    val contentUri: String,
    val displayName: String,
    val mimeType: String,
    val format: AudioFormat,
    val fileSizeBytes: Long
)
