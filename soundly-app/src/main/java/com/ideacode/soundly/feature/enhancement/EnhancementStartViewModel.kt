package com.ideacode.soundly.feature.enhancement

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.ideacode.soundly.common.error.AppError
import com.ideacode.soundly.common.error.AppResult
import com.ideacode.soundly.common.state.*
import com.ideacode.soundly.domain.mainflow.EnhancementMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

data class EnhancementStartState(val busy: Boolean = false, val selected: EnhancementMode = EnhancementMode.default) : UiState
sealed interface EnhancementStartAction : UiAction { data class Start(val mode: EnhancementMode) : EnhancementStartAction; data class Select(val mode: EnhancementMode) : EnhancementStartAction }
sealed interface EnhancementStartEffect : UiEffect {
    data class Started(val taskId: String) : EnhancementStartEffect
    data class Error(val error: AppError) : EnhancementStartEffect
}
class EnhancementStartViewModel(private val saved: SavedStateHandle, private val start: StartEnhancementUseCase) :
    StateActionViewModel<EnhancementStartState, EnhancementStartAction, EnhancementStartEffect>(EnhancementStartState()) {
    init {
        saved.get<String>("h6_mode")?.let { name -> EnhancementMode.entries.firstOrNull { it.name == name }?.let { mode -> updateState { it.copy(selected = mode) } } }
        saved.get<String>("h5_setup_task")?.let { updateState { s -> s.copy(busy = true) }; postEffect(EnhancementStartEffect.Started(it)) } }
    override fun dispatch(action: EnhancementStartAction) {
        if (uiState.value.busy) return
        if (action is EnhancementStartAction.Select) { saved["h6_mode"] = action.mode.name; updateState { it.copy(selected = action.mode) }; return }
        if (action !is EnhancementStartAction.Start) return
        val projectId = saved.get<String>("projectId").orEmpty()
        val variantId = saved.get<String>("variantId").orEmpty()
        if (projectId.isBlank() || variantId.isBlank()) { postEffect(EnhancementStartEffect.Error(AppError("project_not_found", false))); return }
        updateState { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                when (val result = start(projectId, variantId, action.mode)) {
                    is AppResult.Success -> { saved["h5_setup_task"] = result.value.taskId; postEffect(EnhancementStartEffect.Started(result.value.taskId)) }
                    is AppResult.Failure -> { updateState { it.copy(busy = false) }; postEffect(EnhancementStartEffect.Error(result.error)) }
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                updateState { it.copy(busy = false) }; postEffect(EnhancementStartEffect.Error(AppError("processing_failed", true)))
            }
        }
    }
    fun navigationHandled() { saved.remove<String>("h5_setup_task"); updateState { it.copy(busy = false) } }
}
