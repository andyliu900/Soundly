package com.ideacode.soundly_sdk.project.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.ideacode.soundly_sdk.project.data.entity.AudioMarkerEntity
import com.ideacode.soundly_sdk.project.data.entity.AudioProjectAggregate
import com.ideacode.soundly_sdk.project.data.entity.AudioProjectEntity
import com.ideacode.soundly_sdk.project.data.entity.ProjectAudioVariantEntity
import com.ideacode.soundly_sdk.project.data.entity.RecoverySessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
internal interface AudioProjectDao {

    @Transaction
    @Query("SELECT * FROM audio_project ORDER BY updatedAt DESC")
    fun observeProjects(): Flow<List<AudioProjectAggregate>>

    @Transaction
    @Query("SELECT * FROM audio_project WHERE id = :projectId LIMIT 1")
    fun observeProject(projectId: String): Flow<AudioProjectAggregate?>

    @Transaction
    @Query("SELECT * FROM audio_project WHERE id = :projectId LIMIT 1")
    suspend fun getProject(projectId: String): AudioProjectAggregate?

    @Transaction
    @Query(
        "SELECT * FROM audio_project " +
            "WHERE instr(lower(title), lower(:query)) > 0 " +
            "ORDER BY updatedAt DESC"
    )
    fun searchProjects(query: String): Flow<List<AudioProjectAggregate>>

    @Query("SELECT * FROM project_audio_variant WHERE id = :variantId LIMIT 1")
    suspend fun getVariant(variantId: String): ProjectAudioVariantEntity?

    @Query("SELECT COUNT(*) FROM project_audio_variant WHERE sourceVariantId = :variantId")
    suspend fun countDerivedVariants(variantId: String): Int

    @Upsert
    suspend fun upsertProject(entity: AudioProjectEntity)

    @Upsert
    suspend fun upsertVariants(entities: List<ProjectAudioVariantEntity>)

    @Upsert
    suspend fun upsertMarkers(entities: List<AudioMarkerEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertVariant(entity: ProjectAudioVariantEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMarker(entity: AudioMarkerEntity)

    @Query("UPDATE audio_project SET updatedAt = :updatedAt WHERE id = :projectId")
    suspend fun touchProject(projectId: String, updatedAt: Long): Int

    @Transaction
    suspend fun insertVariantAndTouch(entity: ProjectAudioVariantEntity, updatedAt: Long) {
        insertVariant(entity)
        touchProject(entity.projectId, updatedAt)
    }

    @Transaction
    suspend fun insertMarkerAndTouch(entity: AudioMarkerEntity, updatedAt: Long) {
        insertMarker(entity)
        touchProject(entity.projectId, updatedAt)
    }

    @Transaction
    suspend fun saveProject(
        project: AudioProjectEntity,
        variants: List<ProjectAudioVariantEntity>,
        markers: List<AudioMarkerEntity>
    ) {
        upsertProject(project)
        upsertVariants(variants)
        upsertMarkers(markers)
    }

    @Query(
        "UPDATE audio_project SET title = :title, updatedAt = :updatedAt " +
            "WHERE id = :projectId"
    )
    suspend fun renameProject(projectId: String, title: String, updatedAt: Long): Int

    @Query("DELETE FROM project_audio_variant WHERE id = :variantId AND projectId = :projectId")
    suspend fun deleteVariant(projectId: String, variantId: String): Int

    @Transaction
    suspend fun deleteVariantAndTouch(
        projectId: String,
        variantId: String,
        updatedAt: Long
    ): Int {
        val deleted = deleteVariant(projectId, variantId)
        if (deleted > 0) touchProject(projectId, updatedAt)
        return deleted
    }

    @Query("DELETE FROM audio_marker WHERE id = :markerId AND projectId = :projectId")
    suspend fun deleteMarker(projectId: String, markerId: String): Int

    @Transaction
    suspend fun deleteMarkerAndTouch(
        projectId: String,
        markerId: String,
        updatedAt: Long
    ): Int {
        val deleted = deleteMarker(projectId, markerId)
        if (deleted > 0) touchProject(projectId, updatedAt)
        return deleted
    }

    @Query("DELETE FROM audio_project WHERE id = :projectId")
    suspend fun deleteProject(projectId: String): Int

    @Upsert
    suspend fun upsertRecoverySession(entity: RecoverySessionEntity)

    @Query("SELECT * FROM recovery_session ORDER BY updatedAt DESC")
    suspend fun getRecoverySessions(): List<RecoverySessionEntity>

    @Query("SELECT * FROM recovery_session WHERE id = :sessionId LIMIT 1")
    suspend fun getRecoverySession(sessionId: String): RecoverySessionEntity?

    @Query(
        "UPDATE recovery_session SET recordedDurationMs = :durationMs, " +
            "recordedBytes = :recordedBytes, status = :status, updatedAt = :updatedAt " +
            "WHERE id = :sessionId"
    )
    suspend fun updateRecoverySession(
        sessionId: String,
        durationMs: Long,
        recordedBytes: Long,
        status: String,
        updatedAt: Long
    ): Int

    @Query("DELETE FROM recovery_session WHERE id = :sessionId")
    suspend fun deleteRecoverySession(sessionId: String)

    @Query("DELETE FROM audio_project")
    suspend fun clearProjects()

    @Query("DELETE FROM recovery_session")
    suspend fun clearRecoverySessions()

    @Transaction
    suspend fun clearAllProjectData() {
        clearRecoverySessions()
        clearProjects()
    }
}
