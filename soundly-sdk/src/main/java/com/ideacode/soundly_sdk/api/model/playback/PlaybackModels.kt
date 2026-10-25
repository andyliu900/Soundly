package com.ideacode.soundly_sdk.api.model.playback

import com.ideacode.soundly_sdk.api.error.SoundlyError

sealed interface PlaybackState {
    data object Idle : PlaybackState
    data object Preparing : PlaybackState

    data class Ready(
        val durationMs: Long
    ) : PlaybackState

    data class Playing(
        val positionMs: Long,
        val durationMs: Long
    ) : PlaybackState

    data class Paused(
        val positionMs: Long,
        val durationMs: Long
    ) : PlaybackState

    data object Completed : PlaybackState

    data class Failed(
        val error: SoundlyError
    ) : PlaybackState
}

