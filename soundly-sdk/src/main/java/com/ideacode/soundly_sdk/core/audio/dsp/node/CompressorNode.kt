package com.ideacode.soundly_sdk.core.audio.dsp.node

import com.ideacode.soundly_sdk.core.audio.dsp.model.CompressorPreset


class CompressorNode: DspNodeInterface<CompressorPreset> {

    private var preset: CompressorPreset? = null

    override fun configure(preset: CompressorPreset?) {
        this.preset = preset
    }

    override fun process(input: FloatArray, sampleRate: Int): FloatArray {
        val p = preset ?: return input

        val threshold = p.threshold ?: 0.7f
        val ratio = p.ratio ?: 1.8f

        val out = FloatArray(input.size)

        for (i in input.indices) {
            val s = input[i]
            val abs = kotlin.math.abs(s)

            out[i] =
                if (abs > threshold) {
                    val excess = abs - threshold
                    (threshold + excess / ratio) * kotlin.math.sign(s)
                } else s
        }
        return out
    }

}