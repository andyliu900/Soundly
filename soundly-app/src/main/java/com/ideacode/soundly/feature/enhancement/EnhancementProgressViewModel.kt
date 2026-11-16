package com.ideacode.soundly.feature.enhancement

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.ideacode.soundly.common.error.AppResult
import com.ideacode.soundly.common.state.*
import com.ideacode.soundly.data.sdk.EditingGateway
import com.ideacode.soundly_sdk.api.model.enhancement.EnhancementTaskState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class ProgressState(val task: EnhancementTaskState? = null, val loading: Boolean = true,
    val unknown: Boolean = false, val busy: Boolean = false, val operationError: Boolean = false,
    val projectId: String = "", val sourceId: String = "") : UiState
sealed interface ProgressAction : UiAction { data object Cancel : ProgressAction; data object Retry : ProgressAction }
sealed interface ProgressEffect : UiEffect { data object Unused : ProgressEffect }

class EnhancementProgressViewModel(private val saved: SavedStateHandle, private val gateway: EditingGateway) :
    StateActionViewModel<ProgressState, ProgressAction, ProgressEffect>(ProgressState(
        projectId = saved.get<String>("projectId").orEmpty(), sourceId = saved.get<String>("sourceVariantId").orEmpty())) {
    private val activeTask = MutableStateFlow(saved.get<String>("activeTaskId") ?: saved.get<String>("taskId").orEmpty())
    init {
        viewModelScope.launch {
            activeTask.collectLatest { id ->
                if (id.isBlank()) { updateState { it.copy(loading = false, unknown = true) }; return@collectLatest }
                try {
                    gateway.observeEnhancement(id).collect { task ->
                        task?.task?.let { saved["projectId"] = it.projectId; saved["sourceVariantId"] = it.sourceVariantId }
                        updateState { old -> old.copy(task = task, loading = false, unknown = task == null,
                            projectId = task?.task?.projectId ?: old.projectId, sourceId = task?.task?.sourceVariantId ?: old.sourceId) }
                    }
                } catch (e: CancellationException) { throw e } catch (_: Exception) { updateState { it.copy(loading = false, unknown = true) } }
            }
        }
    }
    override fun dispatch(action: ProgressAction) {
        val state = uiState.value
        if (state.busy) return
        when (action) {
            ProgressAction.Cancel -> if (state.task is EnhancementTaskState.Running) operation(false)
            ProgressAction.Retry -> if ((state.task as? EnhancementTaskState.Failed)?.retryable == true) operation(true)
        }
    }
    private fun operation(retry: Boolean) {
        val id = activeTask.value
        updateState { it.copy(busy = true, operationError = false) }
        viewModelScope.launch {
            try {
                if (retry) {
                    when (val result = gateway.retryEnhancement(id)) {
                        is AppResult.Success -> { saved["activeTaskId"] = result.value.taskId; updateState { it.copy(task = null, loading = true, unknown = false) }; activeTask.value = result.value.taskId }
                        is AppResult.Failure -> updateState { it.copy(operationError = true) }
                    }
                } else if (gateway.cancelEnhancement(id) is AppResult.Failure && uiState.value.task is EnhancementTaskState.Running) {
                    updateState { it.copy(operationError = true) }
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { updateState { it.copy(operationError = true) } }
            finally { updateState { it.copy(busy = false) } }
        }
    }
}
