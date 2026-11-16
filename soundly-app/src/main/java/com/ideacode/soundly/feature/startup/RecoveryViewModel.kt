package com.ideacode.soundly.feature.startup

import androidx.lifecycle.viewModelScope
import androidx.lifecycle.SavedStateHandle
import com.ideacode.soundly.common.error.AppError
import com.ideacode.soundly.common.error.AppResult
import com.ideacode.soundly.common.state.StateActionViewModel
import com.ideacode.soundly.common.state.UiAction
import com.ideacode.soundly.common.state.UiEffect
import com.ideacode.soundly.common.state.UiState
import com.ideacode.soundly.data.sdk.RecordingGateway
import com.ideacode.soundly_sdk.api.model.recording.RecoverableRecording
import com.ideacode.soundly_sdk.api.model.recording.RecordingResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal data class RecoveryState(val sessionId: String, val session: RecoverableRecording? = null,
    val loading: Boolean = true, val busy: Boolean = false, val error: Boolean = false) : UiState
internal sealed interface RecoveryAction : UiAction { data object Load : RecoveryAction; data object Save : RecoveryAction; data object Discard : RecoveryAction }
internal sealed interface RecoveryEffect : UiEffect {
    data class Preview(val result: RecordingResult) : RecoveryEffect
    data object Home : RecoveryEffect
    data class Error(val error: AppError) : RecoveryEffect
}
internal class RecoveryViewModel(private val gateway: RecordingGateway, private val saved: SavedStateHandle, sessionId: String) :
    StateActionViewModel<RecoveryState, RecoveryAction, RecoveryEffect>(RecoveryState(saved.get<String>(CURRENT_SESSION) ?: sessionId)) {
    init { load() }
    override fun dispatch(action: RecoveryAction) {
        if (uiState.value.busy) return
        when (action) {
            RecoveryAction.Load -> load()
            RecoveryAction.Save -> if (uiState.value.session != null) operation {
                when (val result = gateway.recover(uiState.value.sessionId)) {
                    is AppResult.Success -> { postEffect(RecoveryEffect.Preview(result.value)); AppResult.Success(Unit) }
                    is AppResult.Failure -> result
                }
            }
            RecoveryAction.Discard -> if (uiState.value.session != null) operation {
                val result = gateway.discardRecovery(uiState.value.sessionId)
                when (result) {
                    is AppResult.Failure -> result
                    is AppResult.Success -> when (val remaining = gateway.recoverable()) {
                        is AppResult.Failure -> {
                            updateState { it.copy(session = null, error = true) }
                            remaining
                        }
                        is AppResult.Success -> {
                            val next = remaining.value.firstOrNull()
                            if (next == null) postEffect(RecoveryEffect.Home)
                            else {
                                saved[CURRENT_SESSION] = next.sessionId
                                updateState { it.copy(sessionId = next.sessionId, session = next) }
                            }
                            AppResult.Success(Unit)
                        }
                    }
                }
            }
        }
    }
    fun acknowledgePreview(result: RecordingResult) = gateway.acknowledgeSaved(result.sessionId)
    private fun load() {
        if (uiState.value.busy) return
        updateState { it.copy(loading = true, error = false) }
        viewModelScope.launch {
            try { when (val result = gateway.recoverable()) {
                is AppResult.Failure -> updateState { it.copy(loading = false, error = true) }
                is AppResult.Success -> {
                    val session = result.value.firstOrNull { it.sessionId == uiState.value.sessionId } ?: result.value.firstOrNull()
                    if (session != null) saved[CURRENT_SESSION] = session.sessionId
                    updateState { state -> state.copy(loading = false, sessionId = session?.sessionId ?: state.sessionId, session = session) }
                }
            } } catch (e: CancellationException) { throw e } catch (_: Exception) { updateState { it.copy(loading = false, error = true) } }
        }
    }
    private fun operation(block: suspend () -> AppResult<Unit>) {
        updateState { it.copy(busy = true) }
        viewModelScope.launch {
            try { val result = block(); if (result is AppResult.Failure) postEffect(RecoveryEffect.Error(result.error)) }
            catch (e: CancellationException) { throw e } catch (_: Exception) { postEffect(RecoveryEffect.Error(AppError("recording_failed", true))) }
            finally { updateState { it.copy(busy = false) } }
        }
    }
    companion object { private const val CURRENT_SESSION = "current_recovery_session" }
}
