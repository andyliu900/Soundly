package com.ideacode.soundly_core.audio.model

data class PcmData(
    val pcm: FloatArray,
    val sampleRate: Int,
    val channels: Int
)
