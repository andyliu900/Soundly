package com.ideacode.sample_app.home

import com.ideacode.soundly_model.audio.processor.ProcessingStage

data class HomeUiState(
    val isProcessing: Boolean = false,
    val stage: ProcessingStage = ProcessingStage.IDLE,
    val wavFilePath: String = "",

    val process: Int = 0,
    val message: String = ""
)
