package com.ideacode.soundly_sdk.api.error

import com.ideacode.soundly_sdk.api.model.recording.RecordingInterruptionReason

/**
 * Machine-readable SDK errors. These values deliberately contain no
 * user-facing copy so the host app can localize them.
 */
sealed class SoundlyError(
    open val code: String,
    open val retryable: Boolean,
    internal open val cause: Throwable? = null
) {
    /** Safe for logs/UI diagnostics: never includes the underlying exception text. */
    final override fun toString(): String =
        "SoundlyError(code=$code, retryable=$retryable)"

    data object NotInitialized : SoundlyError(
        code = "sdk_not_initialized",
        retryable = false
    )

    data class InitializationFailed(
        internal override val cause: Throwable
    ) : SoundlyError(
        code = "sdk_initialization_failed",
        retryable = true,
        cause = cause
    )

    data object PermissionDenied : SoundlyError(
        code = "permission_denied",
        retryable = true
    )

    data object MicrophoneUnavailable : SoundlyError(
        code = "microphone_unavailable",
        retryable = true
    )

    data class InvalidRequest(
        val field: String? = null,
        internal override val cause: Throwable? = null
    ) : SoundlyError(
        code = "invalid_request",
        retryable = false,
        cause = cause
    )

    data class FileNotFound(
        val path: String? = null,
        internal override val cause: Throwable? = null
    ) : SoundlyError(
        code = "file_not_found",
        retryable = false,
        cause = cause
    )

    data class UnsupportedFormat(
        val mimeType: String? = null,
        internal override val cause: Throwable? = null
    ) : SoundlyError(
        code = "unsupported_format",
        retryable = false,
        cause = cause
    )

    data class InvalidAudioFile(
        internal override val cause: Throwable? = null
    ) : SoundlyError(
        code = "invalid_audio_file",
        retryable = false,
        cause = cause
    )

    data object InsufficientStorage : SoundlyError(
        code = "insufficient_storage",
        retryable = true
    )

    data class DecodeFailed(
        internal override val cause: Throwable? = null
    ) : SoundlyError(
        code = "decode_failed",
        retryable = true,
        cause = cause
    )

    data object ProcessingCancelled : SoundlyError(
        code = "processing_cancelled",
        retryable = true
    )

    data class ProcessingFailed(
        internal override val cause: Throwable? = null
    ) : SoundlyError(
        code = "processing_failed",
        retryable = true,
        cause = cause
    )

    data class PlaybackFailed(
        internal override val cause: Throwable? = null
    ) : SoundlyError(
        code = "playback_failed",
        retryable = true,
        cause = cause
    )

    data class DatabaseFailed(
        internal override val cause: Throwable? = null
    ) : SoundlyError(
        code = "database_failed",
        retryable = true,
        cause = cause
    )

    data class SettingsPersistenceFailed(
        internal override val cause: Throwable? = null
    ) : SoundlyError(
        code = "settings_persistence_failed",
        retryable = true,
        cause = cause
    )

    data class ProjectNotFound(
        val projectId: String
    ) : SoundlyError(
        code = "project_not_found",
        retryable = false
    )

    data class VariantInUse(
        val projectId: String,
        val variantId: String
    ) : SoundlyError(
        code = "variant_in_use",
        retryable = false
    )

    data class FileOperationFailed(
        internal override val cause: Throwable? = null
    ) : SoundlyError(
        code = "file_operation_failed",
        retryable = true,
        cause = cause
    )

    data class ImportFailed(
        internal override val cause: Throwable? = null
    ) : SoundlyError(
        code = "import_failed",
        retryable = true,
        cause = cause
    )

    data class RecordingFailed(
        internal override val cause: Throwable? = null
    ) : SoundlyError(
        code = "recording_failed",
        retryable = true,
        cause = cause
    )

    data class RecordingInterrupted(
        val reason: RecordingInterruptionReason
    ) : SoundlyError(
        code = "recording_interrupted",
        retryable = true
    )

    data class InvalidState(
        val operation: String? = null
    ) : SoundlyError(
        code = "invalid_state",
        retryable = false
    )

    data class TrimFailed(
        internal override val cause: Throwable? = null
    ) : SoundlyError(
        code = "trim_failed",
        retryable = true,
        cause = cause
    )

    data class DestinationConflict(
        val displayName: String
    ) : SoundlyError(
        code = "destination_conflict",
        retryable = false
    )

    data class ExportFailed(
        internal override val cause: Throwable? = null
    ) : SoundlyError(
        code = "export_failed",
        retryable = true,
        cause = cause
    )

    data class ShareFailed(
        internal override val cause: Throwable? = null
    ) : SoundlyError(
        code = "share_failed",
        retryable = true,
        cause = cause
    )

    data class Unknown(
        internal override val cause: Throwable? = null
    ) : SoundlyError(
        code = "unknown",
        retryable = false,
        cause = cause
    )
}
