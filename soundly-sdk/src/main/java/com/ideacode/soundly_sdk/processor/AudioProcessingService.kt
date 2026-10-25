package com.ideacode.soundly_sdk.processor

import com.ideacode.soundly_sdk.api.model.audio.processor.ProcessingProgress
import com.ideacode.soundly_sdk.api.model.audio.processor.ProcessingRequest
import com.ideacode.soundly_sdk.api.model.audio.processor.ProcessingResult
import com.ideacode.soundly_sdk.api.result.SoundlyResult

interface AudioProcessingService {
    suspend fun process(
        request: ProcessingRequest,
        onProgress: (ProcessingProgress) -> Unit = {}
    ): SoundlyResult<ProcessingResult>
}

