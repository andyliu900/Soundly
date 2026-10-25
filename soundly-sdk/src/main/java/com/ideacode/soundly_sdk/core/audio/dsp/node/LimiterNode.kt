package com.ideacode.soundly_sdk.core.audio.dsp.node

import com.ideacode.soundly_sdk.core.audio.dsp.model.LimiterPreset


internal class LimiterNode: DspNodeInterface<LimiterPreset> {

    private var preset: LimiterPreset? = null

    override fun configure(preset: LimiterPreset?) {
        this.preset = preset
    }

    override fun process(input: FloatArray, sampleRate: Int, channels: Int): FloatArray {
        val configuredPreset = preset ?: return input
        val limit = configuredPreset.limit ?: 1.0f

        return FloatArray(input.size) {
            input[it].coerceIn(-limit, limit)
        }
    }
}
