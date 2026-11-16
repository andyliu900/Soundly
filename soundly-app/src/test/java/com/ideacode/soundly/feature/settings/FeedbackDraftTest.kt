package com.ideacode.soundly.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackDraftTest {
    @Test fun diagnosticsAreIncludedOnlyAfterOptIn() {
        assertEquals("问题描述", FeedbackDraft.body("问题描述", false, "1.0", "13", "Device"))
        val optedIn = FeedbackDraft.body("问题描述", true, "1.0", "13", "Device")
        assertTrue(optedIn.contains("Version: 1.0"))
        assertTrue(optedIn.contains("Android: 13"))
        assertTrue(optedIn.contains("Device: Device"))
    }
}
