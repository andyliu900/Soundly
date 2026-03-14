package com.ideacode.soundly_sdk.core.audio.analyze


interface AudioAnalyzer {

    /**
     * 分析 PCM 音频特征
     */
    fun analyze(
        pcm: FloatArray,
        sampleRate: Int
    ): AudioFeatures

}