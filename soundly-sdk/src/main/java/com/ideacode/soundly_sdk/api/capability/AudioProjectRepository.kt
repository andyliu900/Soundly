package com.ideacode.soundly_sdk.api.capability

import com.ideacode.soundly_sdk.api.model.domain.AudioMarker
import com.ideacode.soundly_sdk.api.model.domain.AudioProject
import com.ideacode.soundly_sdk.api.model.domain.AudioVariant
import com.ideacode.soundly_sdk.api.result.SoundlyResult
import kotlinx.coroutines.flow.Flow

interface AudioProjectRepository {
    fun observeProjects(): Flow<List<AudioProject>>
    fun observeProject(projectId: String): Flow<AudioProject?>
    fun searchProjects(query: String): Flow<List<AudioProject>>
    suspend fun getProject(projectId: String): SoundlyResult<AudioProject>
    /** Returns the original followed by derived versions in newest-first order. */
    suspend fun getVariants(projectId: String): SoundlyResult<List<AudioVariant>>
    suspend fun saveProject(project: AudioProject): SoundlyResult<Unit>
    suspend fun renameProject(projectId: String, title: String): SoundlyResult<Unit>
    suspend fun addVariant(projectId: String, variant: AudioVariant): SoundlyResult<Unit>
    suspend fun deleteVariant(projectId: String, variantId: String): SoundlyResult<Unit>
    suspend fun addMarker(marker: AudioMarker): SoundlyResult<Unit>
    suspend fun deleteMarker(projectId: String, markerId: String): SoundlyResult<Unit>
    suspend fun deleteProject(projectId: String): SoundlyResult<Unit>
}
