package com.ideacode.soundly_sdk.core.audio.model

internal data class PcmData(
    val pcm: FloatArray,
    val sampleRate: Int,
    val channels: Int
)
