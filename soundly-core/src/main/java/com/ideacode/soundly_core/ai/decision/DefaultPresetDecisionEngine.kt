package com.ideacode.soundly_core.ai.decision

import com.ideacode.soundly_core.audio.analyze.AudioFeatures
import com.ideacode.soundly_core.audio.dsp.model.DspPreset


class DefaultPresetDecisionEngine: PresetDecisionEngine {

    /**
     * 根据音频特征选择策略
     */
    override fun decide(features: AudioFeatures): DspPreset {
        if (!features.isValid) {
            return DspPreset.default()
        }

        return when {
            features.speechConfidence > 0.75f ->
                DspPreset.voiceStable()

            features.spectralFlatness > 0.4f ->
                DspPreset.naturalSmooth()

            else ->
                DspPreset.default()
        }
    }

}