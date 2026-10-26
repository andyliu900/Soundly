package com.ideacode.soundly.feature.foundation

import com.ideacode.soundly.common.error.AppError

data class FoundationUiState(
    val isLoading: Boolean = true,
    val isReady: Boolean = false,
    val error: AppError? = null
)
