package com.ideacode.soundly_sdk.api.model.audio.processor

import com.ideacode.soundly_sdk.api.model.audio.AudioSourceRef
import com.ideacode.soundly_sdk.api.model.audio.dsp.PresetOption

data class ProcessingRequest(
    val source: AudioSourceRef,
    val presetOption: PresetOption,
    val outputFileName: String
)

data class ProcessingProgress(
    val stage: ProcessingStage,
    val percent: Int = 0
)

data class ProcessingResult(
    val outputFilePath: String,
    /** SDK 2 project ID. The legacy property name is retained for binary/source compatibility. */
    val historyId: String?,
    val presetOption: PresetOption,
    val sampleRate: Int,
    val channels: Int,
    val durationMs: Long
)
