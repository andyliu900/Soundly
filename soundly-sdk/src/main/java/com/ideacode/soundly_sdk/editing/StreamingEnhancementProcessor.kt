package com.ideacode.soundly_sdk.editing

import com.ideacode.soundly_sdk.api.model.audio.dsp.PresetOption
import com.ideacode.soundly_sdk.api.model.enhancement.EnhancementStage
import com.ideacode.soundly_sdk.core.audio.analyze.AudioFeatureAccumulator
import com.ideacode.soundly_sdk.core.audio.decode.PcmStreamDecoder
import com.ideacode.soundly_sdk.core.audio.decode.StreamingPcmDecoder
import com.ideacode.soundly_sdk.core.audio.dsp.pipeline.DefaultDspPipeline
import com.ideacode.soundly_sdk.core.audio.dsp.pipeline.DspPipeline
import com.ideacode.soundly_sdk.internal.SoundlyDispatchers
import com.ideacode.soundly_sdk.preset.PresetFactory
import java.io.File
import kotlinx.coroutines.withContext

internal data class StreamingEnhancementProgress(
    val stage: EnhancementStage,
    /** Overall task work progress. Values emitted by the processor are monotonic. */
    val percent: Int
)

internal data class StreamingEnhancementResult(
    val sampleRate: Int,
    val channels: Int,
    val durationMs: Long,
    val pcmFrames: Long
)

internal interface StreamingEnhancementProcessor {
    suspend fun process(
        source: File,
        destination: File,
        preset: PresetOption,
        onProgress: (StreamingEnhancementProgress) -> Unit = {}
    ): StreamingEnhancementResult
}

/**
 * Two-pass constant-memory enhancement:
 * decode/analyze, then decode/stateful-DSP/incremental-WAV-write.
 */
internal class AndroidStreamingEnhancementProcessor(
    private val decoderFactory: () -> PcmStreamDecoder = { StreamingPcmDecoder() },
    private val pipelineFactory: () -> DspPipeline = { DefaultDspPipeline() },
    private val writerFactory: (File, Int, Int) -> PcmWavSink = { file, sampleRate, channels ->
        StreamingPcmWavWriter(file, sampleRate, channels)
    },
    private val dispatchers: SoundlyDispatchers = SoundlyDispatchers.Default
) : StreamingEnhancementProcessor {

    override suspend fun process(
        source: File,
        destination: File,
        preset: PresetOption,
        onProgress: (StreamingEnhancementProgress) -> Unit
    ): StreamingEnhancementResult = withContext(dispatchers.computation) {
        val progress = MonotonicProgress(onProgress)
        progress.emit(EnhancementStage.LOADING, LOADING_PERCENT)

        val accumulator = AudioFeatureAccumulator()
        val analysisResult = decoderFactory().decode(source) { chunk ->
            accumulator.addInterleaved(chunk.samples, chunk.channels)
            progress.emit(
                EnhancementStage.ANALYZING,
                ANALYSIS_START + (chunk.progress * ANALYSIS_RANGE).toInt()
            )
        }
        progress.emit(EnhancementStage.ANALYZING, ANALYSIS_END)

        val features = accumulator.finish()
        progress.emit(EnhancementStage.RECOMMENDING, RECOMMENDING_PERCENT)
        val dspPreset = PresetFactory.build(preset, features)
        val pipeline = pipelineFactory().apply { configure(dspPreset) }

        var writer: PcmWavSink? = null
        var outputSampleRate = 0
        var outputChannels = 0
        try {
            val processingResult = decoderFactory().decode(source) { chunk ->
                if (writer == null) {
                    outputSampleRate = chunk.sampleRate
                    outputChannels = chunk.channels
                    writer = writerFactory(destination, outputSampleRate, outputChannels)
                } else {
                    require(
                        chunk.sampleRate == outputSampleRate && chunk.channels == outputChannels
                    ) { "PCM format changed during decoding" }
                }
                val enhanced = pipeline.process(chunk.samples, chunk.sampleRate, chunk.channels)
                writer?.write(enhanced)
                progress.emit(
                    EnhancementStage.PROCESSING,
                    PROCESSING_START + (chunk.progress * PROCESSING_RANGE).toInt()
                )
            }
            progress.emit(EnhancementStage.WRITING, WRITING_PERCENT)
            requireNotNull(writer) { "Decoder produced no PCM audio" }.finish()
            progress.emit(EnhancementStage.WRITING, WRITING_COMPLETE_PERCENT)
            StreamingEnhancementResult(
                sampleRate = processingResult.sampleRate,
                channels = processingResult.channels,
                durationMs = processingResult.durationMs,
                pcmFrames = processingResult.frames
            )
        } finally {
            runCatching { writer?.close() }
        }
    }

    private class MonotonicProgress(
        private val callback: (StreamingEnhancementProgress) -> Unit
    ) {
        private var lastPercent = -1
        private var lastStage: EnhancementStage? = null

        fun emit(stage: EnhancementStage, percent: Int) {
            val normalized = percent.coerceIn(lastPercent.coerceAtLeast(0), 100)
            if (normalized == lastPercent && stage == lastStage) return
            lastPercent = normalized
            lastStage = stage
            callback(StreamingEnhancementProgress(stage, normalized))
        }
    }

    private companion object {
        const val LOADING_PERCENT = 1
        const val ANALYSIS_START = 2
        const val ANALYSIS_END = 40
        const val ANALYSIS_RANGE = ANALYSIS_END - ANALYSIS_START
        const val RECOMMENDING_PERCENT = 42
        const val PROCESSING_START = 43
        const val PROCESSING_RANGE = 49
        const val WRITING_PERCENT = 93
        const val WRITING_COMPLETE_PERCENT = 95
    }
}
