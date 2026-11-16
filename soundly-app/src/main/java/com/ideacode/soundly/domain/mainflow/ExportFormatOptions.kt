package com.ideacode.soundly.domain.mainflow

import com.ideacode.soundly_sdk.api.model.domain.AudioFormat

/**
 * Choices for ExportQuality.AUTOMATIC. Mirrors the SDK's copy/PCM-WAV policy;
 * actual decoding and storage errors remain the exporter's responsibility.
 */
object ExportFormatOptions {
    fun availableFor(source: AudioFormat): List<AudioFormat> = when (source) {
        AudioFormat.WAV -> listOf(AudioFormat.WAV)
        AudioFormat.M4A, AudioFormat.AAC, AudioFormat.MP3 -> listOf(source, AudioFormat.WAV)
        AudioFormat.UNKNOWN -> emptyList()
    }

    /** The preference is used only if the selected source can actually be exported to it. */
    fun initialFormat(source: AudioFormat, preference: AudioFormat): AudioFormat? {
        val available = availableFor(source)
        return preference.takeIf { it in available } ?: available.firstOrNull()
    }
}
