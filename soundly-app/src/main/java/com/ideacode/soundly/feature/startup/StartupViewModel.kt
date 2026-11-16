package com.ideacode.soundly.feature.startup

import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ideacode.soundly.app.AppContainer
import com.ideacode.soundly.common.error.AppError
import com.ideacode.soundly.common.error.AppResult
import com.ideacode.soundly.common.state.SoundlyViewModelFactory
import com.ideacode.soundly.common.state.StateActionViewModel
import com.ideacode.soundly.common.state.UiAction
import com.ideacode.soundly.common.state.UiEffect
import com.ideacode.soundly.common.state.UiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal sealed interface StartupUiState : UiState {
    data object Loading : StartupUiState
    data class Error(val error: AppError) : StartupUiState
    data object Ready : StartupUiState
}
internal sealed interface StartupAction : UiAction {
    data object Retry : StartupAction
}
internal sealed interface StartupEffect : UiEffect {
    data class Navigate(val destination: StartupDestination) : StartupEffect
}

internal class StartupViewModel(
    private val coordinator: StartupCoordinator,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val minimumDisplayMs: Long = MINIMUM_DISPLAY_MS
) :
    StateActionViewModel<StartupUiState, StartupAction, StartupEffect>(StartupUiState.Loading) {
    private val startedAt = clock()

    init { resolve(retry = false) }

    override fun dispatch(action: StartupAction) {
        when (action) {
            StartupAction.Retry -> if (uiState.value is StartupUiState.Error) resolve(retry = true)
        }
    }

    private fun resolve(retry: Boolean) {
        updateState { StartupUiState.Loading }
        viewModelScope.launch {
            when (val result = coordinator.resolve(retry)) {
                is StartupResult.Error -> updateState { StartupUiState.Error(result.error) }
                is StartupResult.Ready -> {
                    val remaining = minimumDisplayMs - (clock() - startedAt)
                    if (remaining > 0) delay(remaining)
                    updateState { StartupUiState.Ready }
                    postEffect(StartupEffect.Navigate(result.destination))
                }
            }
        }
    }

    companion object {
        private const val MINIMUM_DISPLAY_MS = 1200L

        fun factory(container: AppContainer): ViewModelProvider.Factory =
            SoundlyViewModelFactory(StartupViewModel::class.java) {
                val coordinator = StartupCoordinator(
                    sdkReady = {
                        when (val result = container.soundlySdkProvider.get()) {
                            is AppResult.Success -> AppResult.Success(Unit)
                            is AppResult.Failure -> result
                        }
                    },
                    initializeSdk = { container.soundlySdkProvider.initialize() },
                    recordingState = { container.recordingGateway.state.value },
                    recoverableSessionIds = {
                        when (val result = container.recordingGateway.recoverable()) {
                            is AppResult.Success -> AppResult.Success(result.value.map { it.sessionId })
                            is AppResult.Failure -> result
                        }
                    },
                    hasCompletedOnboarding = { container.appPreferences.hasCompletedOnboarding },
                    lastSaved = { container.recordingGateway.lastSaved.value }
                )
                StartupViewModel(coordinator)
            }
    }
}
