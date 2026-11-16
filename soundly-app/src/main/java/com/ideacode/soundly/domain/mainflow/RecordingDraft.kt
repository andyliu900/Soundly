package com.ideacode.soundly.domain.mainflow

import com.ideacode.soundly_sdk.api.model.domain.RecordingScene
import com.ideacode.soundly_sdk.api.model.recording.RecordingConfig
import com.ideacode.soundly_sdk.api.model.recording.RecordingQuality
import com.ideacode.soundly_sdk.api.model.settings.SoundlySettings
import com.ideacode.soundly_sdk.api.model.settings.createRecordingTitle
import com.ideacode.soundly_sdk.api.model.settings.toRecordingConfig

/** Overrides for one recording; this never changes the persisted defaults. */
data class RecordingDraft(
    val homeScene: RecordingScene? = null,
    val sceneOverride: RecordingScene? = null,
    val qualityOverride: RecordingQuality? = null,
    val title: String? = null
) {
    fun toConfig(defaults: SoundlySettings, timestampMs: Long): RecordingConfig {
        val resolvedTitle = title?.trim()?.takeIf(String::isNotEmpty)
            ?: defaults.createRecordingTitle(timestampMs)
        return defaults.toRecordingConfig(resolvedTitle).copy(
            scene = sceneOverride ?: homeScene ?: defaults.defaultScene,
            quality = qualityOverride ?: defaults.recordingQuality
        )
    }
}

/** The current five UI scenarios map to SDK scene metadata. */
enum class RecordingScenario(val sdkScene: RecordingScene) {
    AUTO(RecordingScene.AUTO),
    MEETING(RecordingScene.MEETING),
    CLASSROOM(RecordingScene.CLASSROOM),
    INTERVIEW(RecordingScene.INTERVIEW),
    VOICE_MEMO(RecordingScene.OTHER)
}
