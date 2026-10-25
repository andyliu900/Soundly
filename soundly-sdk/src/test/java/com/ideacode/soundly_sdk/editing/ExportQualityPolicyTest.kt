package com.ideacode.soundly_sdk.editing

import com.ideacode.soundly_sdk.api.error.SoundlyError
import com.ideacode.soundly_sdk.api.model.domain.AudioFormat
import com.ideacode.soundly_sdk.api.model.export.ExportQuality
import com.ideacode.soundly_sdk.api.result.SoundlyResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportQualityPolicyTest {

    @Test
    fun automaticReportsTheConcreteStrategyItSelects() {
        val copy = ExportQualityPolicy.resolve(
            AudioFormat.WAV,
            AudioFormat.WAV,
            ExportQuality.AUTOMATIC,
            sourceIsPcm16Wav = true
        ) as SoundlyResult.Success
        val decode = ExportQualityPolicy.resolve(
            AudioFormat.M4A,
            AudioFormat.WAV,
            ExportQuality.AUTOMATIC,
            sourceIsPcm16Wav = false
        ) as SoundlyResult.Success

        assertEquals(ExportOperation.COPY, copy.value.operation)
        assertEquals(ExportQuality.PRESERVE_SOURCE, copy.value.actualQuality)
        assertEquals(ExportOperation.ENCODE_PCM16_WAV, decode.value.operation)
        assertEquals(ExportQuality.PCM_16_BIT, decode.value.actualQuality)
    }

    @Test
    fun preserveSourceRejectsAFormatChange() {
        val result = ExportQualityPolicy.resolve(
            AudioFormat.M4A,
            AudioFormat.WAV,
            ExportQuality.PRESERVE_SOURCE,
            sourceIsPcm16Wav = false
        )

        assertTrue(result is SoundlyResult.Failure)
        assertEquals(
            "quality",
            ((result as SoundlyResult.Failure).error as SoundlyError.InvalidRequest).field
        )
    }

    @Test
    fun pcm16RejectsNonWavTargetAndCopiesKnownPcm16Wav() {
        val invalid = ExportQualityPolicy.resolve(
            AudioFormat.WAV,
            AudioFormat.M4A,
            ExportQuality.PCM_16_BIT,
            sourceIsPcm16Wav = true
        )
        val valid = ExportQualityPolicy.resolve(
            AudioFormat.WAV,
            AudioFormat.WAV,
            ExportQuality.PCM_16_BIT,
            sourceIsPcm16Wav = true
        ) as SoundlyResult.Success

        assertTrue(invalid is SoundlyResult.Failure)
        assertEquals(ExportOperation.COPY, valid.value.operation)
        assertEquals(ExportQuality.PCM_16_BIT, valid.value.actualQuality)
    }
}
