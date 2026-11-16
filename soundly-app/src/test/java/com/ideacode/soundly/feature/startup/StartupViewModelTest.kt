package com.ideacode.soundly.feature.startup

import com.ideacode.soundly.common.error.AppError
import com.ideacode.soundly.common.error.AppResult
import com.ideacode.soundly_sdk.api.model.recording.RecordingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StartupViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `ready navigation stays pending until acknowledged without being repeated`() = runTest(dispatcher) {
        val model = StartupViewModel(coordinator(), clock = { 0 }, minimumDisplayMs = 0)
        advanceUntilIdle()
        assertEquals(StartupUiState.Ready, model.uiState.value)
        val effect = model.effects.value.single()
        assertEquals(StartupEffect.Navigate(StartupDestination.Onboarding), effect.value)
        // A recreated collector observes the same pending effect, not a second navigation.
        assertEquals(effect, model.effects.value.single())
        model.acknowledgeEffect(effect.id)
        assertTrue(model.effects.value.isEmpty())
        advanceUntilIdle()
        assertTrue(model.effects.value.isEmpty())
    }

    @Test
    fun `retry after sdk failure starts only once despite rapid taps`() = runTest(dispatcher) {
        var initialized = false
        var initializationCount = 0
        val error = AppError("sdk_initialization_failed", true)
        val coordinator = StartupCoordinator(
            sdkReady = { if (initialized) AppResult.Success(Unit) else AppResult.Failure(error) },
            initializeSdk = { initializationCount++; initialized = true; AppResult.Success(Unit) },
            recordingState = { RecordingState.Idle },
            recoverableSessionIds = { AppResult.Success(emptyList()) },
            hasCompletedOnboarding = { false }
        )
        val model = StartupViewModel(coordinator, clock = { 0 }, minimumDisplayMs = 0)
        advanceUntilIdle()
        assertEquals(StartupUiState.Error(error), model.uiState.value)
        model.dispatch(StartupAction.Retry)
        model.dispatch(StartupAction.Retry)
        advanceUntilIdle()
        assertEquals(1, initializationCount)
        assertEquals(1, model.effects.value.size)
    }

    private fun coordinator() = StartupCoordinator(
        sdkReady = { AppResult.Success(Unit) },
        initializeSdk = { AppResult.Success(Unit) },
        recordingState = { RecordingState.Idle },
        recoverableSessionIds = { AppResult.Success(emptyList()) },
        hasCompletedOnboarding = { false }
    )
}
