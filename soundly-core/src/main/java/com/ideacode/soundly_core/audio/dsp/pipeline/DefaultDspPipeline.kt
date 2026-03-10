package com.ideacode.soundly_core.audio.dsp.pipeline

import com.ideacode.soundly_core.audio.dsp.model.DspPreset
import com.ideacode.soundly_core.audio.dsp.node.CompressorNode
import com.ideacode.soundly_core.audio.dsp.node.EqNode
import com.ideacode.soundly_core.audio.dsp.node.LimiterNode
import com.ideacode.soundly_core.audio.dsp.node.NoiseReductionNode


class DefaultDspPipeline: DspPipeline {

    private val noiseReductionNode = NoiseReductionNode()
    private val eqNode = EqNode()
    private val compressorNode = CompressorNode()
    private val limiterNote = LimiterNode()

    private val chain = listOf(
        noiseReductionNode,
        eqNode,
        compressorNode,
        limiterNote
    )

    override fun configure(preset: DspPreset) {
        noiseReductionNode.configure(preset.noiseReductionPreset)

        eqNode.configure(preset.eqPreset)

        compressorNode.configure(preset.compressorPreset)

        limiterNote.configure(preset.limiterPreset)
    }

    override fun process(input: FloatArray, sampleRate: Int): FloatArray {
        var buffer = input
        for (node in chain) {
            buffer = node.process(buffer, sampleRate)
        }

        return buffer
    }


}