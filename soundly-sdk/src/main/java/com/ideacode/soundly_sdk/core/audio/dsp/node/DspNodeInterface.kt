package com.ideacode.soundly_sdk.core.audio.dsp.node

internal interface DspNodeInterface<T> {

    /**
     * 配置参数
     */
    fun configure(preset: T?)

    /**
     * PCM 处理
     */
    fun process(
        input: FloatArray,
        sampleRate: Int,
        channels: Int = 1
    ): FloatArray


}
