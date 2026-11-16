package com.ideacode.soundly.common

import com.ideacode.soundly.common.ui.DisplayFormat
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayFormatTest {
    @Test
    fun `duration handles negative minutes hours and long recordings`() {
        assertEquals("00:00", DisplayFormat.durationMillis(-1))
        assertEquals("59:59", DisplayFormat.durationMillis(3_599_999))
        assertEquals("01:00:00", DisplayFormat.durationMillis(3_600_000))
        assertEquals("00:03:28", DisplayFormat.durationMillis(208_000, alwaysShowHours = true))
        assertEquals("100:00:00", DisplayFormat.durationMillis(360_000_000))
    }

    @Test
    fun `byte size is bounded and uses requested locale`() {
        assertEquals("0 B", DisplayFormat.byteSize(-1, Locale.US))
        assertEquals("1023 B", DisplayFormat.byteSize(1023, Locale.US))
        assertEquals("1.0 KB", DisplayFormat.byteSize(1024, Locale.US))
        assertEquals("1.5 MB", DisplayFormat.byteSize(1_572_864, Locale.US))
        assertEquals("1,5 MB", DisplayFormat.byteSize(1_572_864, Locale.GERMANY))
    }
}
