package com.ideacode.soundly_sdk.api.capability

import com.ideacode.soundly_sdk.api.model.domain.AudioFormat

/** Conservative capabilities of the current non-destructive trimmer. */
object TrimCapabilities {
    /** Support for a container does not guarantee that every file in it can be decoded or trimmed. */
    fun supports(format: AudioFormat): Boolean = format == AudioFormat.WAV || format == AudioFormat.M4A

    /** Times are in milliseconds on the selected source version's timeline. */
    fun isValidRange(range: TrimRange, durationMs: Long): Boolean =
        durationMs > 0 && range.startMs >= 0 && range.startMs < range.endMs && range.endMs <= durationMs
}
