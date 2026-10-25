package com.ideacode.soundly_sdk.api

/**
 * Marks contracts whose shape is established for upcoming implementation work
 * but is not yet exposed by [SoundlySdk].
 */
@RequiresOptIn(
    message = "This Soundly API is not implemented by the stable SDK facade yet.",
    level = RequiresOptIn.Level.WARNING
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
internal annotation class ExperimentalSoundlyApi
