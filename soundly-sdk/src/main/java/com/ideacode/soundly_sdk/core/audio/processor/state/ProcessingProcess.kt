package com.ideacode.soundly_sdk.core.audio.processor.state

import com.ideacode.soundly_sdk.api.model.audio.processor.ProcessingStage

data class ProcessingProcess(
    val stage: ProcessingStage,
    val percent: Int = 0,
    val message: String = "",
    val wavFilePath: String = ""
)
