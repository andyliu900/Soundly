package com.ideacode.soundly_sdk.player

import android.content.Context
import android.media.MediaPlayer
import com.ideacode.soundly_sdk.core.media.player.AudioPlayer
import com.ideacode.soundly_sdk.api.model.audio.AudioSource
import java.lang.Exception

private class SdkAudioPlayer(
    private val context: Context
): AudioPlayer {

    private var mediaPlayer: MediaPlayer? = null

    override fun prepare(
        source: AudioSource,
        onPrepared: (() -> Unit)?,
        onCompletion: (() -> Unit)?,
        onError: ((Throwable) -> Unit)?
    ) {
        release()
        val player = MediaPlayer()
        mediaPlayer = player
        try {
            when (source) {
                is AudioSource.Original -> {
                    player.setDataSource(context, source.uri)
                }

                is AudioSource.Processed -> {
                    player.setDataSource(source.wavFile.absolutePath)
                }
            }

            player.setOnPreparedListener {
                onPrepared?.invoke()
            }

            player.setOnCompletionListener {
                onCompletion?.invoke()
            }

            player.setOnErrorListener { failedPlayer, what, extra ->
                if (mediaPlayer === failedPlayer) {
                    mediaPlayer = null
                }
                runCatching { failedPlayer.release() }
                onError?.invoke(MediaPlayerFailure(what, extra))
                true
            }

            player.prepareAsync()
        } catch (error: Exception) {
            if (mediaPlayer === player) {
                mediaPlayer = null
            }
            runCatching { player.release() }
            onError?.invoke(error)
        }
    }

    override fun play() {
        mediaPlayer?.takeIf { !it.isPlaying }?.start()
    }

    override fun pause() {
        mediaPlayer?.takeIf { it.isPlaying }?.pause()
    }

    override fun stop() {
        runCatching { mediaPlayer?.stop() }
    }

    override fun release() {
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
    }

    override fun isPlaying(): Boolean {
        return runCatching { mediaPlayer?.isPlaying ?: false }.getOrDefault(false)
    }

    private class MediaPlayerFailure(
        val what: Int,
        val extra: Int
    ) : IllegalStateException("MediaPlayer playback failed")
}
