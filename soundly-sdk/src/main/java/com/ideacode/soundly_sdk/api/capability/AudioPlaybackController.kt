package com.ideacode.soundly_sdk.api.capability

import com.ideacode.soundly_sdk.api.model.audio.AudioSourceRef
import com.ideacode.soundly_sdk.api.model.playback.PlaybackState
import com.ideacode.soundly_sdk.api.result.SoundlyResult
import kotlinx.coroutines.flow.StateFlow

interface AudioPlaybackController {
    val state: StateFlow<PlaybackState>

    suspend fun prepare(source: AudioSourceRef): SoundlyResult<Unit>
    fun play(): SoundlyResult<Unit>
    fun pause(): SoundlyResult<Unit>
    fun stop(): SoundlyResult<Unit>
    fun seekTo(positionMs: Long): SoundlyResult<Unit>
    suspend fun switchSourceKeepingPosition(source: AudioSourceRef): SoundlyResult<Unit>
    fun release()
}
