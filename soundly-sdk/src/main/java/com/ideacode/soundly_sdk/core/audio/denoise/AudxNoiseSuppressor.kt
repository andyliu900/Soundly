package com.ideacode.soundly_sdk.core.audio.denoise

import com.audx.android.Audx
import kotlin.math.roundToInt

/** Creates one stateful noise-suppression session for an enhancement job. */
internal fun interface NoiseSuppressorSessionFactory {
    fun create(sampleRate: Int, channels: Int): NoiseSuppressorSession
}

/**
 * Accepts arbitrary, interleaved Float PCM chunks while preserving RNNoise state across chunks.
 * Implementations may buffer input and must return the remaining samples from [flush].
 */
internal interface NoiseSuppressorSession : AutoCloseable {
    fun process(input: FloatArray): FloatArray
    fun flush(): FloatArray
}

internal fun interface Pcm16NoiseProcessorFactory {
    fun create(sampleRate: Int): Pcm16NoiseProcessor
}

internal interface Pcm16NoiseProcessor : AutoCloseable {
    fun process(input: ShortArray, output: ShortArray)
}

internal object AudxNoiseSuppressorSessionFactory : NoiseSuppressorSessionFactory {
    override fun create(sampleRate: Int, channels: Int): NoiseSuppressorSession =
        AudxNoiseSuppressorSession(sampleRate, channels)
}

/**
 * PCM adapter for Audx/RNNoise. Audx processes mono PCM16 in exact 10 ms frames, whereas the
 * MediaCodec decoder produces arbitrary interleaved Float PCM chunks. One Audx state is retained
 * per channel so imported stereo audio keeps its channel layout and duration.
 */
internal class AudxNoiseSuppressorSession(
    private val sampleRate: Int,
    private val channels: Int,
    processorFactory: Pcm16NoiseProcessorFactory = AudxPcm16NoiseProcessorFactory
) : NoiseSuppressorSession {

    private val frameSamples = (sampleRate / FRAMES_PER_SECOND).coerceAtLeast(1)
    private val processors = createProcessors(sampleRate, channels, processorFactory)
    private val inputFrames = Array(channels) { ShortArray(frameSamples) }
    private val outputFrames = Array(channels) { ShortArray(frameSamples) }
    private var bufferedFrames = 0
    private var closed = false

    init {
        require(sampleRate > 0) { "Sample rate must be positive" }
        require(channels > 0) { "Channel count must be positive" }
    }

    override fun process(input: FloatArray): FloatArray {
        check(!closed) { "Noise suppressor is closed" }
        require(input.size % channels == 0) { "PCM chunk does not contain complete frames" }
        if (input.isEmpty()) return input

        val inputFrameCount = input.size / channels
        val completedFrameCount =
            ((bufferedFrames + inputFrameCount) / frameSamples) * frameSamples
        if (completedFrameCount == 0) {
            appendInput(input)
            return FloatArray(0)
        }

        val output = FloatArray(completedFrameCount * channels)
        var inputFrame = 0
        var outputFrame = 0
        while (inputFrame < inputFrameCount) {
            val framesToCopy = minOf(frameSamples - bufferedFrames, inputFrameCount - inputFrame)
            copyInputFrames(input, inputFrame, framesToCopy)
            inputFrame += framesToCopy
            if (bufferedFrames == frameSamples) {
                processBufferedFrame(output, outputFrame)
                outputFrame += frameSamples
            }
        }
        check(outputFrame == completedFrameCount) { "Unexpected RNNoise frame accounting" }
        return output
    }

    override fun flush(): FloatArray {
        check(!closed) { "Noise suppressor is closed" }
        if (bufferedFrames == 0) return FloatArray(0)

        val validFrames = bufferedFrames
        inputFrames.forEach { channel -> channel.fill(0, validFrames, frameSamples) }
        val fullFrame = FloatArray(frameSamples * channels)
        bufferedFrames = frameSamples
        processBufferedFrame(fullFrame, 0)
        return fullFrame.copyOf(validFrames * channels)
    }

    override fun close() {
        if (closed) return
        closed = true
        processors.forEach { processor -> runCatching { processor.close() } }
    }

    private fun appendInput(input: FloatArray) {
        copyInputFrames(input, sourceFrameOffset = 0, frameCount = input.size / channels)
    }

    private fun copyInputFrames(
        input: FloatArray,
        sourceFrameOffset: Int,
        frameCount: Int
    ) {
        repeat(frameCount) { relativeFrame ->
            val sourceOffset = (sourceFrameOffset + relativeFrame) * channels
            for (channel in 0 until channels) {
                inputFrames[channel][bufferedFrames + relativeFrame] =
                    input[sourceOffset + channel].toPcm16()
            }
        }
        bufferedFrames += frameCount
    }

    private fun processBufferedFrame(output: FloatArray, destinationFrameOffset: Int) {
        check(bufferedFrames == frameSamples) { "RNNoise requires a complete 10 ms frame" }
        for (channel in 0 until channels) {
            processors[channel].process(inputFrames[channel], outputFrames[channel])
        }
        repeat(frameSamples) { frame ->
            val destinationOffset = (destinationFrameOffset + frame) * channels
            for (channel in 0 until channels) {
                output[destinationOffset + channel] = outputFrames[channel][frame] / PCM16_SCALE
            }
        }
        bufferedFrames = 0
    }

    private fun Float.toPcm16(): Short =
        (coerceIn(-1f, 1f) * PCM16_SCALE)
            .roundToInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()

    private companion object {
        const val FRAMES_PER_SECOND = 100
        const val PCM16_SCALE = 32_768f

        fun createProcessors(
            sampleRate: Int,
            channels: Int,
            processorFactory: Pcm16NoiseProcessorFactory
        ): List<Pcm16NoiseProcessor> {
            require(sampleRate > 0) { "Sample rate must be positive" }
            require(channels > 0) { "Channel count must be positive" }
            val created = mutableListOf<Pcm16NoiseProcessor>()
            try {
                repeat(channels) {
                    created += processorFactory.create(sampleRate)
                }
                return created
            } catch (error: Throwable) {
                created.forEach { processor -> runCatching { processor.close() } }
                throw error
            }
        }
    }
}

private object AudxPcm16NoiseProcessorFactory : Pcm16NoiseProcessorFactory {
    override fun create(sampleRate: Int): Pcm16NoiseProcessor = AudxPcm16NoiseProcessor(sampleRate)
}

private class AudxPcm16NoiseProcessor(sampleRate: Int) : Pcm16NoiseProcessor {
    private val delegate = Audx.Builder()
        .inputRate(sampleRate)
        .resampleQuality(Audx.AUDX_RESAMPLER_QUALITY_DEFAULT)
        .build()

    override fun process(input: ShortArray, output: ShortArray) {
        delegate.process(input, output) { _ -> Unit }
    }

    override fun close() = delegate.close()
}
