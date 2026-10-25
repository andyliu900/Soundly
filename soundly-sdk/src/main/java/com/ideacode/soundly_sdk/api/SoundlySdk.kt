package com.ideacode.soundly_sdk.api

import com.ideacode.soundly_sdk.api.capability.AudioImporter
import com.ideacode.soundly_sdk.api.capability.AudioMetadataReader
import com.ideacode.soundly_sdk.api.capability.AudioProjectRepository
import com.ideacode.soundly_sdk.api.capability.RecordingManager
import com.ideacode.soundly_sdk.api.capability.AudioPlaybackController
import com.ideacode.soundly_sdk.api.capability.AudioExporter
import com.ideacode.soundly_sdk.api.capability.AudioTrimmer
import com.ideacode.soundly_sdk.api.capability.StorageManager
import com.ideacode.soundly_sdk.api.capability.AudioEnhancer
import com.ideacode.soundly_sdk.processor.AudioProcessingService
import com.ideacode.soundly_sdk.api.model.settings.SoundlySettingsRepository

/**
 * Stable facade for capabilities that are implemented by the current SDK.
 * New capabilities are added here only after their implementation is usable.
 */
interface SoundlySdk {
    val processor: AudioProcessingService
    val projects: AudioProjectRepository
    val importer: AudioImporter
    val metadata: AudioMetadataReader
    val recorder: RecordingManager
    val playback: AudioPlaybackController
    val trimmer: AudioTrimmer
    val exporter: AudioExporter
    val storage: StorageManager
    val enhancer: AudioEnhancer
    val settings: SoundlySettingsRepository
}
