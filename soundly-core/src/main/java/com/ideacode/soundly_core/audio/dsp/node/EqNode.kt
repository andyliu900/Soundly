package com.ideacode.soundly_core.audio.dsp.node

import com.ideacode.soundly_core.audio.dsp.model.EqPreset


class EqNode: DspNodeInterface<EqPreset> {

    private var preset: EqPreset? = null

    override fun configure(preset: EqPreset?) {
        this.preset = preset
    }

    override fun process(input: FloatArray, sampleRate: Int): FloatArray {
        val p = preset ?: return input

        val low = p.lowGain ?: 1.0f
        val mid = p.midGain ?: 1.05f
        val high = p.highGain ?: 1.0f

        val gain = (low + mid + high) / 3f

        return FloatArray(input.size) {
            (input[it] * gain).coerceIn(-1f, 1f)
        }
    }


}