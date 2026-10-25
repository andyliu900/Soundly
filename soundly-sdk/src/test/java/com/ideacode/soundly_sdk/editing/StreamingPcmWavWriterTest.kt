package com.ideacode.soundly_sdk.editing

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingPcmWavWriterTest {
    @Test
    fun finishBackfillsHeaderWithAccumulatedDataSize() {
        val file = File.createTempFile("soundly-streaming-writer", ".wav")
        try {
            StreamingPcmWavWriter(file, 48_000, 2).use { writer ->
                writer.write(FloatArray(2_000) { 0.25f })
                writer.write(FloatArray(4_000) { -0.25f })
                assertEquals(12_000L, writer.finish())
            }
            val bytes = file.readBytes()
            assertEquals("RIFF", bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII))
            assertEquals("WAVE", bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII))
            assertEquals(12_000, littleEndianInt(bytes, 40))
            assertEquals(12_044L, file.length())
            assertTrue(bytes.drop(44).any { it.toInt() != 0 })
        } finally {
            file.delete()
        }
    }

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int = ByteBuffer
        .wrap(bytes, offset, Int.SIZE_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN)
        .int
}
