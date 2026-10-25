package com.ideacode.soundly_sdk.api.error

import com.ideacode.soundly_sdk.core.audio.decode.AudioDecodingException
import com.ideacode.soundly_sdk.api.exception.SoundlySdkException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException

class SoundlyErrorMapperTest {

    @Test
    fun mapsPermissionFailureToStableError() {
        val error = SoundlyErrorMapper.processing(SecurityException("denied"))

        assertEquals(SoundlyError.PermissionDenied, error)
        assertTrue(error.retryable)
    }

    @Test
    fun mapsMissingInputToFileNotFound() {
        val error = SoundlyErrorMapper.processing(FileNotFoundException("missing"))

        assertTrue(error is SoundlyError.FileNotFound)
        assertFalse(error.retryable)
        assertEquals("file_not_found", error.code)
    }

    @Test
    fun mapsUnexpectedFailureToProcessingFailed() {
        val cause = IllegalStateException("unexpected")
        val error = SoundlyErrorMapper.processing(cause)

        assertTrue(error is SoundlyError.ProcessingFailed)
        assertEquals(cause, error.cause)
    }

    @Test
    fun mapsDecoderBoundaryFailureToDecodeFailed() {
        val rootCause = IllegalStateException("codec details")
        val error = SoundlyErrorMapper.processing(AudioDecodingException(rootCause))

        assertTrue(error is SoundlyError.DecodeFailed)
        assertEquals("decode_failed", error.code)
        assertEquals(rootCause, error.cause)
    }

    @Test
    fun publicErrorTextNeverContainsUnderlyingExceptionMessage() {
        val secretMessage = "device-specific-private-message"
        val error = SoundlyError.ProcessingFailed(IllegalStateException(secretMessage))

        assertFalse(error.toString().contains(secretMessage))
        assertEquals(
            "SoundlyError(code=processing_failed, retryable=true)",
            error.toString()
        )

        val exception = SoundlySdkException(error)
        assertFalse(exception.toString().contains(secretMessage))
        assertEquals(null, exception.cause)
    }
}
