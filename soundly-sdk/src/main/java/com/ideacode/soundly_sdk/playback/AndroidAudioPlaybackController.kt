package com.ideacode.soundly_sdk.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import com.ideacode.soundly_sdk.api.capability.AudioPlaybackController
import com.ideacode.soundly_sdk.api.error.SoundlyError
import com.ideacode.soundly_sdk.api.error.SoundlyErrorMapper
import com.ideacode.soundly_sdk.api.model.audio.AudioSourceRef
import com.ideacode.soundly_sdk.api.model.playback.PlaybackState
import com.ideacode.soundly_sdk.api.result.SoundlyResult
import com.ideacode.soundly_sdk.internal.SoundlyDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.FileNotFoundException
import kotlin.coroutines.resume

internal class AndroidAudioPlaybackController(
    private val context: Context,
    dispatchers: SoundlyDispatchers = SoundlyDispatchers.Default
) : AudioPlaybackController {

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.computation)
    private val mutableState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(audioAttributes)
        .setOnAudioFocusChangeListener(::onAudioFocusChange)
        .build()

    override val state: StateFlow<PlaybackState> = mutableState.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null
    private var tickerJob: Job? = null
    private var generation = 0

    override suspend fun prepare(source: AudioSourceRef): SoundlyResult<Unit> {
        if (source is AudioSourceRef.LocalFile && !source.file.isFile) {
            return SoundlyResult.Failure(
                SoundlyError.FileNotFound(source.file.absolutePath)
            )
        }
        val currentGeneration: Int
        val player: MediaPlayer
        synchronized(lock) {
            releasePlayer(updateState = false)
            generation += 1
            currentGeneration = generation
            mutableState.value = PlaybackState.Preparing
            player = MediaPlayer().apply {
                setAudioAttributes(audioAttributes)
            }
            mediaPlayer = player
        }

        return suspendCancellableCoroutine { continuation ->
            fun fail(error: Exception) {
                synchronized(lock) {
                    if (currentGeneration != generation) return
                    releasePlayer(updateState = false)
                    val mapped = SoundlyErrorMapper.playback(error)
                    mutableState.value = PlaybackState.Failed(mapped)
                    if (continuation.isActive) {
                        continuation.resume(SoundlyResult.Failure(mapped))
                    }
                }
            }

            try {
                player.setOnPreparedListener { preparedPlayer ->
                    synchronized(lock) {
                        if (currentGeneration != generation) return@setOnPreparedListener
                        mutableState.value = PlaybackState.Ready(
                            preparedPlayer.duration.toLong().coerceAtLeast(0L)
                        )
                        if (continuation.isActive) {
                            continuation.resume(SoundlyResult.Success(Unit))
                        }
                    }
                }
                player.setOnCompletionListener { completedPlayer ->
                    stopTicker()
                    abandonAudioFocus()
                    mutableState.value = PlaybackState.Completed
                    runCatching { completedPlayer.seekTo(0) }
                }
                player.setOnErrorListener { _, what, extra ->
                    fail(IllegalStateException("MediaPlayer error what=$what extra=$extra"))
                    true
                }
                when (source) {
                    is AudioSourceRef.ContentUri -> player.setDataSource(context, source.uri)
                    is AudioSourceRef.LocalFile -> player.setDataSource(source.file.absolutePath)
                }
                player.prepareAsync()
            } catch (error: SecurityException) {
                fail(error)
            } catch (error: FileNotFoundException) {
                fail(error)
            } catch (error: Exception) {
                fail(error)
            }

            continuation.invokeOnCancellation {
                synchronized(lock) {
                    if (currentGeneration == generation) releasePlayer(updateState = true)
                }
            }
        }
    }

    override fun play(): SoundlyResult<Unit> = synchronized(lock) {
        val player = mediaPlayer
            ?: return@synchronized SoundlyResult.Failure(
                SoundlyError.InvalidState("play")
            )
        if (mutableState.value !is PlaybackState.Ready &&
            mutableState.value !is PlaybackState.Paused &&
            mutableState.value !is PlaybackState.Completed
        ) {
            return@synchronized SoundlyResult.Failure(SoundlyError.InvalidState("play"))
        }
        if (!requestAudioFocus()) {
            return@synchronized SoundlyResult.Failure(SoundlyError.PlaybackFailed())
        }
        return@synchronized try {
            if (mutableState.value is PlaybackState.Completed) player.seekTo(0)
            player.start()
            mutableState.value = PlaybackState.Playing(
                player.currentPosition.toLong(),
                player.duration.toLong().coerceAtLeast(0L)
            )
            startTicker()
            SoundlyResult.Success(Unit)
        } catch (error: Exception) {
            val mapped = SoundlyErrorMapper.playback(error)
            mutableState.value = PlaybackState.Failed(mapped)
            SoundlyResult.Failure(mapped)
        }
    }

    override fun pause(): SoundlyResult<Unit> = synchronized(lock) {
        val player = mediaPlayer
            ?: return@synchronized SoundlyResult.Failure(
                SoundlyError.InvalidState("pause")
            )
        if (mutableState.value !is PlaybackState.Playing) {
            return@synchronized SoundlyResult.Failure(SoundlyError.InvalidState("pause"))
        }
        return@synchronized try {
            player.pause()
            stopTicker()
            abandonAudioFocus()
            mutableState.value = PlaybackState.Paused(
                player.currentPosition.toLong(),
                player.duration.toLong().coerceAtLeast(0L)
            )
            SoundlyResult.Success(Unit)
        } catch (error: Exception) {
            playbackFailure(error)
        }
    }

    override fun stop(): SoundlyResult<Unit> = synchronized(lock) {
        val player = mediaPlayer
            ?: return@synchronized SoundlyResult.Failure(
                SoundlyError.InvalidState("stop")
            )
        if (mutableState.value is PlaybackState.Preparing) {
            return@synchronized SoundlyResult.Failure(SoundlyError.InvalidState("stop"))
        }
        return@synchronized try {
            if (player.isPlaying) player.pause()
            player.seekTo(0)
            stopTicker()
            abandonAudioFocus()
            mutableState.value = PlaybackState.Ready(
                player.duration.toLong().coerceAtLeast(0L)
            )
            SoundlyResult.Success(Unit)
        } catch (error: Exception) {
            playbackFailure(error)
        }
    }

    override fun seekTo(positionMs: Long): SoundlyResult<Unit> = synchronized(lock) {
        val player = mediaPlayer
            ?: return@synchronized SoundlyResult.Failure(
                SoundlyError.InvalidState("seekTo")
            )
        if (positionMs < 0L || mutableState.value is PlaybackState.Preparing) {
            return@synchronized SoundlyResult.Failure(
                SoundlyError.InvalidRequest("positionMs")
            )
        }
        return@synchronized try {
            val duration = player.duration.toLong().coerceAtLeast(0L)
            val clamped = positionMs.coerceAtMost(duration)
            player.seekTo(clamped.toInt())
            mutableState.value = when (mutableState.value) {
                is PlaybackState.Playing -> PlaybackState.Playing(clamped, duration)
                else -> PlaybackState.Paused(clamped, duration)
            }
            SoundlyResult.Success(Unit)
        } catch (error: Exception) {
            playbackFailure(error)
        }
    }

    override suspend fun switchSourceKeepingPosition(
        source: AudioSourceRef
    ): SoundlyResult<Unit> {
        val wasPlaying: Boolean
        val positionMs: Long
        synchronized(lock) {
            wasPlaying = mutableState.value is PlaybackState.Playing
            positionMs = currentPosition()
        }
        val prepared = prepare(source)
        if (prepared is SoundlyResult.Failure) return prepared
        val seek = seekTo(positionMs)
        if (seek is SoundlyResult.Failure) return seek
        return if (wasPlaying) play() else SoundlyResult.Success(Unit)
    }

    override fun release() {
        synchronized(lock) {
            generation += 1
            releasePlayer(updateState = true)
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                synchronized(lock) {
                    val player = mediaPlayer ?: return@launch
                    if (mutableState.value is PlaybackState.Playing) {
                        runCatching {
                            mutableState.value = PlaybackState.Playing(
                                player.currentPosition.toLong(),
                                player.duration.toLong().coerceAtLeast(0L)
                            )
                        }
                    }
                }
                delay(POSITION_UPDATE_INTERVAL_MS)
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    private fun currentPosition(): Long = when (val current = mutableState.value) {
        is PlaybackState.Playing -> current.positionMs
        is PlaybackState.Paused -> current.positionMs
        else -> runCatching { mediaPlayer?.currentPosition?.toLong() ?: 0L }.getOrDefault(0L)
    }

    private fun releasePlayer(updateState: Boolean) {
        stopTicker()
        abandonAudioFocus()
        runCatching { mediaPlayer?.reset() }
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
        if (updateState) mutableState.value = PlaybackState.Idle
    }

    private fun requestAudioFocus(): Boolean =
        audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    private fun abandonAudioFocus() {
        audioManager.abandonAudioFocusRequest(focusRequest)
    }

    private fun onAudioFocusChange(change: Int) {
        if (change == AudioManager.AUDIOFOCUS_LOSS ||
            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
        ) {
            if (mutableState.value is PlaybackState.Playing) pause()
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            synchronized(lock) { runCatching { mediaPlayer?.setVolume(0.2f, 0.2f) } }
        } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
            synchronized(lock) { runCatching { mediaPlayer?.setVolume(1f, 1f) } }
        }
    }

    private fun playbackFailure(error: Exception): SoundlyResult.Failure {
        val mapped = SoundlyErrorMapper.playback(error)
        mutableState.value = PlaybackState.Failed(mapped)
        return SoundlyResult.Failure(mapped)
    }

    private companion object {
        const val POSITION_UPDATE_INTERVAL_MS = 200L
    }
}
