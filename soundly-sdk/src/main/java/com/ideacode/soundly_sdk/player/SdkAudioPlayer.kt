package com.ideacode.soundly_sdk.player

import android.content.Context
import android.media.MediaPlayer
import com.ideacode.soundly_core.media.player.AudioPlayer
import com.ideacode.soundly_model.audio.AudioSource
import java.lang.Exception

class SdkAudioPlayer(
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

        mediaPlayer = MediaPlayer().apply {
            try {
                when (source) {
                    is AudioSource.Original -> {
                        setDataSource(context, source.uri)
                    }

                    is AudioSource.Processed -> {
                        setDataSource(source.wavFile.absolutePath)
                    }
                }

                setOnPreparedListener {
                    onPrepared?.invoke()
                }

                setOnCompletionListener {
                    onCompletion?.invoke()
                }

                setOnErrorListener { _, what, extra ->
                    onError?.invoke(
                        RuntimeException("MediaPlayer error: what=$what extra=$extra")
                    )
                    true
                }

                prepareAsync()
            } catch (e: Exception) {
                onError?.invoke(e)
            }
        }
    }

    override fun play() {
        mediaPlayer?.takeIf { !it.isPlaying }?.start()
    }

    override fun pause() {
        mediaPlayer?.takeIf { !it.isPlaying }?.pause()
    }

    override fun stop() {
        mediaPlayer?.stop()
    }

    override fun release() {
        mediaPlayer?.release()
        mediaPlayer = null
    }

    override fun isPlaying(): Boolean {
        return mediaPlayer?.isPlaying ?: false
    }


}