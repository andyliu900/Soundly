package com.ideacode.soundly.feature.project

import com.ideacode.soundly.common.error.AppError
import com.ideacode.soundly.common.error.AppResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectDeletionViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun teardown() = Dispatchers.resetMain()

    @Test fun `confirmed deletion is single flight and completion remains observable`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val model = ProjectDeletionViewModel("project") { id ->
            assertEquals("project", id); calls++; gate.await(); AppResult.Success(Unit)
        }
        model.delete(); model.delete(); runCurrent()
        assertEquals(1, calls)
        assertEquals(ProjectDeletionState.RUNNING, model.state.value)
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(ProjectDeletionState.SUCCEEDED, model.state.value)
        model.delete(); advanceUntilIdle(); assertEquals(1, calls)
    }

    @Test fun `failed deletion can be acknowledged and retried`() = runTest(dispatcher) {
        var calls = 0
        val model = ProjectDeletionViewModel("project") { calls++; AppResult.Failure(AppError("failure", true)) }
        model.delete(); advanceUntilIdle()
        assertEquals(ProjectDeletionState.FAILED, model.state.value)
        model.acknowledgeFailure(); model.delete(); advanceUntilIdle()
        assertEquals(2, calls)
    }
}
