package com.ideacode.soundly_sdk.core.audio.analyze

internal class DefaultAudioAnalyzer: AudioAnalyzer {

    override fun analyze(
        pcm: FloatArray,
        sampleRate: Int
    ): AudioFeatures {

        if (pcm.isEmpty() || sampleRate <= 0) return AudioFeatureAccumulator().finish()
        return AudioFeatureAccumulator().apply { addMono(pcm) }.finish()
    }

}
