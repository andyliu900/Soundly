package com.ideacode.soundly_sdk.playback

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ideacode.soundly_sdk.api.model.audio.AudioSourceRef
import com.ideacode.soundly_sdk.api.model.playback.PlaybackState
import com.ideacode.soundly_sdk.api.result.SoundlyResult
import com.ideacode.soundly_sdk.core.util.WavWriter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioPlaybackControllerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val files = mutableListOf<java.io.File>()

    @After
    fun tearDown() {
        files.forEach { it.delete() }
    }

    @Test
    fun switchSourceKeepsPausedPosition() = runBlocking {
        val first = createWav("playback-first")
        val second = createWav("playback-second")
        val controller = AndroidAudioPlaybackController(context)

        assertTrue(controller.prepare(AudioSourceRef.LocalFile(first)) is SoundlyResult.Success)
        assertTrue(controller.seekTo(400L) is SoundlyResult.Success)
        assertTrue(
            controller.switchSourceKeepingPosition(AudioSourceRef.LocalFile(second))
                is SoundlyResult.Success
        )

        val state = controller.state.value
        assertTrue(state is PlaybackState.Paused)
        assertEquals(400L, (state as PlaybackState.Paused).positionMs)
        controller.release()
        assertEquals(PlaybackState.Idle, controller.state.value)
    }

    @Test
    fun pauseRequiresAnActivelyPlayingPlayer() = runBlocking {
        val source = createWav("playback-pause-state")
        val controller = AndroidAudioPlaybackController(context)

        assertTrue(controller.prepare(AudioSourceRef.LocalFile(source)) is SoundlyResult.Success)
        val result = controller.pause()

        assertTrue(result is SoundlyResult.Failure)
        assertEquals("invalid_state", (result as SoundlyResult.Failure).error.code)
        assertTrue(controller.state.value is PlaybackState.Ready)
        controller.release()
    }

    @Test
    fun failedPrepareReleasesPlayerAndAllowsRetry() = runBlocking {
        val corrupt = java.io.File(context.cacheDir, "corrupt-playback.bin")
            .apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }
            .also(files::add)
        val valid = createWav("playback-after-failure")
        val controller = AndroidAudioPlaybackController(context)

        val failure = controller.prepare(AudioSourceRef.LocalFile(corrupt))
        assertTrue(failure is SoundlyResult.Failure)
        assertTrue(controller.state.value is PlaybackState.Failed)

        assertTrue(controller.prepare(AudioSourceRef.LocalFile(valid)) is SoundlyResult.Success)
        assertTrue(controller.state.value is PlaybackState.Ready)
        controller.release()
    }

    private fun createWav(name: String): java.io.File = WavWriter(context.cacheDir).write(
        pcm = FloatArray(44_100) { 0.05f },
        sampleRate = 44_100,
        channels = 1,
        fileName = name
    ).also(files::add)
}
