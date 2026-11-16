package com.ideacode.soundly.feature.startup

import com.ideacode.soundly.common.error.AppError
import com.ideacode.soundly.common.error.AppResult
import com.ideacode.soundly_sdk.api.model.recording.RecordingState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupCoordinatorTest {
    private val failure = AppError("sdk_initialization_failed", true)

    @Test
    fun `first launch and returning user follow onboarding state`() = runTest {
        assertEquals(StartupResult.Ready(StartupDestination.Onboarding), coordinator().resolve())
        assertEquals(StartupResult.Ready(StartupDestination.Home), coordinator(onboarded = true).resolve())
    }

    @Test
    fun `active recording takes precedence over an older recovery`() = runTest {
        val active = RecordingState.Recording("active", 1000, 100, 0)
        var inspectedRecovery = false
        val coordinator = coordinator(state = active, sessions = {
            inspectedRecovery = true
            AppResult.Success(listOf("old"))
        })
        assertEquals(StartupResult.Ready(StartupDestination.Recording), coordinator.resolve())
        assertEquals(false, inspectedRecovery)
    }

    @Test
    fun `recoverable recording takes precedence over onboarding`() = runTest {
        val coordinator = coordinator(sessions = { AppResult.Success(listOf("session-1")) })
        assertEquals(StartupResult.Ready(StartupDestination.Recovery("session-1")), coordinator.resolve())
    }

    @Test
    fun `recovery query failure stays on startup instead of skipping data`() = runTest {
        val coordinator = coordinator(sessions = { AppResult.Failure(failure) })
        assertEquals(StartupResult.Error(failure), coordinator.resolve())
    }

    @Test
    fun `retry initializes sdk again and verifies readiness`() = runTest {
        var initialized = false
        val coordinator = StartupCoordinator(
            sdkReady = { if (initialized) AppResult.Success(Unit) else AppResult.Failure(failure) },
            initializeSdk = { initialized = true; AppResult.Success(Unit) },
            recordingState = { RecordingState.Idle },
            recoverableSessionIds = { AppResult.Success(emptyList()) },
            hasCompletedOnboarding = { false }
        )
        assertEquals(StartupResult.Error(failure), coordinator.resolve())
        assertEquals(StartupResult.Ready(StartupDestination.Onboarding), coordinator.resolve(retry = true))
        assertTrue(initialized)
    }

    private fun coordinator(
        onboarded: Boolean = false,
        state: RecordingState = RecordingState.Idle,
        sessions: suspend () -> AppResult<List<String>> = { AppResult.Success(emptyList()) }
    ) = StartupCoordinator(
        sdkReady = { AppResult.Success(Unit) },
        initializeSdk = { AppResult.Success(Unit) },
        recordingState = { state },
        recoverableSessionIds = sessions,
        hasCompletedOnboarding = { onboarded }
    )
}
