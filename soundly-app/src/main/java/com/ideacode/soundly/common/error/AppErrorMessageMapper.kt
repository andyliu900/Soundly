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
        else -> R.string.error_operation_failed
    }
}
