package com.ideacode.soundly_sdk.core.audio.dsp.pipeline

import com.ideacode.soundly_sdk.core.audio.dsp.model.DspPreset


interface DspPipeline {

    /**
     * 根据 AI 决策结果配置 Pipeline
     */
    fun configure(preset: DspPreset)

    /**
     * 执行 DSP 处理
     */
    fun process(
        input: FloatArray,
        sampleRate: Int
    ): FloatArray
}