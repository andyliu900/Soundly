package com.ideacode.soundly_core.media.player

import com.ideacode.soundly_model.audio.AudioSource


interface AudioPlayer {

    fun prepare(
        source: AudioSource,
        onPrepared: (() -> Unit)? = null,
        onCompletion: (() -> Unit)? = null,
        onError: ((Throwable) -> Unit)? = null
    )

    fun play()

    fun pause()

    fun stop()

    fun release()

    fun isPlaying(): Boolean

}