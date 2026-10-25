package com.ideacode.soundly_sdk.project.repository

import com.ideacode.soundly_sdk.api.capability.AudioProjectRepository
import com.ideacode.soundly_sdk.api.error.SoundlyError
import com.ideacode.soundly_sdk.api.model.domain.AudioMarker
import com.ideacode.soundly_sdk.api.model.domain.AudioProject
import com.ideacode.soundly_sdk.api.model.domain.AudioVariant
import com.ideacode.soundly_sdk.api.result.SoundlyResult
import com.ideacode.soundly_sdk.project.data.dao.AudioProjectDao
import com.ideacode.soundly_sdk.project.data.entity.toDomain
import com.ideacode.soundly_sdk.project.data.entity.toEntity
import com.ideacode.soundly_sdk.storage.AudioFileStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CancellationException
import java.io.File

internal class RoomAudioProjectRepository(
    private val dao: AudioProjectDao,
    private val fileStore: AudioFileStore,
    private val clock: () -> Long = System::currentTimeMillis
) : AudioProjectRepository {

    override fun observeProjects(): Flow<List<AudioProject>> =
        dao.observeProjects().map { projects -> projects.map { it.toDomain() } }

    override fun observeProject(projectId: String): Flow<AudioProject?> =
        dao.observeProject(projectId).map { it?.toDomain() }

    override fun searchProjects(query: String): Flow<List<AudioProject>> =
        if (query.isBlank()) {
            observeProjects()
        } else {
            dao.searchProjects(query.trim()).map { projects -> projects.map { it.toDomain() } }
        }

    override suspend fun getProject(projectId: String): SoundlyResult<AudioProject> = try {
        dao.getProject(projectId)?.let { SoundlyResult.Success(it.toDomain()) }
            ?: SoundlyResult.Failure(SoundlyError.ProjectNotFound(projectId))
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        SoundlyResult.Failure(SoundlyError.DatabaseFailed(error))
    }

    override suspend fun getVariants(projectId: String): SoundlyResult<List<AudioVariant>> =
        when (val result = getProject(projectId)) {
            is SoundlyResult.Success -> SoundlyResult.Success(
                listOf(result.value.original) + result.value.variants.sortedByDescending {
                    it.createdAt
                }
            )
            is SoundlyResult.Failure -> result
        }

    override suspend fun saveProject(project: AudioProject): SoundlyResult<Unit> {
        val validationError = validateProject(project)
        if (validationError != null) return SoundlyResult.Failure(validationError)

        return databaseCall {
            dao.saveProject(
                project = project.toEntity(),
                variants = (listOf(project.original) + project.variants).map { it.toEntity() },
                markers = project.markers.map { it.toEntity() }
            )
            Unit
        }
    }

    override suspend fun renameProject(
        projectId: String,
        title: String
    ): SoundlyResult<Unit> {
        val normalized = title.trim()
        if (normalized.isBlank() || normalized.length > MAX_TITLE_LENGTH) {
            return SoundlyResult.Failure(SoundlyError.InvalidRequest(field = "title"))
        }
        return try {
            if (dao.renameProject(projectId, normalized, clock()) == 0) {
                SoundlyResult.Failure(SoundlyError.ProjectNotFound(projectId))
            } else {
                SoundlyResult.Success(Unit)
            }
        } catch (error: Exception) {
            SoundlyResult.Failure(SoundlyError.DatabaseFailed(error))
        }
    }

    override suspend fun addVariant(
        projectId: String,
        variant: AudioVariant
    ): SoundlyResult<Unit> {
        if (variant.projectId != projectId || !variant.id.matches(SAFE_IDENTIFIER)) {
            return SoundlyResult.Failure(SoundlyError.InvalidRequest(field = "variant"))
        }
        return try {
            val project = dao.getProject(projectId)
            if (project == null) {
                SoundlyResult.Failure(SoundlyError.ProjectNotFound(projectId))
            } else if (!isValidDerivedVariant(
                    variant,
                    project.variants.mapTo(mutableSetOf()) { it.id }
                )
            ) {
                SoundlyResult.Failure(SoundlyError.InvalidRequest(field = "variant"))
            } else {
                dao.insertVariantAndTouch(variant.toEntity(), clock())
                SoundlyResult.Success(Unit)
            }
        } catch (error: Exception) {
            SoundlyResult.Failure(SoundlyError.DatabaseFailed(error))
        }
    }

    override suspend fun deleteVariant(
        projectId: String,
        variantId: String
    ): SoundlyResult<Unit> {
        val project = try {
            dao.getProject(projectId)
        } catch (error: Exception) {
            return SoundlyResult.Failure(SoundlyError.DatabaseFailed(error))
        } ?: return SoundlyResult.Failure(SoundlyError.ProjectNotFound(projectId))
        if (project.project.originalVariantId == variantId) {
            return SoundlyResult.Failure(SoundlyError.InvalidRequest(field = "variantId"))
        }
        val variant = try {
            dao.getVariant(variantId)
        } catch (error: Exception) {
            return SoundlyResult.Failure(SoundlyError.DatabaseFailed(error))
        } ?: return SoundlyResult.Failure(SoundlyError.InvalidRequest(field = "variantId"))
        try {
            if (dao.countDerivedVariants(variantId) > 0) {
                return SoundlyResult.Failure(
                    SoundlyError.VariantInUse(projectId, variantId)
                )
            }
        } catch (error: Exception) {
            return SoundlyResult.Failure(SoundlyError.DatabaseFailed(error))
        }
        val managedFile = File(variant.filePath).takeIf(fileStore::isManaged)
        val staged = try {
            managedFile?.let(fileStore::stageDeletion)
        } catch (error: Exception) {
            return SoundlyResult.Failure(SoundlyError.FileOperationFailed(error))
        }

        return try {
            if (dao.deleteVariantAndTouch(projectId, variantId, clock()) == 0) {
                staged?.let(fileStore::rollbackDeletion)
                SoundlyResult.Failure(SoundlyError.InvalidRequest(field = "variantId"))
            } else if (staged != null && !fileStore.commitDeletion(staged)) {
                // The database delete already committed. Restore both sides when possible so a
                // reported failure does not silently leave a half-deleted version.
                if (fileStore.rollbackDeletion(staged)) {
                    runCatching { dao.insertVariantAndTouch(variant, clock()) }
                }
                SoundlyResult.Failure(SoundlyError.FileOperationFailed())
            } else {
                SoundlyResult.Success(Unit)
            }
        } catch (error: Exception) {
            staged?.let(fileStore::rollbackDeletion)
            SoundlyResult.Failure(SoundlyError.DatabaseFailed(error))
        }
    }

    override suspend fun addMarker(marker: AudioMarker): SoundlyResult<Unit> {
        if (!marker.id.matches(SAFE_IDENTIFIER) || marker.positionMs < 0L) {
            return SoundlyResult.Failure(SoundlyError.InvalidRequest(field = "marker"))
        }
        return try {
            if (dao.getProject(marker.projectId) == null) {
                SoundlyResult.Failure(SoundlyError.ProjectNotFound(marker.projectId))
            } else {
                dao.insertMarkerAndTouch(marker.toEntity(), clock())
                SoundlyResult.Success(Unit)
            }
        } catch (error: Exception) {
            SoundlyResult.Failure(SoundlyError.DatabaseFailed(error))
        }
    }

    override suspend fun deleteMarker(
        projectId: String,
        markerId: String
    ): SoundlyResult<Unit> = try {
        if (dao.deleteMarkerAndTouch(projectId, markerId, clock()) == 0) {
            SoundlyResult.Failure(SoundlyError.InvalidRequest(field = "markerId"))
        } else {
            SoundlyResult.Success(Unit)
        }
    } catch (error: Exception) {
        SoundlyResult.Failure(SoundlyError.DatabaseFailed(error))
    }

    override suspend fun deleteProject(projectId: String): SoundlyResult<Unit> {
        try {
            if (dao.getProject(projectId) == null) {
                return SoundlyResult.Failure(SoundlyError.ProjectNotFound(projectId))
            }
        } catch (error: Exception) {
            return SoundlyResult.Failure(SoundlyError.DatabaseFailed(error))
        }
        val projectDirectory = fileStore.projectDirectory(projectId)
        val staged = try {
            fileStore.stageDeletion(projectDirectory)
        } catch (error: Exception) {
            return SoundlyResult.Failure(SoundlyError.FileOperationFailed(error))
        }

        return try {
            if (dao.deleteProject(projectId) == 0) {
                staged?.let(fileStore::rollbackDeletion)
                SoundlyResult.Failure(SoundlyError.ProjectNotFound(projectId))
            } else if (staged != null && !fileStore.commitDeletion(staged)) {
                // The project rows stay deleted. A partially removed staged directory is now
                // unreferenced cache and can be retried by clearCache/deleteAllLocalData; this is
                // safer than restoring rows that might point at files already removed.
                SoundlyResult.Failure(SoundlyError.FileOperationFailed())
            } else {
                SoundlyResult.Success(Unit)
            }
        } catch (error: Exception) {
            staged?.let(fileStore::rollbackDeletion)
            SoundlyResult.Failure(SoundlyError.DatabaseFailed(error))
        }
    }

    private suspend inline fun <T> databaseCall(
        crossinline block: suspend () -> T
    ): SoundlyResult<T> = try {
        SoundlyResult.Success(block())
    } catch (error: Exception) {
        SoundlyResult.Failure(SoundlyError.DatabaseFailed(error))
    }

    private fun validateProject(project: AudioProject): SoundlyError.InvalidRequest? {
        if (!project.id.matches(SAFE_IDENTIFIER)) {
            return SoundlyError.InvalidRequest("project.id")
        }
        if (project.title.isBlank() || project.title.length > MAX_TITLE_LENGTH) {
            return SoundlyError.InvalidRequest("project.title")
        }
        if (!project.original.id.matches(SAFE_IDENTIFIER) ||
            project.original.projectId != project.id
        ) {
            return SoundlyError.InvalidRequest("project.original")
        }
        val variantIds = project.variants.map { it.id }
        if (project.original.id in variantIds || variantIds.distinct().size != variantIds.size) {
            return SoundlyError.InvalidRequest("project.variants")
        }
        if (project.variants.any {
                !it.id.matches(SAFE_IDENTIFIER) || it.projectId != project.id
            }
        ) {
            return SoundlyError.InvalidRequest("project.variants")
        }
        val allVariantIds = (listOf(project.original) + project.variants)
            .mapTo(mutableSetOf()) { it.id }
        if (project.variants.any { !isValidDerivedVariant(it, allVariantIds) }) {
            return SoundlyError.InvalidRequest("project.variants")
        }
        if (project.markers.any {
                !it.id.matches(SAFE_IDENTIFIER) ||
                    it.projectId != project.id ||
                    it.positionMs < 0L
            }
        ) {
            return SoundlyError.InvalidRequest("project.markers")
        }
        return null
    }

    private fun isValidDerivedVariant(
        variant: AudioVariant,
        availableVariantIds: Set<String>
    ): Boolean {
        if (variant.sourceVariantId == variant.id) return false
        if (variant.sourceVariantId != null && variant.sourceVariantId !in availableVariantIds) {
            return false
        }
        return when (variant.variantType) {
            com.ideacode.soundly_sdk.api.model.domain.VariantType.ORIGINAL ->
                variant.sourceVariantId == null &&
                    variant.trimStartMs == null && variant.trimEndMs == null

            com.ideacode.soundly_sdk.api.model.domain.VariantType.ENHANCED ->
                variant.trimStartMs == null && variant.trimEndMs == null

            com.ideacode.soundly_sdk.api.model.domain.VariantType.TRIMMED ->
                variant.sourceVariantId != null &&
                    variant.trimStartMs != null && variant.trimStartMs >= 0L &&
                    variant.trimEndMs != null && variant.trimEndMs > variant.trimStartMs
        }
    }

    private companion object {
        const val MAX_TITLE_LENGTH = 120
        val SAFE_IDENTIFIER = Regex("[A-Za-z0-9_-]{1,128}")
    }
}
