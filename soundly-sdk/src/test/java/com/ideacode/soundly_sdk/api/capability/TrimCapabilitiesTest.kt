package com.ideacode.soundly_sdk.api.capability

import com.ideacode.soundly_sdk.api.model.domain.AudioFormat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrimCapabilitiesTest {
    @Test fun supportedFormatsMatchCurrentTrimmer() {
        assertTrue(TrimCapabilities.supports(AudioFormat.WAV))
        assertTrue(TrimCapabilities.supports(AudioFormat.M4A))
        for (format in listOf(AudioFormat.AAC, AudioFormat.MP3, AudioFormat.UNKNOWN)) {
            assertFalse(TrimCapabilities.supports(format))
        }
    }

    @Test fun rangeUsesSelectedVersionsDurationAndRejectsInvalidBoundaries() {
        assertTrue(TrimCapabilities.isValidRange(TrimRange(0, 1), 1))
        assertTrue(TrimCapabilities.isValidRange(TrimRange(1, 1000), 1000))
        for ((range, duration) in listOf(
            TrimRange(-1, 1) to 100L,
            TrimRange(0, 0) to 100L,
            TrimRange(2, 1) to 100L,
            TrimRange(99, 101) to 100L,
            TrimRange(0, 1) to 0L,
            TrimRange(Long.MAX_VALUE - 1, Long.MAX_VALUE) to 100L
        )) assertFalse(TrimCapabilities.isValidRange(range, duration))
    }
}
