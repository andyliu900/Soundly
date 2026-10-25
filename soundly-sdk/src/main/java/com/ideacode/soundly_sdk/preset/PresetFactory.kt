package com.ideacode.soundly_sdk.preset

import com.ideacode.soundly_sdk.core.audio.analyze.AudioFeatures
import com.ideacode.soundly_sdk.core.audio.dsp.model.DspPreset
import com.ideacode.soundly_sdk.api.model.audio.dsp.PresetOption
import com.ideacode.soundly_sdk.core.ai.decision.DefaultPresetDecisionEngine
import timber.log.Timber

internal object PresetFactory {

    private val decisionEngine by lazy { DefaultPresetDecisionEngine() }
    
    fun build(
        option: PresetOption,
        features: AudioFeatures?
        ): DspPreset {

        Timber.i(option?.let { "buildPreset option_title: ${option.title}" } ?: "option is null")

        Timber.i("decisionEngine: $decisionEngine")

        return when (option) {
            PresetOption.RAW -> DspPreset.raw()
            PresetOption.DEFAULT -> DspPreset.default()
            PresetOption.VOICE_STABLE -> DspPreset.voiceStable()
            PresetOption.SPEECH_FOCUS -> DspPreset.speechFocus()
            PresetOption.NATURAL_SMOOTH -> DspPreset.naturalSmooth()
            PresetOption.PODCAST -> DspPreset.podcastInterview()
            PresetOption.MEETING -> DspPreset.meetingCall()
            PresetOption.MUSIC -> DspPreset.musicNoise()
            PresetOption.AI -> {
                features?.let { decisionEngine.decide(it) } ?: DspPreset.ai()
            }
        }
    }
}
