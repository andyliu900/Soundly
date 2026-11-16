package com.ideacode.soundly.feature.startup

import com.ideacode.soundly.common.error.AppError
import com.ideacode.soundly.common.error.AppResult
import com.ideacode.soundly_sdk.api.model.recording.RecordingState
import com.ideacode.soundly_sdk.api.model.recording.RecordingResult

internal sealed interface StartupDestination {
    data object Onboarding : StartupDestination
    data object Home : StartupDestination
    data object Recording : StartupDestination
    data class Recovery(val sessionId: String) : StartupDestination
    data class Preview(val result: RecordingResult) : StartupDestination
}

internal sealed interface StartupResult {
    data class Ready(val destination: StartupDestination) : StartupResult
    data class Error(val error: AppError) : StartupResult
}

/** Decides the initial route without owning Android views or navigation. */
internal class StartupCoordinator(
    private val sdkReady: () -> AppResult<Unit>,
    private val initializeSdk: () -> AppResult<Unit>,
    private val recordingState: () -> RecordingState,
    private val recoverableSessionIds: suspend () -> AppResult<List<String>>,
    private val hasCompletedOnboarding: () -> Boolean,
    private val lastSaved: () -> RecordingResult? = { null }
) {
    suspend fun resolve(retry: Boolean = false): StartupResult {
        var sdk = sdkReady()
        if (sdk is AppResult.Failure && retry) {
            sdk = initializeSdk()
            if (sdk is AppResult.Success) sdk = sdkReady()
        }
        if (sdk is AppResult.Failure) return StartupResult.Error(sdk.error)

        // An active session takes precedence over older recoverable sessions.
        when (recordingState()) {
            is RecordingState.Recording, is RecordingState.Paused,
            is RecordingState.Interrupted, is RecordingState.Finalizing ->
                return StartupResult.Ready(StartupDestination.Recording)
            else -> Unit
        }

        lastSaved()?.let { return StartupResult.Ready(StartupDestination.Preview(it)) }

        return when (val sessions = recoverableSessionIds()) {
            is AppResult.Failure -> StartupResult.Error(sessions.error)
            is AppResult.Success -> {
                val destination = sessions.value.firstOrNull()?.let(StartupDestination::Recovery)
                    ?: if (hasCompletedOnboarding()) StartupDestination.Home else StartupDestination.Onboarding
                StartupResult.Ready(destination)
            }
        }
    }
}
