package com.ideacode.soundly_sdk.baseline

import android.net.Uri
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ideacode.soundly_sdk.api.Soundly
import com.ideacode.soundly_sdk.api.model.audio.dsp.PresetOption
import com.ideacode.soundly_sdk.core.audio.processor.state.ProcessingProcess
import com.ideacode.soundly_sdk.processor.SdkAudioProcessor
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread

/**
 * Source-compatible probe compiled separately against the Git HEAD SDK and the
 * current working tree. It intentionally uses only the legacy API shared by both.
 */
@RunWith(AndroidJUnit4::class)
class SdkBaselineBenchmarkTest {

    @Test
    fun allFixtures() {
        listOf(
            "short_mono.wav",
            "silence_mono.wav",
            "stereo.wav",
            "five_minutes_mono.wav",
            "thirty_minutes_mono.wav",
            "corrupt_audio.wav"
        ).forEach { fixture ->
            Runtime.getRuntime().gc()
            SystemClock.sleep(150)
            runFixture(fixture)
        }
    }

    private fun runFixture(assetName: String) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixture = File(context.cacheDir, "baseline-input-$assetName")
        GZIPInputStream(instrumentation.context.assets.open("$assetName.fixture")).use { input ->
            fixture.outputStream().use(input::copyTo)
        }

        @Suppress("DEPRECATION")
        Soundly.init(context)
        val processor = SdkAudioProcessor()
        val startedAt = SystemClock.elapsedRealtime()
        val initialPssKb = Debug.getPss()
        val peakPssKb = AtomicLong(initialPssKb)
        val sampling = AtomicBoolean(true)
        val sampler = thread(name = "soundly-pss-sampler") {
            while (sampling.get()) {
                peakPssKb.accumulateAndGet(Debug.getPss()) { current, sample ->
                    maxOf(current, sample)
                }
                SystemClock.sleep(20)
            }
        }

        var outputPath: String? = null
        var outcome = "success"
        var failureType: String? = null
        var failureMessage: String? = null

        try {
            @Suppress("DEPRECATION")
            processor.process(
                uri = Uri.fromFile(fixture),
                presetOption = PresetOption.RAW,
                outputFileName = "baseline-${assetName.substringBeforeLast('.')}",
                onProcess = { process: ProcessingProcess ->
                    if (!process.wavFilePath.isNullOrBlank()) {
                        outputPath = process.wavFilePath
                    }
                }
            )
        } catch (error: Throwable) {
            outcome = "failure"
            failureType = error.javaClass.name
            failureMessage = error.message
        } finally {
            sampling.set(false)
            sampler.join(2_000)
        }

        val output = outputPath?.let(::File)?.takeIf(File::exists)
        val elapsedMs = SystemClock.elapsedRealtime() - startedAt
        val json = buildString {
            append('{')
            field("fixture", assetName)
            field("outcome", outcome)
            field("elapsed_ms", elapsedMs)
            field("initial_pss_kb", initialPssKb)
            field("peak_pss_kb", peakPssKb.get())
            field("pss_delta_kb", peakPssKb.get() - initialPssKb)
            field("input_size_bytes", fixture.length())
            nullableField("output_path", output?.absolutePath)
            nullableNumberField("output_size_bytes", output?.length())
            nullableField("output_sha256", output?.let(::sha256))
            nullableField("failure_type", failureType)
            nullableField("failure_message", failureMessage)
            if (last() == ',') deleteCharAt(lastIndex)
            append('}')
        }
        println("SOUNDLY_BASELINE_RESULT=$json")
    }

    private fun StringBuilder.field(name: String, value: String) {
        append('"').append(name).append("\":\"").append(escape(value)).append("\",")
    }

    private fun StringBuilder.field(name: String, value: Long) {
        append('"').append(name).append("\":").append(value).append(',')
    }

    private fun StringBuilder.nullableField(name: String, value: String?) {
        append('"').append(name).append("\":")
        if (value == null) append("null,") else append('"').append(escape(value)).append("\",")
    }

    private fun StringBuilder.nullableNumberField(name: String, value: Long?) {
        append('"').append(name).append("\":").append(value ?: "null").append(',')
    }

    private fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
