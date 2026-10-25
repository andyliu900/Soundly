package com.ideacode.soundly_sdk.editing

import android.content.Context
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ideacode.soundly_sdk.api.model.audio.dsp.PresetOption
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in device soak tests: -Pandroid.testInstrumentationRunnerArguments.soundlyLongAudio=true */
@RunWith(AndroidJUnit4::class)
class StreamingLongAudioStressTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun streamsThirtyMinutesWithBoundedMemory() = runStress(minutes = 30)

    @Test
    fun streamsTwoHoursWithBoundedMemory() = runStress(minutes = 120)

    private fun runStress(minutes: Int): Unit = runBlocking {
        assumeTrue(
            InstrumentationRegistry.getArguments().getString(ENABLE_ARGUMENT) == "true"
        )
        val source = File(context.cacheDir, "streaming-stress-${minutes}m-input.wav")
        val destination = File(context.cacheDir, "streaming-stress-${minutes}m-output.wav")
        createSparsePcm16Wav(source, minutes)
        val initialPssKb = Debug.getPss().toLong()
        val peakPssKb = AtomicLong(initialPssKb)
        val sampling = AtomicBoolean(true)
        val sampler = thread(name = "soundly-streaming-memory-sampler") {
            while (sampling.get()) {
                peakPssKb.accumulateAndGet(Debug.getPss().toLong(), ::maxOf)
                SystemClock.sleep(25L)
            }
        }
        val progress = mutableListOf<StreamingEnhancementProgress>()
        val startedAtMs = SystemClock.elapsedRealtime()
        try {
            val result = AndroidStreamingEnhancementProcessor().process(
                source = source,
                destination = destination,
                preset = PresetOption.DEFAULT,
                onProgress = progress::add
            )
            val expectedDurationMs = minutes * 60_000L
            assertTrue(result.durationMs in (expectedDurationMs - 200L)..(expectedDurationMs + 200L))
            assertEquals(minutes * 60L * SAMPLE_RATE, result.pcmFrames)
            assertTrue(destination.isFile && destination.length() > WAV_HEADER_BYTES)
            assertTrue(progress.zipWithNext().all { (left, right) -> left.percent <= right.percent })
            assertTrue(
                "PSS delta ${peakPssKb.get() - initialPssKb} KiB exceeded " +
                    "${MAX_PSS_GROWTH_KB / 1024} MiB",
                peakPssKb.get() - initialPssKb <= MAX_PSS_GROWTH_KB
            )
            Log.i(
                REPORT_TAG,
                "duration=${minutes}m elapsedMs=${SystemClock.elapsedRealtime() - startedAtMs} " +
                    "frames=${result.pcmFrames} initialPssKb=$initialPssKb " +
                    "peakPssKb=${peakPssKb.get()} pssDeltaKb=${peakPssKb.get() - initialPssKb} " +
                    "outputBytes=${destination.length()} progressEvents=${progress.size}"
            )
        } finally {
            sampling.set(false)
            sampler.join(2_000L)
            source.delete()
            destination.delete()
        }
    }

    private fun createSparsePcm16Wav(file: File, minutes: Int) {
        val frames = minutes * 60L * SAMPLE_RATE
        val dataBytes = frames * PCM_BYTES_PER_SAMPLE
        RandomAccessFile(file, "rw").use { output ->
            output.setLength(0L)
            output.writeBytes("RIFF")
            writeInt(output, (36L + dataBytes).toInt())
            output.writeBytes("WAVE")
            output.writeBytes("fmt ")
            writeInt(output, 16)
            writeShort(output, 1)
            writeShort(output, 1)
            writeInt(output, SAMPLE_RATE.toInt())
            writeInt(output, (SAMPLE_RATE * PCM_BYTES_PER_SAMPLE).toInt())
            writeShort(output, PCM_BYTES_PER_SAMPLE)
            writeShort(output, 16)
            output.writeBytes("data")
            writeInt(output, dataBytes.toInt())
            output.setLength(WAV_HEADER_BYTES + dataBytes)
        }
    }

    private fun writeInt(output: RandomAccessFile, value: Int) {
        output.write(value and 0xff)
        output.write(value ushr 8 and 0xff)
        output.write(value ushr 16 and 0xff)
        output.write(value ushr 24 and 0xff)
    }

    private fun writeShort(output: RandomAccessFile, value: Int) {
        output.write(value and 0xff)
        output.write(value ushr 8 and 0xff)
    }

    private companion object {
        const val ENABLE_ARGUMENT = "soundlyLongAudio"
        const val REPORT_TAG = "SoundlySoakReport"
        const val SAMPLE_RATE = 1_000L
        const val PCM_BYTES_PER_SAMPLE = 2
        const val WAV_HEADER_BYTES = 44L
        const val MAX_PSS_GROWTH_KB = 128L * 1024L
    }
}
