package com.ideacode.soundly_sdk.editing

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.roundToInt

internal interface PcmWavSink : Closeable {
    fun write(samples: FloatArray)
    fun finish(): Long
}

/** Incremental PCM16 WAV writer with header finalization and fsync. */
internal class StreamingPcmWavWriter(
    destination: File,
    private val sampleRate: Int,
    private val channels: Int
) : PcmWavSink {
    private val output: RandomAccessFile
    private var dataBytes = 0L
    private var finished = false

    init {
        require(sampleRate > 0 && channels > 0) { "Invalid WAV format" }
        destination.parentFile?.mkdirs()
        output = RandomAccessFile(destination, "rw").apply {
            setLength(0L)
            write(ByteArray(WAV_HEADER_BYTES))
        }
    }

    override fun write(samples: FloatArray) {
        check(!finished) { "WAV writer is already finalized" }
        require(samples.size % channels == 0) { "PCM chunk does not contain complete frames" }
        val bytes = ByteArray(samples.size * PCM_BYTES_PER_SAMPLE)
        var offset = 0
        samples.forEach { sample ->
            val value = (sample.coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt()
            bytes[offset++] = (value and 0xff).toByte()
            bytes[offset++] = (value ushr 8 and 0xff).toByte()
        }
        require(dataBytes + bytes.size <= MAX_WAV_DATA_BYTES) { "WAV output exceeds RIFF limit" }
        output.write(bytes)
        dataBytes += bytes.size
    }

    override fun finish(): Long {
        if (finished) return dataBytes
        require(dataBytes > 0L) { "No PCM data was written" }
        writeHeader()
        output.fd.sync()
        finished = true
        return dataBytes
    }

    override fun close() {
        output.close()
    }

    private fun writeHeader() {
        val blockAlign = channels * PCM_BYTES_PER_SAMPLE
        output.seek(0L)
        output.writeBytes("RIFF")
        writeInt((36L + dataBytes).toInt())
        output.writeBytes("WAVE")
        output.writeBytes("fmt ")
        writeInt(16)
        writeShort(1)
        writeShort(channels)
        writeInt(sampleRate)
        writeInt(sampleRate * blockAlign)
        writeShort(blockAlign)
        writeShort(PCM_BITS_PER_SAMPLE)
        output.writeBytes("data")
        writeInt(dataBytes.toInt())
    }

    private fun writeInt(value: Int) {
        output.write(value and 0xff)
        output.write(value ushr 8 and 0xff)
        output.write(value ushr 16 and 0xff)
        output.write(value ushr 24 and 0xff)
    }

    private fun writeShort(value: Int) {
        output.write(value and 0xff)
        output.write(value ushr 8 and 0xff)
    }

    private companion object {
        const val WAV_HEADER_BYTES = 44
        const val PCM_BYTES_PER_SAMPLE = 2
        const val PCM_BITS_PER_SAMPLE = 16
        const val MAX_WAV_DATA_BYTES = 0xffff_ffffL - 36L
    }
}
