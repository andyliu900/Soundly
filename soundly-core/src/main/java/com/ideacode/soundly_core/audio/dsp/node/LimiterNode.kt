package com.ideacode.soundly_core.audio.dsp.node

import com.ideacode.soundly_core.audio.dsp.model.LimiterPreset


class LimiterNode: DspNodeInterface<LimiterPreset> {

    private var preset: LimiterPreset? = null

    override fun configure(preset: LimiterPreset?) {
        this.preset = preset
    }

    override fun process(input: FloatArray, sampleRate: Int): FloatArray {
        val limit = preset?.limit ?: 0.95f

        return FloatArray(input.size) {
            input[it].coerceIn(-limit, limit)
        }
    }
}