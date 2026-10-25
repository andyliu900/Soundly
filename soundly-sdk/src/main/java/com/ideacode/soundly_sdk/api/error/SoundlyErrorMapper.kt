package com.ideacode.soundly_sdk.api.error

import com.ideacode.soundly_sdk.core.audio.decode.AudioDecodingException
import com.ideacode.soundly_sdk.recording.MicrophoneUnavailableException
import java.io.FileNotFoundException

internal object SoundlyErrorMapper {

    fun processing(error: Exception): SoundlyError = when (error) {
        is SecurityException -> SoundlyError.PermissionDenied
        is FileNotFoundException -> SoundlyError.FileNotFound(cause = error)
        is AudioDecodingException -> SoundlyError.DecodeFailed(error.cause)
        is IllegalArgumentException -> SoundlyError.InvalidAudioFile(error)
        else -> SoundlyError.ProcessingFailed(error)
    }

    fun recording(error: Exception): SoundlyError = when (error) {
        is SecurityException -> SoundlyError.PermissionDenied
        is MicrophoneUnavailableException -> SoundlyError.MicrophoneUnavailable
        is java.io.FileNotFoundException -> SoundlyError.FileNotFound(cause = error)
        is IllegalArgumentException -> SoundlyError.InvalidRequest(cause = error)
        else -> SoundlyError.RecordingFailed(error)
    }

    fun playback(error: Exception): SoundlyError = when (error) {
        is SecurityException -> SoundlyError.PermissionDenied
        is java.io.FileNotFoundException -> SoundlyError.FileNotFound(cause = error)
        else -> SoundlyError.PlaybackFailed(error)
    }
}
