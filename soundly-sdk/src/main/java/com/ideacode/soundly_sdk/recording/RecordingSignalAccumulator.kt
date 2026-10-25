package com.ideacode.soundly_sdk.recording

import com.ideacode.soundly_sdk.api.model.recording.AudioMeter
import com.ideacode.soundly_sdk.api.model.recording.WaveformFrame
import kotlin.math.sqrt

/** Converts MediaRecorder amplitude polling into bounded public signal models. */
internal class RecordingSignalAccumulator(
    private val samplesPerFrame: Int = DEFAULT_SAMPLES_PER_FRAME
) {
    private val pendingAmplitudes = ArrayList<Float>(samplesPerFrame)
    private var frameStartedAtMs = 0L

    init {
        require(samplesPerFrame > 0)
    }

    fun sample(
        maxAmplitude: Int,
        positionMs: Long,
        measuredAtMs: Long
    ): RecordingSignalSample {
        val peak = maxAmplitude.toFloat().div(MAX_AMPLITUDE).coerceIn(0f, 1f)
        val meter = AudioMeter(
            peak = peak,
            rms = peak / SQRT_TWO,
            measuredAtMs = measuredAtMs
        )
        if (pendingAmplitudes.isEmpty()) frameStartedAtMs = positionMs
        pendingAmplitudes += peak
        val frame = if (pendingAmplitudes.size >= samplesPerFrame) {
            takeFrame(positionMs)
        } else {
            null
        }
        return RecordingSignalSample(meter, frame)
    }

    fun flush(positionMs: Long): WaveformFrame? =
        if (pendingAmplitudes.isEmpty()) null else takeFrame(positionMs)

    fun reset() {
        pendingAmplitudes.clear()
        frameStartedAtMs = 0L
    }

    private fun takeFrame(positionMs: Long): WaveformFrame = WaveformFrame(
        amplitudes = pendingAmplitudes.toList(),
        startPositionMs = frameStartedAtMs,
        endPositionMs = positionMs.coerceAtLeast(frameStartedAtMs)
    ).also {
        pendingAmplitudes.clear()
    }

    private companion object {
        const val DEFAULT_SAMPLES_PER_FRAME = 10
        const val MAX_AMPLITUDE = 32_767f
        val SQRT_TWO = sqrt(2f)
    }
}

internal data class RecordingSignalSample(
    val meter: AudioMeter,
    val waveformFrame: WaveformFrame?
)
