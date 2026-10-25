package com.ideacode.soundly_sdk.project.data.entity

import androidx.room.Embedded
import androidx.room.Relation
import com.ideacode.soundly_sdk.api.model.audio.dsp.PresetOption
import com.ideacode.soundly_sdk.api.model.domain.AudioFormat
import com.ideacode.soundly_sdk.api.model.domain.AudioMarker
import com.ideacode.soundly_sdk.api.model.domain.AudioProject
import com.ideacode.soundly_sdk.api.model.domain.AudioSourceType
import com.ideacode.soundly_sdk.api.model.domain.AudioVariant
import com.ideacode.soundly_sdk.api.model.domain.ProjectStatus
import com.ideacode.soundly_sdk.api.model.domain.RecordingScene
import com.ideacode.soundly_sdk.api.model.domain.VariantType

internal data class AudioProjectAggregate(
    @Embedded val project: AudioProjectEntity,
    @Relation(parentColumn = "id", entityColumn = "projectId")
    val variants: List<ProjectAudioVariantEntity>,
    @Relation(parentColumn = "id", entityColumn = "projectId")
    val markers: List<AudioMarkerEntity>
)

internal fun AudioProjectAggregate.toDomain(): AudioProject {
    val domainVariants = variants.map { it.toDomain() }
    val original = requireNotNull(
        domainVariants.firstOrNull { it.id == project.originalVariantId }
    ) { "Project ${project.id} has no original variant" }

    return AudioProject(
        id = project.id,
        title = project.title,
        sourceType = enumValueOf(project.sourceType),
        scene = project.recordingScene?.let { enumValueOf<RecordingScene>(it) },
        original = original,
        variants = domainVariants
            .filterNot { it.id == project.originalVariantId }
            .sortedByDescending { it.createdAt },
        markers = markers
            .map { it.toDomain() }
            .sortedBy { it.positionMs },
        status = enumValueOf(project.status),
        createdAt = project.createdAt,
        updatedAt = project.updatedAt
    )
}

internal fun AudioProject.toEntity(): AudioProjectEntity = AudioProjectEntity(
    id = id,
    title = title,
    sourceType = sourceType.name,
    recordingScene = scene?.name,
    status = status.name,
    originalVariantId = original.id,
    createdAt = createdAt,
    updatedAt = updatedAt
)

internal fun AudioVariant.toEntity(): ProjectAudioVariantEntity =
    ProjectAudioVariantEntity(
        id = id,
        projectId = projectId,
        variantType = variantType.name,
        presetOption = presetOption.name,
        filePath = filePath,
        format = format.name,
        mimeType = mimeType,
        sampleRate = sampleRate,
        channels = channels,
        durationMs = durationMs,
        fileSizeBytes = fileSizeBytes,
        createdAt = createdAt,
        sourceVariantId = sourceVariantId,
        trimStartMs = trimStartMs,
        trimEndMs = trimEndMs
    )

internal fun ProjectAudioVariantEntity.toDomain(): AudioVariant = AudioVariant(
    id = id,
    projectId = projectId,
    variantType = enumValueOf<VariantType>(variantType),
    presetOption = enumValueOf<PresetOption>(presetOption),
    filePath = filePath,
    format = enumValueOf<AudioFormat>(format),
    mimeType = mimeType,
    sampleRate = sampleRate,
    channels = channels,
    durationMs = durationMs,
    fileSizeBytes = fileSizeBytes,
    createdAt = createdAt,
    isOriginal = variantType == VariantType.ORIGINAL.name,
    sourceVariantId = sourceVariantId,
    trimStartMs = trimStartMs,
    trimEndMs = trimEndMs
)

internal fun AudioMarker.toEntity(): AudioMarkerEntity = AudioMarkerEntity(
    id = id,
    projectId = projectId,
    positionMs = positionMs,
    label = label,
    createdAt = createdAt
)

internal fun AudioMarkerEntity.toDomain(): AudioMarker = AudioMarker(
    id = id,
    projectId = projectId,
    positionMs = positionMs,
    label = label,
    createdAt = createdAt
)
