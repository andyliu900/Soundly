package com.ideacode.soundly_sdk.core.audio.decode

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

internal data class PcmChunk(
    val samples: FloatArray,
    val sampleRate: Int,
    val channels: Int,
    val progress: Float
)

internal data class PcmStreamResult(
    val sampleRate: Int,
    val channels: Int,
    val frames: Long,
    val durationMs: Long
)

internal interface PcmStreamDecoder {
    suspend fun decode(source: File, onChunk: (PcmChunk) -> Unit): PcmStreamResult
}

/** MediaExtractor/MediaCodec decoder that retains only one decoder output chunk. */
internal class StreamingPcmDecoder : PcmStreamDecoder {

    override suspend fun decode(source: File, onChunk: (PcmChunk) -> Unit): PcmStreamResult {
        require(source.isFile) { "Audio source does not exist" }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(source.absolutePath)
            val trackIndex = selectAudioTrack(extractor)
            require(trackIndex >= 0) { "No audio track found" }
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
            return if (mime == MediaFormat.MIMETYPE_AUDIO_RAW) {
                decodeRaw(extractor, format, onChunk)
            } else {
                decodeCompressed(extractor, format, mime, onChunk)
            }
        } catch (error: SecurityException) {
            throw error
        } catch (error: java.io.FileNotFoundException) {
            throw error
        } catch (error: Exception) {
            if (error is AudioDecodingException) throw error
            throw AudioDecodingException(error)
        } finally {
            runCatching { extractor.release() }
        }
    }

    private suspend fun decodeRaw(
        extractor: MediaExtractor,
        format: MediaFormat,
        onChunk: (PcmChunk) -> Unit
    ): PcmStreamResult {
        val sampleRate = format.integerOrDefault(MediaFormat.KEY_SAMPLE_RATE, DEFAULT_SAMPLE_RATE)
        val channels = format.integerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 1)
        val encoding = format.integerOrDefault(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        val durationUs = format.longOrDefault(MediaFormat.KEY_DURATION, 0L)
        val capacity = format.integerOrDefault(MediaFormat.KEY_MAX_INPUT_SIZE, DEFAULT_BUFFER_BYTES)
            .coerceIn(MIN_BUFFER_BYTES, MAX_BUFFER_BYTES)
        val buffer = ByteBuffer.allocateDirect(capacity)
        var frames = 0L
        var lastProgress = 0f
        while (true) {
            coroutineContext.ensureActive()
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            buffer.position(0)
            buffer.limit(size)
            val samples = decodePcm(buffer.slice(), encoding, channels)
            frames += samples.size / channels
            val progress = calculateProgress(extractor.sampleTime, frames, sampleRate, durationUs, lastProgress)
            lastProgress = progress
            onChunk(PcmChunk(samples, sampleRate, channels, progress))
            extractor.advance()
        }
        require(frames > 0L) { "Decoder produced no PCM audio" }
        return PcmStreamResult(sampleRate, channels, frames, frames * 1_000L / sampleRate)
    }

    private suspend fun decodeCompressed(
        extractor: MediaExtractor,
        inputFormat: MediaFormat,
        mime: String,
        onChunk: (PcmChunk) -> Unit
    ): PcmStreamResult {
        var codec: MediaCodec? = null
        var started = false
        try {
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()
            started = true
            val durationUs = inputFormat.longOrDefault(MediaFormat.KEY_DURATION, 0L)
            var sampleRate = inputFormat.integerOrDefault(MediaFormat.KEY_SAMPLE_RATE, DEFAULT_SAMPLE_RATE)
            var channels = inputFormat.integerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 1)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var inputEnded = false
            var outputEnded = false
            var frames = 0L
            var lastProgress = 0f
            val info = MediaCodec.BufferInfo()

            while (!outputEnded) {
                coroutineContext.ensureActive()
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val input = requireNotNull(codec.getInputBuffer(inputIndex))
                        input.clear()
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                size,
                                extractor.sampleTime.coerceAtLeast(0L),
                                extractor.sampleFlags
                            )
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = codec.outputFormat
                        sampleRate = outputFormat.integerOrDefault(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        channels = outputFormat.integerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, channels)
                        encoding = outputFormat.integerOrDefault(
                            MediaFormat.KEY_PCM_ENCODING,
                            AudioFormat.ENCODING_PCM_16BIT
                        )
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val output = requireNotNull(codec.getOutputBuffer(outputIndex))
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            val samples = decodePcm(output.slice(), encoding, channels)
                            frames += samples.size / channels
                            val progress = calculateProgress(
                                info.presentationTimeUs,
                                frames,
                                sampleRate,
                                durationUs,
                                lastProgress
                            )
                            lastProgress = progress
                            onChunk(PcmChunk(samples, sampleRate, channels, progress))
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
            require(frames > 0L) { "Decoder produced no PCM audio" }
            return PcmStreamResult(sampleRate, channels, frames, frames * 1_000L / sampleRate)
        } finally {
            if (started) runCatching { codec?.stop() }
            runCatching { codec?.release() }
        }
    }

    private fun decodePcm(buffer: ByteBuffer, encoding: Int, channels: Int): FloatArray {
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val bytesPerSample = when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            AudioFormat.ENCODING_PCM_32BIT,
            AudioFormat.ENCODING_PCM_FLOAT -> 4
            else -> throw IllegalArgumentException("Unsupported decoder PCM encoding")
        }
        require(buffer.remaining() % bytesPerSample == 0) { "Unaligned PCM decoder output" }
        val output = FloatArray(buffer.remaining() / bytesPerSample)
        for (index in output.indices) {
            output[index] = when (encoding) {
                AudioFormat.ENCODING_PCM_8BIT -> ((buffer.get().toInt() and 0xff) - 128) / 128f
                AudioFormat.ENCODING_PCM_16BIT -> buffer.short / 32768f
                AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                    val raw = (buffer.get().toInt() and 0xff) or
                        ((buffer.get().toInt() and 0xff) shl 8) or
                        (buffer.get().toInt() shl 16)
                    raw / 8_388_608f
                }
                AudioFormat.ENCODING_PCM_32BIT -> buffer.int / 2_147_483_648f
                AudioFormat.ENCODING_PCM_FLOAT -> buffer.float.coerceIn(-1f, 1f)
                else -> error("Encoding already validated")
            }
        }
        require(output.size % channels == 0) { "PCM chunk does not contain complete frames" }
        return output
    }

    private fun calculateProgress(
        presentationTimeUs: Long,
        frames: Long,
        sampleRate: Int,
        durationUs: Long,
        previous: Float
    ): Float {
        val elapsedUs = maxOf(presentationTimeUs, frames * 1_000_000L / sampleRate)
        val value = if (durationUs > 0L) elapsedUs.toDouble() / durationUs else previous.toDouble()
        return value.toFloat().coerceIn(previous, 1f)
    }

    private fun selectAudioTrack(extractor: MediaExtractor): Int =
        (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: -1

    private fun MediaFormat.integerOrDefault(key: String, default: Int): Int =
        if (containsKey(key)) getInteger(key) else default

    private fun MediaFormat.longOrDefault(key: String, default: Long): Long =
        if (containsKey(key)) getLong(key) else default

    private companion object {
        const val DEQUEUE_TIMEOUT_US = 10_000L
        const val DEFAULT_SAMPLE_RATE = 44_100
        const val DEFAULT_BUFFER_BYTES = 128 * 1024
        const val MIN_BUFFER_BYTES = 16 * 1024
        const val MAX_BUFFER_BYTES = 512 * 1024
    }
}
