package com.ideacode.soundly_sdk.core.audio.model

data class PcmData(
    val pcm: FloatArray,
    val sampleRate: Int,
    val channels: Int
)
