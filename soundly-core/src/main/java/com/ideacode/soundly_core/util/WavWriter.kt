package com.ideacode.soundly_core.util

import com.ideacode.soundly_core.util.FileUtils.floatToPcm16
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

class WavWriter(
    private val outputDir: File
) {

    fun write(
        pcm: FloatArray,
        sampleRate: Int,
        channels:Int,
        fileName: String
    ): File {

        Timber.i("Writing WAV sampleRate=$sampleRate, pcmSize=${pcm.size}")

        val durationSec = pcm.size.toFloat() / sampleRate
        Timber.i("PCM duration ~ ${durationSec}s")

        val pcm16 = floatToPcm16(pcm)
        val dataSize = pcm16.size * 2
        val bitsPerSample = 16
        val blockAlgin = channels * (bitsPerSample / 8)
        val byteRate = sampleRate * blockAlgin

        val file = File(outputDir, "$fileName.wav")

        FileOutputStream(file).use { out ->

            // RIFF header
            writeString(out, "RIFF")
            writeInt(out, 36 + dataSize)
            writeString(out, "WAVE")

            // fmt chunk
            writeString(out, "fmt ")
            writeInt(out, 16)
            writeShort(out, 1)              // PCM
            writeShort(out, channels.toShort())              // Mono
            writeInt(out, sampleRate)
            writeInt(out, byteRate)
            writeShort(out, blockAlgin.toShort())
            writeShort(out, bitsPerSample.toShort())

            // data chunk
            writeString(out, "data")
            writeInt(out, dataSize)

            // PCM data
            for (s in pcm16) {
                writeShort(out, s)
            }
        }

        return file
    }

    private fun writeInt(out: OutputStream, value: Int) {
        out.write(value and 0xff)
        out.write(value shr 8 and 0xff)
        out.write(value shr 16 and 0xff)
        out.write(value shr 24 and 0xff)
    }

    private fun writeShort(out: OutputStream, value: Short) {
        out.write(value.toInt() and 0xff)
        out.write(value.toInt() shr 8 and 0xff)
    }

    private fun writeString(out: OutputStream, value: String) {
        out.write(value.toByteArray(Charsets.US_ASCII))
    }
}