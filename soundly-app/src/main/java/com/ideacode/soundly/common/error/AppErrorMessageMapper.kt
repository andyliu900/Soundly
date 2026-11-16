package com.ideacode.soundly.common.error

import androidx.annotation.StringRes
import com.ideacode.soundly.R

/** Maps stable error codes to host-owned, localized copy. */
class AppErrorMessageMapper {
    @StringRes
    fun messageRes(error: AppError): Int = when (error.code) {
        "sdk_not_initialized" -> R.string.error_sdk_not_initialized
        "sdk_initialization_failed" -> R.string.error_sdk_initialization_failed
        "permission_denied" -> R.string.error_permission_denied
        "microphone_unavailable" -> R.string.error_microphone_unavailable
        "insufficient_storage" -> R.string.error_insufficient_storage
        "invalid_request", "file_not_found", "project_not_found" -> R.string.error_content_unavailable
        "unsupported_format" -> R.string.error_unsupported_format
        "invalid_audio_file", "decode_failed" -> R.string.error_invalid_audio
        "playback_failed" -> R.string.error_playback_failed
        "recording_failed", "recording_interrupted" -> R.string.error_recording_failed
        "processing_failed" -> R.string.error_processing_failed
        "destination_conflict" -> R.string.error_destination_conflict
        "export_failed", "file_operation_failed" -> R.string.error_export_failed
        "share_failed" -> R.string.error_share_failed
        "share_unavailable" -> R.string.h7_share_failed
        else -> R.string.error_operation_failed
    }
}
