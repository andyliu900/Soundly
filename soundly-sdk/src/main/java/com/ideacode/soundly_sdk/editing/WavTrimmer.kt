package com.ideacode.soundly_sdk.editing

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

internal object WavTrimmer {

    fun trim(source: File, destination: File, startMs: Long, endMs: Long) {
        RandomAccessFile(source, "r").use { input ->
            val info = parse(input)
            require(info.audioFormat == PCM_FORMAT) { "Only PCM WAV files can be trimmed" }
            require(info.blockAlign > 0 && info.byteRate > 0) { "Invalid WAV format" }

            val startByte = ((startMs * info.byteRate / 1_000L) / info.blockAlign) *
                info.blockAlign
            val requestedEnd = ((endMs * info.byteRate / 1_000L) / info.blockAlign) *
                info.blockAlign
            val endByte = requestedEnd.coerceAtMost(info.dataSize)
            require(startByte in 0 until endByte) { "Trim range has no audio samples" }
            val outputDataSize = endByte - startByte
            require(outputDataSize <= Int.MAX_VALUE) { "Trimmed WAV is too large" }

            destination.parentFile?.mkdirs()
            FileOutputStream(destination).use { output ->
                writeHeader(output, info, outputDataSize.toInt())
                input.seek(info.dataOffset + startByte)
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var remaining = outputDataSize
                while (remaining > 0L) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    check(read > 0) { "Unexpected end of WAV data" }
                    output.write(buffer, 0, read)
                    remaining -= read
                }
                output.fd.sync()
            }
        }
    }

    private fun parse(input: RandomAccessFile): WavInfo {
        require(input.readFourCc() == "RIFF") { "Missing RIFF header" }
        input.readLittleEndianInt()
        require(input.readFourCc() == "WAVE") { "Missing WAVE header" }

        var audioFormat: Int? = null
        var channels: Int? = null
        var sampleRate: Int? = null
        var byteRate: Int? = null
        var blockAlign: Int? = null
        var bitsPerSample: Int? = null
        var dataOffset: Long? = null
        var dataSize: Long? = null

        while (input.filePointer + 8L <= input.length()) {
            val chunkId = input.readFourCc()
            val chunkSize = input.readLittleEndianInt().toLong() and 0xFFFF_FFFFL
            val chunkStart = input.filePointer
            when (chunkId) {
                "fmt " -> {
                    require(chunkSize >= 16L) { "Invalid WAV fmt chunk" }
                    audioFormat = input.readLittleEndianShort()
                    channels = input.readLittleEndianShort()
                    sampleRate = input.readLittleEndianInt()
                    byteRate = input.readLittleEndianInt()
                    blockAlign = input.readLittleEndianShort()
                    bitsPerSample = input.readLittleEndianShort()
                }

                "data" -> {
                    dataOffset = chunkStart
                    dataSize = minOf(chunkSize, input.length() - chunkStart)
                }
            }
            val paddedSize = chunkSize + (chunkSize and 1L)
            input.seek((chunkStart + paddedSize).coerceAtMost(input.length()))
            if (audioFormat != null && dataOffset != null) break
        }

        return WavInfo(
            audioFormat = requireNotNull(audioFormat),
            channels = requireNotNull(channels),
            sampleRate = requireNotNull(sampleRate),
            byteRate = requireNotNull(byteRate),
            blockAlign = requireNotNull(blockAlign),
            bitsPerSample = requireNotNull(bitsPerSample),
            dataOffset = requireNotNull(dataOffset),
            dataSize = requireNotNull(dataSize)
        )
    }

    private fun writeHeader(output: FileOutputStream, info: WavInfo, dataSize: Int) {
        output.writeAscii("RIFF")
        output.writeLittleEndianInt(36 + dataSize)
        output.writeAscii("WAVE")
        output.writeAscii("fmt ")
        output.writeLittleEndianInt(16)
        output.writeLittleEndianShort(info.audioFormat)
        output.writeLittleEndianShort(info.channels)
        output.writeLittleEndianInt(info.sampleRate)
        output.writeLittleEndianInt(info.byteRate)
        output.writeLittleEndianShort(info.blockAlign)
        output.writeLittleEndianShort(info.bitsPerSample)
        output.writeAscii("data")
        output.writeLittleEndianInt(dataSize)
    }

    private fun RandomAccessFile.readFourCc(): String =
        ByteArray(4).also(::readFully).toString(Charsets.US_ASCII)

    private fun RandomAccessFile.readLittleEndianShort(): Int =
        readUnsignedByte() or (readUnsignedByte() shl 8)

    private fun RandomAccessFile.readLittleEndianInt(): Int =
        readUnsignedByte() or
            (readUnsignedByte() shl 8) or
            (readUnsignedByte() shl 16) or
            (readUnsignedByte() shl 24)

    private fun FileOutputStream.writeAscii(value: String) = write(value.toByteArray(Charsets.US_ASCII))

    private fun FileOutputStream.writeLittleEndianShort(value: Int) {
        write(value and 0xFF)
        write(value ushr 8 and 0xFF)
    }

    private fun FileOutputStream.writeLittleEndianInt(value: Int) {
        write(value and 0xFF)
        write(value ushr 8 and 0xFF)
        write(value ushr 16 and 0xFF)
        write(value ushr 24 and 0xFF)
    }

    private data class WavInfo(
        val audioFormat: Int,
        val channels: Int,
        val sampleRate: Int,
        val byteRate: Int,
        val blockAlign: Int,
        val bitsPerSample: Int,
        val dataOffset: Long,
        val dataSize: Long
    )

    private const val PCM_FORMAT = 1
}
