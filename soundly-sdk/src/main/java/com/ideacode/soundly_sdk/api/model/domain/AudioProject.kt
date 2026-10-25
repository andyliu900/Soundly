package com.ideacode.soundly_sdk.api.model.domain

data class AudioProject(
    val id: String,
    val title: String,
    val sourceType: AudioSourceType,
    val scene: RecordingScene?,
    val original: AudioVariant,
    val variants: List<AudioVariant> = emptyList(),
    val markers: List<AudioMarker> = emptyList(),
    val status: ProjectStatus = ProjectStatus.AVAILABLE,
    val createdAt: Long,
    val updatedAt: Long
)

data class AudioMarker(
    val id: String,
    val projectId: String,
    val positionMs: Long,
    val label: String? = null,
    val createdAt: Long
)

enum class AudioSourceType {
    RECORDED,
    IMPORTED
}

enum class RecordingScene {
    AUTO,
    MEETING,
    INTERVIEW,
    CLASSROOM,
    PODCAST,
    OUTDOOR,
    OTHER
}

enum class ProjectStatus {
    RECORDING,
    RECOVERY_REQUIRED,
    AVAILABLE,
    PROCESSING,
    FAILED
}

enum class VariantType {
    ORIGINAL,
    ENHANCED,
    TRIMMED
}

enum class AudioFormat {
    WAV,
    M4A,
    AAC,
    MP3,
    UNKNOWN
}

