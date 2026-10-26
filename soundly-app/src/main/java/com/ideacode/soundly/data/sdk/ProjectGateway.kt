package com.ideacode.soundly.data.sdk

import com.ideacode.soundly.common.error.AppResult
import com.ideacode.soundly_sdk.api.model.domain.AudioProject
import com.ideacode.soundly_sdk.api.model.domain.AudioVariant
import com.ideacode.soundly_sdk.api.model.recording.StaticWaveform
import com.ideacode.soundly_sdk.api.model.playback.PlaybackState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface ProjectGateway {
    val playbackState: StateFlow<PlaybackState>
    fun observeProjects(): Flow<List<AudioProject>>
    fun searchProjects(query: String): Flow<List<AudioProject>>
    fun observeProject(projectId: String): Flow<AudioProject?>
    suspend fun getProject(projectId: String): AppResult<AudioProject>
    suspend fun renameProject(projectId: String, title: String): AppResult<Unit>
    suspend fun deleteProject(projectId: String): AppResult<Unit>
    suspend fun loadWaveform(projectId: String, variantId: String): AppResult<StaticWaveform>
    suspend fun preparePlayback(variant: AudioVariant): AppResult<Unit>
    fun play(): AppResult<Unit>
    fun pausePlayback(): AppResult<Unit>
    suspend fun switchPlaybackKeepingPosition(variant: AudioVariant): AppResult<Unit>
}

class DefaultProjectGateway(
    private val provider: SoundlySdkProvider
) : ProjectGateway {
    private val sdk get() = provider.requireSdk()

    override val playbackState: StateFlow<PlaybackState>
        get() = sdk.playback.state

    override fun observeProjects(): Flow<List<AudioProject>> = sdk.projects.observeProjects()
    override fun searchProjects(query: String): Flow<List<AudioProject>> = sdk.projects.searchProjects(query)
    override fun observeProject(projectId: String): Flow<AudioProject?> = sdk.projects.observeProject(projectId)
    override suspend fun getProject(projectId: String): AppResult<AudioProject> =
        sdk.projects.getProject(projectId).toAppResult()
    override suspend fun renameProject(projectId: String, title: String): AppResult<Unit> =
        sdk.projects.renameProject(projectId, title).toAppResult()
    override suspend fun deleteProject(projectId: String): AppResult<Unit> =
        sdk.projects.deleteProject(projectId).toAppResult()
    override suspend fun loadWaveform(projectId: String, variantId: String): AppResult<StaticWaveform> =
        sdk.recorder.loadStaticWaveform(projectId, variantId).toAppResult()
    override suspend fun preparePlayback(variant: AudioVariant): AppResult<Unit> =
        sdk.playback.prepare(variant.toSourceRef()).toAppResult()
    override fun play(): AppResult<Unit> = sdk.playback.play().toAppResult()
    override fun pausePlayback(): AppResult<Unit> = sdk.playback.pause().toAppResult()
    override suspend fun switchPlaybackKeepingPosition(variant: AudioVariant): AppResult<Unit> =
        sdk.playback.switchSourceKeepingPosition(variant.toSourceRef()).toAppResult()
}
