package com.ideacode.soundly_sdk.editing

import com.ideacode.soundly_sdk.api.error.SoundlyError
import com.ideacode.soundly_sdk.api.model.domain.AudioFormat
import com.ideacode.soundly_sdk.api.model.export.ExportQuality
import com.ideacode.soundly_sdk.api.result.SoundlyResult

internal data class ExportPlan(
    val operation: ExportOperation,
    val actualQuality: ExportQuality
)

internal enum class ExportOperation {
    COPY,
    ENCODE_PCM16_WAV
}

/** Pure policy layer that keeps format/quality promises independent from Android storage. */
internal object ExportQualityPolicy {

    fun resolve(
        sourceFormat: AudioFormat,
        targetFormat: AudioFormat,
        requestedQuality: ExportQuality,
        sourceIsPcm16Wav: Boolean
    ): SoundlyResult<ExportPlan> {
        val sameFormat = sourceFormat == targetFormat
        return when (requestedQuality) {
            ExportQuality.AUTOMATIC -> when {
                sameFormat -> copy(ExportQuality.PRESERVE_SOURCE)
                targetFormat == AudioFormat.WAV && sourceFormat in WAV_DECODE_INPUTS ->
                    encodePcm16()
                else -> unsupported(sourceFormat, targetFormat)
            }

            ExportQuality.PRESERVE_SOURCE -> if (sameFormat) {
                copy(ExportQuality.PRESERVE_SOURCE)
            } else {
                SoundlyResult.Failure(SoundlyError.InvalidRequest("quality"))
            }

            ExportQuality.PCM_16_BIT -> when {
                targetFormat != AudioFormat.WAV ->
                    SoundlyResult.Failure(SoundlyError.InvalidRequest("quality"))
                sourceFormat == AudioFormat.WAV && sourceIsPcm16Wav ->
                    copy(ExportQuality.PCM_16_BIT)
                sourceFormat == AudioFormat.WAV || sourceFormat in WAV_DECODE_INPUTS ->
                    encodePcm16()
                else -> unsupported(sourceFormat, targetFormat)
            }
        }
    }

    private fun copy(quality: ExportQuality) = SoundlyResult.Success(
        ExportPlan(ExportOperation.COPY, quality)
    )

    private fun encodePcm16() = SoundlyResult.Success(
        ExportPlan(ExportOperation.ENCODE_PCM16_WAV, ExportQuality.PCM_16_BIT)
    )

    private fun unsupported(
        source: AudioFormat,
        target: AudioFormat
    ): SoundlyResult.Failure = SoundlyResult.Failure(
        SoundlyError.UnsupportedFormat("${source.name}->${target.name}")
    )

    private val WAV_DECODE_INPUTS = setOf(AudioFormat.M4A, AudioFormat.AAC, AudioFormat.MP3)
}
