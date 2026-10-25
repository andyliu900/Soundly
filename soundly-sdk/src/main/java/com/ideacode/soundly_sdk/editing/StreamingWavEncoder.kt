package com.ideacode.soundly_sdk.editing

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.ideacode.soundly_sdk.api.model.export.ExportProgress
import com.ideacode.soundly_sdk.api.model.export.ExportStage
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/** Streaming decoder used only when a compressed project version is exported as PCM WAV. */
internal class StreamingWavEncoder {

    suspend fun encode(
        source: File,
        destination: File,
        onProgress: (ExportProgress) -> Unit
    ): WavEncodingResult {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var codecStarted = false
        var output: RandomAccessFile? = null
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
            val durationUs = inputFormat.longOrDefault(MediaFormat.KEY_DURATION, 0L)

            codec = MediaCodec.createDecoderByType(mimeType)
            codec.configure(inputFormat, null, null, 0)
            codec.start()
            codecStarted = true

            destination.parentFile?.mkdirs()
            output = RandomAccessFile(destination, "rw").apply {
                setLength(0L)
                write(ByteArray(WAV_HEADER_BYTES))
            }

            val bufferInfo = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var dataBytes = 0L
            var lastPercent = -1
            onProgress(ExportProgress(ExportStage.ENCODING, 0))

            while (!outputEnded) {
                coroutineContext.ensureActive()
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = requireNotNull(codec.getInputBuffer(inputIndex))
                        inputBuffer.clear()
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
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
                                sampleSize,
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
                        val outputBuffer = requireNotNull(codec.getOutputBuffer(outputIndex))
                        if (bufferInfo.size > 0 &&
                            bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                        ) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            dataBytes += writeAsPcm16(output, outputBuffer.slice(), pcmEncoding)
                            require(dataBytes <= MAX_WAV_DATA_BYTES) {
                                "WAV output exceeds the RIFF 4 GiB limit"
                            }
                            val percent = if (durationUs > 0L) {
                                ((bufferInfo.presentationTimeUs.coerceAtLeast(0L) * 70L) /
                                    durationUs).toInt().coerceIn(0, 70)
                            } else {
                                0
                            }
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(
                                    ExportProgress(
                                        stage = ExportStage.ENCODING,
                                        percent = percent,
                                        bytesWritten = dataBytes
                                    )
                                )
                            }
                        }
                        outputEnded = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }

            require(dataBytes > 0L) { "Decoder produced no PCM audio" }
            writeHeader(output, sampleRate, channels, dataBytes)
            output.fd.sync()
            return WavEncodingResult(sampleRate, channels, dataBytes + WAV_HEADER_BYTES)
        } finally {
            runCatching { output?.close() }
            if (codecStarted) runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun writeAsPcm16(
        output: RandomAccessFile,
        input: ByteBuffer,
        encoding: Int
    ): Long {
        input.order(ByteOrder.LITTLE_ENDIAN)
        return when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> {
                val bytes = ByteArray(input.remaining())
                input.get(bytes)
                output.write(bytes)
                bytes.size.toLong()
            }

            AudioFormat.ENCODING_PCM_FLOAT -> {
                var written = 0L
                while (input.remaining() >= Float.SIZE_BYTES) {
                    val sample = input.float.coerceIn(-1f, 1f)
                    writeLittleEndianShort(output, (sample * Short.MAX_VALUE).roundToInt())
                    written += Short.SIZE_BYTES
                }
                written
            }

            AudioFormat.ENCODING_PCM_8BIT -> {
                var written = 0L
                while (input.hasRemaining()) {
                    val sample = (input.get().toInt() and 0xff) - 128
                    writeLittleEndianShort(output, sample shl 8)
                    written += Short.SIZE_BYTES
                }
                written
            }

            else -> throw IllegalArgumentException("Unsupported decoder PCM encoding: $encoding")
        }
    }

    private fun writeHeader(
        output: RandomAccessFile,
        sampleRate: Int,
        channels: Int,
        dataBytes: Long
    ) {
        val blockAlign = channels * PCM_BYTES_PER_SAMPLE
        output.seek(0L)
        output.writeBytes("RIFF")
        writeLittleEndianInt(output, (36L + dataBytes).toInt())
        output.writeBytes("WAVE")
        output.writeBytes("fmt ")
        writeLittleEndianInt(output, 16)
        writeLittleEndianShort(output, 1)
        writeLittleEndianShort(output, channels)
        writeLittleEndianInt(output, sampleRate)
        writeLittleEndianInt(output, sampleRate * blockAlign)
        writeLittleEndianShort(output, blockAlign)
        writeLittleEndianShort(output, PCM_BITS_PER_SAMPLE)
        output.writeBytes("data")
        writeLittleEndianInt(output, dataBytes.toInt())
    }

    private fun writeLittleEndianInt(output: RandomAccessFile, value: Int) {
        output.write(value and 0xff)
        output.write(value ushr 8 and 0xff)
        output.write(value ushr 16 and 0xff)
        output.write(value ushr 24 and 0xff)
    }

    private fun writeLittleEndianShort(output: RandomAccessFile, value: Int) {
        output.write(value and 0xff)
        output.write(value ushr 8 and 0xff)
    }

    private fun MediaFormat.integerOrDefault(key: String, default: Int): Int =
        if (containsKey(key)) getInteger(key) else default

    private fun MediaFormat.longOrDefault(key: String, default: Long): Long =
        if (containsKey(key)) getLong(key) else default

    private companion object {
        const val DEQUEUE_TIMEOUT_US = 10_000L
        const val WAV_HEADER_BYTES = 44
        const val PCM_BYTES_PER_SAMPLE = 2
        const val PCM_BITS_PER_SAMPLE = 16
        const val MAX_WAV_DATA_BYTES = 0xffff_ffffL - 36L
    }
}

internal data class WavEncodingResult(
    val sampleRate: Int,
    val channels: Int,
    val fileSizeBytes: Long
)
