package com.ideacode.soundly_sdk.core.audio.decode

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.ideacode.soundly_sdk.core.audio.model.PcmData
import timber.log.Timber
import java.nio.ByteBuffer

class MediaCodecDecoder(
    private val context: Context
) {

    /**
     * 主入口：Uri -> PCM
     */
    fun decode(uri: Uri): PcmData {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)

        val trackIndex = selectAudioTrack(extractor)
        require(trackIndex >= 0) {"No audio track found"}

        extractor.selectTrack(trackIndex)
        val format = extractor.getTrackFormat(trackIndex)

        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        Timber.i("mime=$mime, sampleRate=$sampleRate, channelCount=$channelCount")

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val pcmBuffer = ArrayList<Float>(1024 * 1024)

        val bufferInfo = MediaCodec.BufferInfo()
        var isEos = false

        while (true) {
            // ---------  输入  ----------
            if (!isEos) {
                val inputIndex = codec.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex)!!
                    val size = extractor.readSampleData(inputBuffer, 0)

                    if (size < 0) {
                        codec.queueInputBuffer(
                            inputIndex,
                            0,
                            0,
                            0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        isEos = true
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

            // --------  输出  ----------
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            when {
                outputIndex >= 0 -> {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)!!
                    val pcmChunk = decodeToFloatPcm(
                        buffer = outputBuffer,
                        size = bufferInfo.size,
                        channelCount = channelCount
                    )
                    pcmBuffer.addAll(pcmChunk.toList())
                    codec.releaseOutputBuffer(outputIndex, false)

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        break
                    }
                }

                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // k可读取新 format （例如 sampleRate 变化）
                }
            }

        }

        codec.stop()
        codec.release()
        extractor.release()

        // 重新计算 channel
        val finalPcm: FloatArray
        val finalChannel: Int

        if (channelCount == 2) {
            finalPcm = stereoToMono(pcmBuffer.toFloatArray())
            finalChannel = 1
        } else {
            finalPcm = pcmBuffer.toFloatArray()
            finalChannel = 1
        }

        return PcmData(
            pcm = finalPcm,
            sampleRate = sampleRate,
            channels = finalChannel
        )

    }

    /**
     * 选择音频轨道
     */
    private fun selectAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("audio/") == true) {
                return i
            }
        }

        return -1
    }

    /**
     * 将 MediaCodec 输出的 PCM(16bit) 转成 Float PCM
     */
    private fun decodeToFloatPcm(
        buffer: ByteBuffer,
        size: Int,
        channelCount: Int
    ): FloatArray {
        buffer.position(0)
        buffer.limit(size)

        val samples = size / 2 // 16bit
        val floatpcm = FloatArray(samples)

        var i = 0
        while (buffer.remaining() >= 2) {
            val low = buffer.get().toInt() and 0xff
            val high = buffer.get().toInt()
            val sample = (high shl 8) or low

            // 16bit signed -> Float
            floatpcm[i++] = (sample / 32768f)
        }

        // 如果是多声道，这里直接返回 interleaved PCM
        // 后续 DSP Pipeline 可自行 downmix
        return floatpcm
    }

    private fun stereoToMono(stereo: FloatArray): FloatArray {
        val mono = FloatArray(stereo.size / 2)
        var j = 0
        var i = 0
        while (i < stereo.size - 1) {
            mono[j++] = (stereo[i] + stereo[i + 1]) * 0.5f
            i += 2
        }
        return mono
    }

}