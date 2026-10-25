package com.ideacode.soundly_sdk.processor

import com.ideacode.soundly_sdk.api.error.SoundlyError
import com.ideacode.soundly_sdk.api.model.audio.AudioSourceRef
import com.ideacode.soundly_sdk.api.model.audio.dsp.PresetOption
import com.ideacode.soundly_sdk.api.model.audio.processor.ProcessingRequest
import com.ideacode.soundly_sdk.api.result.SoundlyResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SdkAudioProcessorValidationTest {

    @Test
    fun blankOutputNameReturnsInvalidRequestWithoutStartingProcessing() = runBlocking {
        val processor = validationProcessor()

        val result = processor.process(
            request = ProcessingRequest(
                source = AudioSourceRef.LocalFile(File("unused.wav")),
                presetOption = PresetOption.DEFAULT,
                outputFileName = "   "
            )
        )

        assertTrue(result is SoundlyResult.Failure)
        val error = (result as SoundlyResult.Failure).error
        assertTrue(error is SoundlyError.InvalidRequest)
        assertEquals("outputFileName", (error as SoundlyError.InvalidRequest).field)
    }

    @Test
    fun missingLocalFileReturnsFileNotFound() = runBlocking {
        val missingFile = File("definitely-missing-soundly-test.wav")
        val processor = validationProcessor()

        val result = processor.process(
            request = ProcessingRequest(
                source = AudioSourceRef.LocalFile(missingFile),
                presetOption = PresetOption.DEFAULT,
                outputFileName = "output"
            )
        )

        assertTrue(result is SoundlyResult.Failure)
        val error = (result as SoundlyResult.Failure).error
        assertTrue(error is SoundlyError.FileNotFound)
        assertEquals(missingFile.absolutePath, (error as SoundlyError.FileNotFound).path)
    }

    @Test
    fun outputNameCannotEscapeSdkDirectory() = runBlocking {
        val processor = validationProcessor()

        val result = processor.process(
            ProcessingRequest(
                source = AudioSourceRef.LocalFile(File("unused.wav")),
                presetOption = PresetOption.DEFAULT,
                outputFileName = "../outside"
            )
        )

        assertTrue(result is SoundlyResult.Failure)
        assertTrue((result as SoundlyResult.Failure).error is SoundlyError.InvalidRequest)
    }

    private fun validationProcessor() = createAudioProcessingService(
        audioLoader = null,
        audioProcessor = null
    )
}
