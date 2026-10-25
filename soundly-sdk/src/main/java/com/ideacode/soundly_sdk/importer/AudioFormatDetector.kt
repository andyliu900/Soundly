package com.ideacode.soundly_sdk.importer

import com.ideacode.soundly_sdk.api.model.domain.AudioFormat

internal object AudioFormatDetector {

    fun detect(mimeType: String, displayName: String?): AudioFormat? {
        val normalizedMime = mimeType.lowercase()
        val extensionFormat = formatFromDisplayName(displayName)
        return when {
            normalizedMime in WAV_MIME_TYPES -> AudioFormat.WAV
            normalizedMime in M4A_MIME_TYPES -> AudioFormat.M4A
            normalizedMime in AAC_MIME_TYPES -> AudioFormat.AAC
            normalizedMime in MP3_MIME_TYPES -> AudioFormat.MP3
            else -> extensionFormat
        }
    }

    private fun formatFromDisplayName(displayName: String?): AudioFormat? {
        val normalizedName = displayName
            ?.lowercase()
            ?.removeSuffix(RECOVERY_FILE_SUFFIX)
        return when (normalizedName?.substringAfterLast('.', "")) {
            "wav", "wave" -> AudioFormat.WAV
            "m4a", "mp4" -> AudioFormat.M4A
            "aac" -> AudioFormat.AAC
            "mp3" -> AudioFormat.MP3
            else -> null
        }
    }

    fun canonicalMimeType(format: AudioFormat, detectedMime: String): String =
        when (format) {
            AudioFormat.WAV -> "audio/wav"
            AudioFormat.M4A -> "audio/mp4"
            AudioFormat.AAC -> "audio/aac"
            AudioFormat.MP3 -> "audio/mpeg"
            AudioFormat.UNKNOWN -> detectedMime
        }

    private val WAV_MIME_TYPES = setOf(
        "audio/wav",
        "audio/x-wav",
        "audio/wave",
        "audio/vnd.wave"
    )
    private val M4A_MIME_TYPES = setOf("audio/mp4", "audio/m4a", "audio/x-m4a")
    private val AAC_MIME_TYPES = setOf("audio/aac", "audio/aacp", "audio/x-aac")
    private val MP3_MIME_TYPES = setOf("audio/mpeg", "audio/mp3", "audio/x-mp3")
    private const val RECOVERY_FILE_SUFFIX = ".partial"
}
