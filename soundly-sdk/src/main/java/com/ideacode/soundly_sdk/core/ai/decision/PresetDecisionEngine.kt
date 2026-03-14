package com.ideacode.soundly_sdk.core.ai.decision

import com.ideacode.soundly_sdk.core.audio.analyze.AudioFeatures
import com.ideacode.soundly_sdk.core.audio.dsp.model.DspPreset


interface PresetDecisionEngine {

    /**
     * 根据音频特征，生成 DSP 预设
     */
    fun decide(features: AudioFeatures): DspPreset

}