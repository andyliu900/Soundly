package com.ideacode.soundly.domain.mainflow

import com.ideacode.soundly_sdk.api.model.audio.dsp.PresetOption

/** Stable product choices. Presets define processing, not a guarantee about the result. */
enum class EnhancementMode(val preset: PresetOption) {
    AUTO(PresetOption.AI),
    CLEAR_SPEECH(PresetOption.SPEECH_FOCUS),
    NOISE_REDUCTION(PresetOption.DEFAULT),
    NATURAL(PresetOption.NATURAL_SMOOTH);

    companion object {
        val default: EnhancementMode = AUTO
    }
}
