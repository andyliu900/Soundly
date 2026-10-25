package com.ideacode.soundly_sdk.api.result

import com.ideacode.soundly_sdk.api.error.SoundlyError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundlyResultTest {

    @Test
    fun successExposesValue() {
        val result: SoundlyResult<Int> = SoundlyResult.Success(42)

        assertEquals(42, result.getOrNull())
        assertEquals("success:42", result.fold(
            onSuccess = { "success:$it" },
            onFailure = { "failure:${it.code}" }
        ))
    }

    @Test
    fun failureExposesStableErrorMetadata() {
        val result: SoundlyResult<Int> = SoundlyResult.Failure(
            SoundlyError.UnsupportedFormat(mimeType = "audio/example")
        )

        assertNull(result.getOrNull())
        val error = (result as SoundlyResult.Failure).error
        assertEquals("unsupported_format", error.code)
        assertTrue(!error.retryable)
    }
}

