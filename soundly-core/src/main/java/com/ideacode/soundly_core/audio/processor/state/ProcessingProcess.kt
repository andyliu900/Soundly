package com.ideacode.soundly_core.audio.processor.state

import com.ideacode.soundly_model.audio.processor.ProcessingStage

data class ProcessingProcess(
    val stage: ProcessingStage,
    val percent: Int = 0,
    val message: String = "",
    val wavFilePath: String = ""
)
