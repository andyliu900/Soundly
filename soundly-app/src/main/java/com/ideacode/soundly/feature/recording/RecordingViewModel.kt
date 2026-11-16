package com.ideacode.soundly.feature.recording

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.ideacode.soundly.common.error.AppError
import com.ideacode.soundly.common.error.AppResult
import com.ideacode.soundly.common.state.StateActionViewModel
import com.ideacode.soundly.common.state.UiAction
import com.ideacode.soundly.common.state.UiEffect
import com.ideacode.soundly.common.state.UiState
import com.ideacode.soundly.data.sdk.RecordingGateway
import com.ideacode.soundly_sdk.api.model.domain.AudioMarker
import com.ideacode.soundly_sdk.api.model.recording.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RecordingUiState(val recording: RecordingState = RecordingState.Idle,
    val config: RecordingConfig? = null, val markers: List<AudioMarker> = emptyList(), val busy: Boolean = false, val durationMs: Long = 0, val recordedBytes: Long = 0,
    val discardSessionId: String? = null) : UiState
sealed interface RecordingAction : UiAction {
    data object Toggle : RecordingAction
    data object Marker : RecordingAction
    data object Save : RecordingAction
    data object Discard : RecordingAction
    data object Recover : RecordingAction
}
sealed interface RecordingEffect : UiEffect {
    data class Preview(val result: RecordingResult) : RecordingEffect
    data object Home : RecordingEffect
    data class Error(val error: AppError) : RecordingEffect
}

