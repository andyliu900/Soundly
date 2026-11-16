package com.ideacode.soundly.feature.enhancement

import com.ideacode.soundly.R
import com.ideacode.soundly.domain.mainflow.EnhancementMode
import com.ideacode.soundly_sdk.api.model.audio.dsp.PresetOption
import com.ideacode.soundly_sdk.api.model.enhancement.EnhancementStage

object EnhancementUi {
    fun title(mode: EnhancementMode): Int = when (mode) {
        EnhancementMode.AUTO -> R.string.enhancement_mode_auto
        EnhancementMode.CLEAR_SPEECH -> R.string.enhancement_mode_speech
        EnhancementMode.NOISE_REDUCTION -> R.string.enhancement_mode_noise
        EnhancementMode.NATURAL -> R.string.enhancement_mode_natural
    }
    fun description(mode: EnhancementMode): Int = when (mode) {
        EnhancementMode.AUTO -> R.string.h6_auto_description
        EnhancementMode.CLEAR_SPEECH -> R.string.h6_speech_description
        EnhancementMode.NOISE_REDUCTION -> R.string.h6_noise_description
        EnhancementMode.NATURAL -> R.string.h6_natural_description
    }
    fun mode(preset: PresetOption): EnhancementMode? = EnhancementMode.entries.firstOrNull { it.preset == preset }
    fun stage(stage: EnhancementStage): Int = when (stage) {
        EnhancementStage.PREPARING -> R.string.h6_stage_preparing
        EnhancementStage.LOADING -> R.string.h6_stage_loading
        EnhancementStage.ANALYZING -> R.string.h6_stage_analyzing
        EnhancementStage.RECOMMENDING -> R.string.h6_stage_recommending
        EnhancementStage.PROCESSING -> R.string.h6_stage_processing
        EnhancementStage.WRITING -> R.string.h6_stage_writing
        EnhancementStage.SAVING -> R.string.h6_stage_saving
        else -> R.string.h6_stage_processing
    }
}
