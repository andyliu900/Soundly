package com.ideacode.soundly.navigation

/**
 * Stable identifiers for the F01-F38 implementation plan.
 *
 * Navigation XML receives concrete destinations as each M2-M5 feature is
 * implemented. Keeping identifiers here prevents UI code from using raw IDs.
 */
enum class AppDestination(val scenarioCode: String) {
    STARTUP("F01"),
    ONBOARDING("F02"),
    HOME("F03"),
    PREPARE_RECORDING("F07"),
    RECORDING("F08"),
    AUDIO_PREVIEW("F15"),
    ENHANCEMENT_SETUP("F18"),
    ENHANCEMENT_RESULT("F22"),
    LIBRARY("F27"),
    SETTINGS("F31")
}
