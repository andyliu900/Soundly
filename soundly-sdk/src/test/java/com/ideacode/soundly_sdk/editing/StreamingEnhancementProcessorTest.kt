package com.ideacode.soundly_sdk.editing

import com.ideacode.soundly_sdk.api.model.audio.dsp.PresetOption
import com.ideacode.soundly_sdk.api.model.enhancement.EnhancementStage
import com.ideacode.soundly_sdk.core.audio.decode.PcmChunk
import com.ideacode.soundly_sdk.core.audio.decode.PcmStreamDecoder
import com.ideacode.soundly_sdk.core.audio.decode.PcmStreamResult
import com.ideacode.soundly_sdk.internal.SoundlyDispatchers
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingEnhancementProcessorTest {
    @Test
    fun thirtyMinuteVirtualStreamUsesBoundedChunksAndMonotonicProgress() =
        verifyVirtualDuration(minutes = 30)

    @Test
    fun twoHourVirtualStreamUsesBoundedChunksAnd64BitFrameAccounting() =
        verifyVirtualDuration(minutes = 120)

    private fun verifyVirtualDuration(minutes: Int) = runBlocking {
        val decoder = VirtualDurationDecoder(minutes)
        val sink = CountingSink()
        val progress = mutableListOf<StreamingEnhancementProgress>()
        val processor = AndroidStreamingEnhancementProcessor(
            decoderFactory = { decoder },
            writerFactory = { _, _, _ -> sink },
            dispatchers = SoundlyDispatchers(Dispatchers.Unconfined, Dispatchers.Unconfined)
        )

        val result = processor.process(
            source = File("virtual-$minutes-minutes.wav"),
            destination = File("unused-output.wav"),
            preset = PresetOption.AI,
            onProgress = progress::add
        )

        assertEquals(minutes * 60_000L, result.durationMs)
        assertEquals(minutes * 60L * SAMPLE_RATE, result.pcmFrames)
        assertEquals(2, decoder.decodeCalls)
        assertTrue(decoder.maxChunkSamples <= CHUNK_FRAMES * CHANNELS)
        assertTrue(sink.maxWriteSamples <= CHUNK_FRAMES * CHANNELS)
        assertTrue(progress.zipWithNext().all { (left, right) -> left.percent <= right.percent })
        assertEquals(
            listOf(
                EnhancementStage.LOADING,
                EnhancementStage.ANALYZING,
                EnhancementStage.RECOMMENDING,
                EnhancementStage.PROCESSING,
                EnhancementStage.WRITING
            ),
            progress.map { it.stage }.distinct()
        )
    }

    private class VirtualDurationDecoder(private val minutes: Int) : PcmStreamDecoder {
        var decodeCalls = 0
        var maxChunkSamples = 0

        override suspend fun decode(
            source: File,
            onChunk: (PcmChunk) -> Unit
        ): PcmStreamResult {
            decodeCalls++
            val chunks = minutes.coerceAtLeast(1)
            repeat(chunks) { chunkIndex ->
                val samples = FloatArray(CHUNK_FRAMES * CHANNELS) { sampleIndex ->
                    if ((sampleIndex + chunkIndex) % 31 < 15) 0.08f else -0.08f
                }
                maxChunkSamples = maxOf(maxChunkSamples, samples.size)
                onChunk(
                    PcmChunk(
                        samples = samples,
                        sampleRate = SAMPLE_RATE.toInt(),
                        channels = CHANNELS,
                        progress = (chunkIndex + 1).toFloat() / chunks
                    )
                )
            }
            val frames = minutes * 60L * SAMPLE_RATE
            return PcmStreamResult(
                sampleRate = SAMPLE_RATE.toInt(),
                channels = CHANNELS,
                frames = frames,
                durationMs = minutes * 60_000L
            )
        }
    }

    private class CountingSink : PcmWavSink {
        var maxWriteSamples = 0
        private var samplesWritten = 0L

        override fun write(samples: FloatArray) {
            maxWriteSamples = maxOf(maxWriteSamples, samples.size)
            samplesWritten += samples.size
        }

        override fun finish(): Long = samplesWritten * Short.SIZE_BYTES
        override fun close() = Unit
    }

    private companion object {
        const val SAMPLE_RATE = 44_100L
        const val CHANNELS = 2
        const val CHUNK_FRAMES = 4_096
    }
}