class RecordingViewModel(private val gateway: RecordingGateway, private val saved: SavedStateHandle) :
    StateActionViewModel<RecordingUiState, RecordingAction, RecordingEffect>(RecordingUiState(recording = gateway.state.value, config = gateway.currentConfig.value)) {
    private val mutableWaveform = MutableStateFlow<List<Float>>(emptyList())
    val waveform = mutableWaveform.asStateFlow()
    private var navigatedSession: String? = null
    private var sessionId: String?
        get() = saved[SESSION]
        set(value) { saved[SESSION] = value }

    init {
        val pendingDiscard = saved.get<String>(DISCARD_SESSION)
        if (pendingDiscard != null && pendingDiscard == sessionOf(gateway.state.value)) updateState { it.copy(discardSessionId = pendingDiscard) }
        else saved.remove<String>(DISCARD_SESSION)
        sessionOf(gateway.state.value)?.let { sessionId = it }
        if (sessionId == null) gateway.lastSaved.value?.let { sessionId = it.sessionId }
        viewModelScope.launch { gateway.state.collect { state ->
            sessionOf(state)?.let { sessionId = it }
            if (uiState.value.discardSessionId != null &&
                (sessionOf(state) != uiState.value.discardSessionId || state is RecordingState.Recording || state is RecordingState.Finalizing)) clearDiscardConfirmation()
            val duration = when (state) { is RecordingState.Recording -> state.durationMs; is RecordingState.Paused -> state.durationMs; is RecordingState.Interrupted -> state.durationMs; else -> null }
            val bytes = when (state) { is RecordingState.Recording -> state.recordedBytes; is RecordingState.Paused -> state.recordedBytes; is RecordingState.Interrupted -> state.recordedBytes; else -> null }
            updateState { it.copy(recording = state, durationMs = duration ?: it.durationMs, recordedBytes = bytes ?: it.recordedBytes) }
            checkSaved(gateway.lastSaved.value)
        } }
        viewModelScope.launch { gateway.currentConfig.collect { config ->
            if (config != null) updateState { it.copy(config = config) }
        } }
        viewModelScope.launch { gateway.markers.collect { markers -> updateState { it.copy(markers = markers) } } }
        viewModelScope.launch { gateway.waveform.collect { frame ->
            if (gateway.state.value is RecordingState.Recording) {
                mutableWaveform.value = (mutableWaveform.value + frame.amplitudes.map { it.coerceIn(0f, 1f) }).takeLast(MAX_BARS)
            }
        } }
        viewModelScope.launch { gateway.lastSaved.collect { checkSaved(it) } }
    }
    override fun dispatch(action: RecordingAction) {
        if (uiState.value.busy || uiState.value.recording is RecordingState.Finalizing) return
        val state = gateway.state.value
        when (action) {
            RecordingAction.Toggle -> when (state) {
                is RecordingState.Recording -> operation { gateway.pause() }
                is RecordingState.Paused, is RecordingState.Interrupted -> operation { gateway.resume() }
                else -> Unit
            }
            RecordingAction.Marker -> if (state is RecordingState.Recording) operation { gateway.addMarker() }
            RecordingAction.Save -> if (state is RecordingState.Recording || state is RecordingState.Paused || state is RecordingState.Interrupted)
                operation { gateway.stopAndSave().also { if (it is AppResult.Success) checkSaved(it.value) }.unit() }
            RecordingAction.Recover -> if (state is RecordingState.Failed && state.sessionId != null)
                operation { gateway.recover(requireNotNull(state.sessionId)).also { if (it is AppResult.Success) checkSaved(it.value) }.unit() }
            RecordingAction.Discard -> {
                val target = uiState.value.discardSessionId ?: return
                if (target != sessionOf(state)) { clearDiscardConfirmation(); return }
                clearDiscardConfirmation()
                operation {
                when (val result = if (state is RecordingState.Failed) gateway.discardRecovery(target) else gateway.discardSession(target)) {
                    is AppResult.Success -> { postEffect(RecordingEffect.Home); result }
                    is AppResult.Failure -> result
                }
                }
            }
        }
    }
    fun requestDiscardConfirmation() {
        val state = gateway.state.value
        val id = sessionOf(state) ?: return
        if (uiState.value.busy || uiState.value.discardSessionId != null || state is RecordingState.Finalizing) return
        updateState { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val paused = if (state is RecordingState.Recording) gateway.pauseSession(id) else AppResult.Success(Unit)
                if (paused is AppResult.Failure) postEffect(RecordingEffect.Error(paused.error))
                else if (sessionOf(gateway.state.value) == id && gateway.state.value !is RecordingState.Finalizing) {
                    val current = gateway.state.value
                    saved[DISCARD_SESSION] = id
                    updateState { it.copy(discardSessionId = id,
                        durationMs = (current as? RecordingState.Paused)?.durationMs ?: it.durationMs,
                        recordedBytes = (current as? RecordingState.Paused)?.recordedBytes ?: it.recordedBytes) }
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                postEffect(RecordingEffect.Error(AppError("recording_failed", true)))
            } finally { updateState { it.copy(busy = false) } }
        }
    }
    fun clearDiscardConfirmation() { saved.remove<String>(DISCARD_SESSION); updateState { it.copy(discardSessionId = null) } }
    private fun operation(block: suspend () -> AppResult<Unit>) {
        updateState { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                when (val result = block()) {
                    is AppResult.Failure -> postEffect(RecordingEffect.Error(result.error))
                    is AppResult.Success -> Unit
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                postEffect(RecordingEffect.Error(AppError("recording_failed", true)))
            } finally { updateState { it.copy(busy = false) } }
        }
    }
    private fun checkSaved(result: RecordingResult?) {
        if (result != null && result.sessionId == sessionId && navigatedSession != result.sessionId) {
            navigatedSession = result.sessionId
            postEffect(RecordingEffect.Preview(result))
        }
    }
    fun acknowledgePreview(result: RecordingResult) { gateway.acknowledgeSaved(result.sessionId) }
    private fun sessionOf(state: RecordingState): String? = when (state) {
        is RecordingState.Ready -> state.sessionId
        is RecordingState.Recording -> state.sessionId
        is RecordingState.Paused -> state.sessionId
        is RecordingState.Interrupted -> state.sessionId
        is RecordingState.Finalizing -> state.sessionId
        is RecordingState.Failed -> state.sessionId
        else -> null
    }
    private fun <T> AppResult<T>.unit(): AppResult<Unit> = when (this) {
        is AppResult.Success -> AppResult.Success(Unit)
        is AppResult.Failure -> this
    }
    companion object { private const val SESSION = "recording_session_id"; private const val DISCARD_SESSION = "discard_session_id"; private const val MAX_BARS = 120 }
}
