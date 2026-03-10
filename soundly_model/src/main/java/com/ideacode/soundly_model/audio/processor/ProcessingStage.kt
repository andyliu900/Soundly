package com.ideacode.soundly_model.audio.processor

enum class ProcessingStage {
    IDLE,
    LOADING,
    ANALYZING,
    DSP_PROCESSING,
    WRITING_WAV,
    COMPLETED,
    CANCELLED,
    ERROR
}