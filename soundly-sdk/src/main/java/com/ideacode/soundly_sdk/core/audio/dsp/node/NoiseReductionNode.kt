package com.ideacode.soundly_sdk.core.audio.dsp.node

import com.ideacode.soundly_sdk.core.audio.dsp.model.NoiseReductionPreset
import kotlin.math.abs

class NoiseReductionNode: DspNodeInterface<NoiseReductionPreset> {

    private var preset: NoiseReductionPreset? = null

    override fun configure(preset: NoiseReductionPreset?) {
        this.preset = preset
    }

    override fun process(input: FloatArray, sampleRate: Int): FloatArray {

        val p = preset ?: return input

        val strength = p.strength ?: 0.2f
        val noiseFloor = p.noiseFloor ?: 0.015f

        val output = FloatArray(input.size)

        for (i in input.indices) {
            val s = input[i]
            output[i] =
                if (abs(s) < noiseFloor) {
                    s * (1f - strength)
                } else {
                    s
                }
        }

        return output
    }
}