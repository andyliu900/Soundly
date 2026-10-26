package com.ideacode.soundly

import com.ideacode.soundly.common.error.AppError
import com.ideacode.soundly.common.error.AppResult
import com.ideacode.soundly.data.sdk.SoundlySdkProvider
import com.ideacode.soundly.feature.foundation.FoundationViewModel
import com.ideacode.soundly_sdk.api.SoundlySdk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FoundationViewModelTest {
    @Test
    fun `sdk failure is exposed as retryable presentation state`() {
        val viewModel = FoundationViewModel(FailingSdkProvider)

        val state = viewModel.uiState.value

        assertFalse(state.isLoading)
        assertFalse(state.isReady)
        assertEquals("sdk_initialization_failed", state.error?.code)
        assertTrue(state.error?.retryable == true)
    }

    private object FailingSdkProvider : SoundlySdkProvider {
        override fun initialize(): AppResult<Unit> = AppResult.Failure(
            AppError(code = "sdk_initialization_failed", retryable = true)
        )

        override fun get(): AppResult<SoundlySdk> = AppResult.Failure(
            AppError(code = "sdk_initialization_failed", retryable = true)
        )
    }
}
