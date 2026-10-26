package com.ideacode.soundly.feature.foundation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.ideacode.soundly.common.error.AppResult
import com.ideacode.soundly.data.sdk.SoundlySdkProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FoundationViewModel(
    private val soundlySdkProvider: SoundlySdkProvider
) : ViewModel() {
    private val _uiState = MutableStateFlow(FoundationUiState())
    val uiState: StateFlow<FoundationUiState> = _uiState.asStateFlow()

    init {
        verifySdk()
    }

    fun retry() {
        _uiState.value = FoundationUiState()
        soundlySdkProvider.initialize()
        verifySdk()
    }

    private fun verifySdk() {
        _uiState.value = when (val result = soundlySdkProvider.get()) {
            is AppResult.Success -> FoundationUiState(isLoading = false, isReady = true)
            is AppResult.Failure -> FoundationUiState(isLoading = false, error = result.error)
        }
    }

    companion object {
        fun factory(provider: SoundlySdkProvider): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    check(modelClass.isAssignableFrom(FoundationViewModel::class.java))
                    return FoundationViewModel(provider) as T
                }
            }
    }
}
