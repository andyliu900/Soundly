package com.ideacode.soundly_sdk.recording

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.ideacode.soundly_sdk.api.model.recording.StaticWaveform
import com.ideacode.soundly_sdk.core.audio.decode.AudioDecodingException
import com.ideacode.soundly_sdk.storage.AudioFileStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

internal interface StaticWaveformGenerator {
    suspend fun generate(source: File, durationMs: Long): StaticWaveform
}

/**
 * Streaming decoder dedicated to waveform extraction. It retains only one
 * peak per interval and never materializes the complete PCM stream.
 */
internal class MediaCodecStaticWaveformGenerator(
    private val sampleIntervalMs: Int = DEFAULT_SAMPLE_INTERVAL_MS
) : StaticWaveformGenerator {

    init {
        require(sampleIntervalMs > 0)
    }

    override suspend fun generate(source: File, durationMs: Long): StaticWaveform {
        val effectiveSampleIntervalMs = maxOf(
            sampleIntervalMs,
            if (durationMs > 0L) {
                ((durationMs + MAX_GENERATED_SAMPLES - 1L) / MAX_GENERATED_SAMPLES)
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt()
            } else {
                sampleIntervalMs
            }
        )
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var codecStarted = false
        try {
            extractor.setDataSource(source.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: throw IllegalArgumentException("No audio track found")
            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mimeType = requireNotNull(inputFormat.getString(MediaFormat.KEY_MIME))
            var sampleRate = inputFormat.integerOrDefault(MediaFormat.KEY_SAMPLE_RATE, 44_100)
            var channels = inputFormat.integerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 1)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

            codec = MediaCodec.createDecoderByType(mimeType)
            codec.configure(inputFormat, null, null, 0)
            codec.start()
            codecStarted = true

            val peaks = ArrayList<Float>()
            var samplesInBucket = 0
            var bucketPeak = 0f
            var decodedSamples = 0L
            val bufferInfo = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false

            fun acceptSample(sample: Float) {
                bucketPeak = maxOf(bucketPeak, abs(sample).coerceIn(0f, 1f))
                samplesInBucket += 1
                decodedSamples += 1L
                val samplesPerBucket = (
                    sampleRate.toLong() * channels.coerceAtLeast(1) *
                        effectiveSampleIntervalMs / 1_000L
                ).coerceAtLeast(channels.coerceAtLeast(1).toLong())
                if (samplesInBucket.toLong() >= samplesPerBucket) {
                    peaks += bucketPeak
                    samplesInBucket = 0
                    bucketPeak = 0f
                }
            }

            while (!outputEnded) {
                coroutineContext.ensureActive()
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = requireNotNull(codec.getInputBuffer(inputIndex))
                        inputBuffer.clear()
                        val size = extractor.readSampleData(inputBuffer, 0)
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

                when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = codec.outputFormat
                        sampleRate = outputFormat.integerOrDefault(
                            MediaFormat.KEY_SAMPLE_RATE,
                            sampleRate
                        )
                        channels = outputFormat.integerOrDefault(
                            MediaFormat.KEY_CHANNEL_COUNT,
                            channels
                        )
                        pcmEncoding = outputFormat.integerOrDefault(
                            MediaFormat.KEY_PCM_ENCODING,
                            AudioFormat.ENCODING_PCM_16BIT
                        )
                    }

                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        try {
                            if (bufferInfo.size > 0 &&
                                bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                            ) {
                                val outputBuffer = requireNotNull(codec.getOutputBuffer(outputIndex))
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                consumePcm(outputBuffer.slice(), pcmEncoding, ::acceptSample)
                            }
                            outputEnded = bufferInfo.flags and
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally {
                            codec.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            }
            if (samplesInBucket > 0) peaks += bucketPeak
            require(peaks.isNotEmpty()) { "Decoder produced no waveform samples" }
            val decodedDurationMs = if (sampleRate > 0 && channels > 0) {
                decodedSamples * 1_000L / (sampleRate.toLong() * channels)
            } else {
                0L
            }
            return StaticWaveform(
                durationMs = durationMs.takeIf { it > 0L } ?: decodedDurationMs,
                sampleIntervalMs = effectiveSampleIntervalMs,
                amplitudes = peaks
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            throw if (error is AudioDecodingException) error else AudioDecodingException(error)
        } finally {
            if (codecStarted) runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun consumePcm(
        buffer: ByteBuffer,
        encoding: Int,
        consumer: (Float) -> Unit
    ) {
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> while (buffer.remaining() >= Short.SIZE_BYTES) {
                consumer(buffer.short / 32_768f)
            }

            AudioFormat.ENCODING_PCM_FLOAT -> while (buffer.remaining() >= Float.SIZE_BYTES) {
                consumer(buffer.float)
            }

            AudioFormat.ENCODING_PCM_8BIT -> while (buffer.hasRemaining()) {
                consumer(((buffer.get().toInt() and 0xff) - 128) / 128f)
            }

            else -> throw IllegalArgumentException("Unsupported decoder PCM encoding")
        }
    }

    private fun MediaFormat.integerOrDefault(key: String, default: Int): Int =
        if (containsKey(key)) getInteger(key) else default

    private companion object {
        const val DEFAULT_SAMPLE_INTERVAL_MS = 50
        const val DEQUEUE_TIMEOUT_US = 10_000L
        const val MAX_GENERATED_SAMPLES = 200_000L
    }
}

internal class StaticWaveformFileStore(
    private val fileStore: AudioFileStore
) {
    fun write(projectId: String, variantId: String, waveform: StaticWaveform): File =
        fileStore.copyAtomically(
            ByteArrayInputStream(StaticWaveformFileCodec.encode(waveform)),
            fileStore.waveformFile(projectId, variantId)
        )

    fun read(projectId: String, variantId: String): StaticWaveform =
        StaticWaveformFileCodec.decode(fileStore.waveformFile(projectId, variantId))

    fun delete(projectId: String, variantId: String) {
        val file = fileStore.waveformFile(projectId, variantId)
        if (file.exists()) fileStore.deleteFile(file)
    }
}

internal object StaticWaveformFileCodec {
    fun encode(waveform: StaticWaveform): ByteArray {
        require(waveform.durationMs >= 0L)
        require(waveform.sampleIntervalMs > 0)
        require(waveform.amplitudes.size <= MAX_SAMPLE_COUNT)
        require(waveform.amplitudes.all { it.isFinite() && it in 0f..1f })
        return ByteArrayOutputStream(HEADER_BYTES + waveform.amplitudes.size * Float.SIZE_BYTES)
            .use { bytes ->
                DataOutputStream(bytes).use { output ->
                    output.writeInt(MAGIC)
                    output.writeInt(VERSION)
                    output.writeLong(waveform.durationMs)
                    output.writeInt(waveform.sampleIntervalMs)
                    output.writeInt(waveform.amplitudes.size)
                    waveform.amplitudes.forEach(output::writeFloat)
                }
                bytes.toByteArray()
            }
    }

    fun decode(file: File): StaticWaveform {
        require(file.isFile) { "Waveform file does not exist" }
        return DataInputStream(file.inputStream().buffered()).use { input ->
            require(input.readInt() == MAGIC) { "Invalid waveform file" }
            require(input.readInt() == VERSION) { "Unsupported waveform version" }
            val durationMs = input.readLong()
            val sampleIntervalMs = input.readInt()
            val count = input.readInt()
            require(durationMs >= 0L && sampleIntervalMs > 0 && count in 1..MAX_SAMPLE_COUNT) {
                "Invalid waveform header"
            }
            val amplitudes = List(count) {
                input.readFloat().also { value ->
                    require(value.isFinite() && value in 0f..1f) {
                        "Invalid waveform amplitude"
                    }
                }
            }
            require(input.read() == -1) { "Unexpected waveform payload" }
            StaticWaveform(durationMs, sampleIntervalMs, amplitudes)
        }
    }

    private const val MAGIC = 0x534E4457
    private const val VERSION = 1
    private const val HEADER_BYTES = 24
    private const val MAX_SAMPLE_COUNT = 1_000_000
}
