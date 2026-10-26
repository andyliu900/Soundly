package com.ideacode.soundly.data.sdk

import com.ideacode.soundly.common.error.AppResult
import com.ideacode.soundly_sdk.api.model.domain.AudioProject
import com.ideacode.soundly_sdk.api.model.domain.AudioVariant
import com.ideacode.soundly_sdk.api.model.recording.StaticWaveform
import kotlinx.coroutines.flow.Flow

interface ProjectGateway {
    fun observeProjects(): Flow<List<AudioProject>>
    fun observeProject(projectId: String): Flow<AudioProject?>
    suspend fun getProject(projectId: String): AppResult<AudioProject>
    suspend fun renameProject(projectId: String, title: String): AppResult<Unit>
    suspend fun loadWaveform(projectId: String, variantId: String): AppResult<StaticWaveform>
    suspend fun preparePlayback(variant: AudioVariant): AppResult<Unit>
    fun play(): AppResult<Unit>
    fun pausePlayback(): AppResult<Unit>
}

class DefaultProjectGateway(
    private val provider: SoundlySdkProvider
) : ProjectGateway {
    private val sdk get() = provider.requireSdk()

    override fun observeProjects(): Flow<List<AudioProject>> = sdk.projects.observeProjects()
    override fun observeProject(projectId: String): Flow<AudioProject?> = sdk.projects.observeProject(projectId)
    override suspend fun getProject(projectId: String): AppResult<AudioProject> =
        sdk.projects.getProject(projectId).toAppResult()
    override suspend fun renameProject(projectId: String, title: String): AppResult<Unit> =
        sdk.projects.renameProject(projectId, title).toAppResult()
    override suspend fun loadWaveform(projectId: String, variantId: String): AppResult<StaticWaveform> =
        sdk.recorder.loadStaticWaveform(projectId, variantId).toAppResult()
    override suspend fun preparePlayback(variant: AudioVariant): AppResult<Unit> =
        sdk.playback.prepare(variant.toSourceRef()).toAppResult()
    override fun play(): AppResult<Unit> = sdk.playback.play().toAppResult()
    override fun pausePlayback(): AppResult<Unit> = sdk.playback.pause().toAppResult()
}
