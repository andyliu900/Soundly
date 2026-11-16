package com.ideacode.soundly.feature.recording

import com.ideacode.soundly.common.error.AppError
import com.ideacode.soundly.common.error.AppResult
import com.ideacode.soundly.data.sdk.RecordingGateway
import com.ideacode.soundly_sdk.api.model.recording.RecordingConfig
import com.ideacode.soundly_sdk.api.model.recording.RecordingState

/** Serial prepare/start for a single user action, preserving recoverable failures. */
class StartRecordingUseCase(private val gateway: RecordingGateway) {
    suspend operator fun invoke(config: RecordingConfig): AppResult<Unit> {
        val current = gateway.state.value
        if (current is RecordingState.Failed && current.sessionId != null) {
            return AppResult.Failure(AppError("recording_recovery_required", false))
        }
        return when (val prepared = gateway.prepare(config)) {
            is AppResult.Failure -> prepared
            is AppResult.Success -> when (val started = gateway.start()) {
                is AppResult.Success -> AppResult.Success(Unit)
                is AppResult.Failure -> {
                    // Ready is an empty prepared session. Failed sessions may contain audio and
                    // must remain available to the SDK's recovery flow.
                    if (gateway.state.value is RecordingState.Ready) gateway.discard()
                    started
                }
            }
        }
    }
}
