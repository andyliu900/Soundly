package com.ideacode.soundly.feature.settings

/** Only the explicit opt-in adds the supplied, non-audio diagnostic fields. */
internal object FeedbackDraft {
    fun body(message: String, includeDiagnostics: Boolean, version: String, androidVersion: String, device: String): String =
        if (includeDiagnostics) "$message\n\n--- Diagnostics ---\nVersion: $version\nAndroid: $androidVersion\nDevice: $device" else message
}
