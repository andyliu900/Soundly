package com.ideacode.soundly_sdk.recording

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import com.ideacode.soundly_sdk.api.model.domain.AudioFormat
import com.ideacode.soundly_sdk.api.model.recording.RecordingConfig
import com.ideacode.soundly_sdk.api.model.recording.RecordingQuality
import java.io.File

internal interface RecorderEngine {
    fun prepare(config: RecordingConfig, outputFile: File)
    fun start()
    fun pause()
    fun resume()
    fun stop()
    fun release()
    fun maxAmplitude(): Int
    fun routedInputDeviceId(): Int? = null
    fun setOnErrorListener(listener: ((RecorderEngineFailure) -> Unit)?) = Unit
}

internal enum class RecorderEngineFailure {
    MICROPHONE_UNAVAILABLE,
    UNKNOWN
}

internal class MicrophoneUnavailableException(
    cause: Throwable? = null
) : IllegalStateException("Microphone is unavailable", cause)

internal class MediaRecorderEngine(
    private val context: Context
) : RecorderEngine {

    private var recorder: MediaRecorder? = null
    private var errorListener: ((RecorderEngineFailure) -> Unit)? = null

    override fun prepare(config: RecordingConfig, outputFile: File) {
        release()
        outputFile.parentFile?.mkdirs()
        val newRecorder = createRecorder()
        try {
            newRecorder.setOnErrorListener { _, what, _ ->
                val failure = if (what == MediaRecorder.MEDIA_ERROR_SERVER_DIED) {
                    RecorderEngineFailure.MICROPHONE_UNAVAILABLE
                } else {
                    RecorderEngineFailure.UNKNOWN
                }
                errorListener?.invoke(failure)
            }
            newRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            when (config.format) {
                AudioFormat.M4A -> {
                    newRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    newRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                }

                AudioFormat.AAC -> {
                    newRecorder.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
                    newRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                }

                else -> throw IllegalArgumentException(
                    "Recording format ${config.format} is not supported"
                )
            }
            newRecorder.setAudioSamplingRate(config.sampleRate)
            newRecorder.setAudioChannels(config.channels)
            newRecorder.setAudioEncodingBitRate(config.quality.bitRate(config.channels))
            newRecorder.setOutputFile(outputFile.absolutePath)
            newRecorder.prepare()
            recorder = newRecorder
        } catch (error: Exception) {
            runCatching { newRecorder.release() }
            throw error
        }
    }

    override fun start() {
        try {
            requireNotNull(recorder) { "Recorder is not prepared" }.start()
        } catch (error: RuntimeException) {
            throw MicrophoneUnavailableException(error)
        }
    }

    override fun pause() {
        requireNotNull(recorder) { "Recorder is not prepared" }.pause()
    }

    override fun resume() {
        requireNotNull(recorder) { "Recorder is not prepared" }.resume()
    }

    override fun stop() {
        requireNotNull(recorder) { "Recorder is not prepared" }.stop()
    }

    override fun release() {
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        recorder = null
    }

    override fun maxAmplitude(): Int = runCatching {
        recorder?.maxAmplitude ?: 0
    }.getOrDefault(0)

    override fun routedInputDeviceId(): Int? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        runCatching { recorder?.routedDevice?.id }.getOrNull()
    } else {
        null
    }

    override fun setOnErrorListener(listener: ((RecorderEngineFailure) -> Unit)?) {
        errorListener = listener
    }

    @Suppress("DEPRECATION")
    private fun createRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }

    private fun RecordingQuality.bitRate(channels: Int): Int = when (this) {
        RecordingQuality.STANDARD -> 64_000 * channels
        RecordingQuality.HIGH -> 128_000 * channels
    }
}
