package com.ideacode.soundly_sdk.core.audio.decode

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.ideacode.soundly_sdk.core.audio.model.PcmData
import timber.log.Timber
import java.nio.ByteBuffer
import java.io.FileNotFoundException

internal class MediaCodecDecoder(
    private val context: Context
) {

    /** Android media source to mono or interleaved PCM float samples. */
    fun decode(uri: Uri): PcmData {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var codecStarted = false

        try {
            extractor.setDataSource(context, uri, null)

            val trackIndex = selectAudioTrack(extractor)
            require(trackIndex >= 0) { "No audio track found" }

            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME)) {
                "Audio MIME type is missing"
            }
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            Timber.i("mime=$mime, sampleRate=$sampleRate, channelCount=$channelCount")

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            codecStarted = true

            val pcmBuffer = ArrayList<Float>(1024 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()
            var inputEnded = false

            while (true) {
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = requireNotNull(codec.getInputBuffer(inputIndex))
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
                                extractor.sampleTime,
                                0
                            )
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
                when {
                    outputIndex >= 0 -> {
                        val outputBuffer = requireNotNull(codec.getOutputBuffer(outputIndex))
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        pcmBuffer.addAll(
                            decodeToFloatPcm(
                                buffer = outputBuffer.slice(),
                                size = bufferInfo.size
                            ).toList()
                        )
                        codec.releaseOutputBuffer(outputIndex, false)

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            break
                        }
                    }

                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                }
            }

            val decodedPcm = pcmBuffer.toFloatArray()
            val finalPcm = if (channelCount == 2) {
                stereoToMono(decodedPcm)
            } else {
                decodedPcm
            }

            return PcmData(
                pcm = finalPcm,
                sampleRate = sampleRate,
                channels = if (channelCount == 2) 1 else channelCount
            )
        } catch (error: SecurityException) {
            throw error
        } catch (error: FileNotFoundException) {
            throw error
        } catch (error: Exception) {
            throw AudioDecodingException(error)
        } finally {
            if (codecStarted) {
                runCatching { codec?.stop() }
            }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun selectAudioTrack(extractor: MediaExtractor): Int {
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("audio/") == true) {
                return index
            }
        }
        return -1
    }

    private fun decodeToFloatPcm(
        buffer: ByteBuffer,
        size: Int
    ): FloatArray {
        buffer.position(0)
        buffer.limit(size)

        val floatPcm = FloatArray(size / 2)
        var index = 0
        while (buffer.remaining() >= 2) {
            val low = buffer.get().toInt() and 0xff
            val high = buffer.get().toInt()
            val sample = (high shl 8) or low
            floatPcm[index++] = sample / 32768f
        }
        return if (index == floatPcm.size) floatPcm else floatPcm.copyOf(index)
    }

    private fun stereoToMono(stereo: FloatArray): FloatArray {
        val mono = FloatArray(stereo.size / 2)
        var outputIndex = 0
        var inputIndex = 0
        while (inputIndex < stereo.size - 1) {
            mono[outputIndex++] = (stereo[inputIndex] + stereo[inputIndex + 1]) * 0.5f
            inputIndex += 2
        }
        return mono
    }

    private companion object {
        const val DEQUEUE_TIMEOUT_US = 10_000L
    }
}
