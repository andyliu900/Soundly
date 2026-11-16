package com.ideacode.soundly.feature.preview

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import com.ideacode.soundly.common.error.*
import com.ideacode.soundly.data.sdk.*
import com.ideacode.soundly.feature.enhancement.StartEnhancementUseCase
import com.ideacode.soundly_sdk.api.capability.*
import com.ideacode.soundly_sdk.api.model.audio.dsp.PresetOption
import com.ideacode.soundly_sdk.api.model.domain.*
import com.ideacode.soundly_sdk.api.model.enhancement.*
import com.ideacode.soundly_sdk.api.model.export.*
import com.ideacode.soundly_sdk.api.model.playback.PlaybackState
import com.ideacode.soundly_sdk.api.model.recording.StaticWaveform
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class AudioPreviewViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun teardown() = Dispatchers.resetMain()
    private val original = AudioVariant("/original", PresetOption.DEFAULT, true, 44100, 1, 10000, id = "original")
    private val other = original.copy(filePath = "/other", isOriginal = false, id = "selected")
    private val project = AudioProject("p", "old", AudioSourceType.RECORDED, null, original, listOf(other), createdAt = 0, updatedAt = 0)

    @Test fun `missing explicit version is an error and never starts enhancement`() = runTest(dispatcher) {
        val projects = Projects(project); val editing = Editing()
        val model = AudioPreviewViewModel(SavedStateHandle(mapOf("projectId" to "p", "variantId" to "missing")), projects, StartEnhancementUseCase(editing))
        advanceUntilIdle()
        assertTrue(model.uiState.value.missing)
        assertNull(model.uiState.value.variant)
        model.dispatch(PreviewAction.Enhance); advanceUntilIdle()
        assertEquals(0, editing.calls)
    }
    @Test fun `selection playback seek rename and single enhancement use exact version`() = runTest(dispatcher) {
        val projects = Projects(project); val editing = Editing(); val gate = CompletableDeferred<Unit>(); editing.gate = gate
        val handle = SavedStateHandle(mapOf("projectId" to "p", "variantId" to "selected"))
        val model = AudioPreviewViewModel(handle, projects, StartEnhancementUseCase(editing)); model.setActive(true)
        advanceUntilIdle()
        assertEquals("selected", model.uiState.value.variant?.id)
        assertEquals("selected", projects.waveformId)
        model.dispatch(PreviewAction.Seek(3000)); advanceUntilIdle()
        assertEquals("selected", projects.preparedId); assertEquals(3000L, projects.position)
        model.dispatch(PreviewAction.Toggle); advanceUntilIdle(); assertTrue(projects.playbackState.value is PlaybackState.Playing)
        model.setActive(false); assertTrue(projects.playbackState.value is PlaybackState.Paused)
        model.dispatch(PreviewAction.Rename("  ")); assertEquals("empty", model.uiState.value.renameError)
        model.dispatch(PreviewAction.Rename(" x ")); advanceUntilIdle(); assertEquals("x", projects.renamed)
        model.dispatch(PreviewAction.Enhance); model.dispatch(PreviewAction.Enhance); runCurrent()
        assertEquals(1, editing.calls); assertEquals("selected", editing.source); assertEquals(PresetOption.AI, editing.preset)
        gate.complete(Unit); advanceUntilIdle()
        assertEquals("task", (model.effects.value.last().value as PreviewEffect.EnhancementStarted).taskId)
        val restored = AudioPreviewViewModel(handle, projects, StartEnhancementUseCase(editing)); advanceUntilIdle()
        assertTrue(restored.uiState.value.starting)
        assertEquals("task", (restored.effects.value.single().value as PreviewEffect.EnhancementStarted).taskId)
        assertEquals(1, editing.calls)
    }
    @Test fun `import waveform failure keeps playback available and retries selected version`() = runTest(dispatcher) {
        val projects = Projects(project.copy(sourceType = AudioSourceType.IMPORTED))
        projects.failWaveform = true
        val model = AudioPreviewViewModel(SavedStateHandle(mapOf("projectId" to "p", "variantId" to "selected")), projects, StartEnhancementUseCase(Editing()))
        model.setActive(true)
        advanceUntilIdle()
        assertTrue(model.uiState.value.waveformError)
        assertEquals(1, projects.generateCalls)
        model.dispatch(PreviewAction.Toggle); advanceUntilIdle()
        assertEquals("selected", projects.preparedId)
        projects.failWaveform = false
        model.dispatch(PreviewAction.RetryWaveform); advanceUntilIdle()
        assertFalse(model.uiState.value.waveformError)
        assertEquals(listOf(.5f), model.uiState.value.waveform)
        assertEquals(2, projects.generateCalls)
        assertEquals("selected", projects.waveformId)
    }
    @Test fun `trim result becomes selected version and survives recreation`() = runTest(dispatcher) {
        val saved = SavedStateHandle(mapOf("projectId" to "p", "variantId" to "original"))
        val projects = Projects(project.copy(sourceType = AudioSourceType.IMPORTED))
        val first = AudioPreviewViewModel(saved, projects, StartEnhancementUseCase(Editing()))
        advanceUntilIdle(); assertEquals("original", first.uiState.value.variant?.id)
        first.selectVariant("selected"); advanceUntilIdle(); assertEquals("selected", first.uiState.value.variant?.id)
        val restored = AudioPreviewViewModel(saved, projects, StartEnhancementUseCase(Editing()))
        advanceUntilIdle(); assertEquals("selected", restored.uiState.value.variant?.id)
        restored.selectVariant("missing"); advanceUntilIdle(); assertTrue(restored.uiState.value.missing)
    }
    private class Projects(project: AudioProject) : ProjectGateway {
        val projectFlow = MutableStateFlow<AudioProject?>(project)
        override val playbackState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
        var waveformId = ""; var preparedId = ""; var position = 0L; var renamed = ""
        var failWaveform = false; var generateCalls = 0
        override fun observeProjects(): Flow<List<AudioProject>> = flowOf(emptyList())
        override fun searchProjects(query: String): Flow<List<AudioProject>> = flowOf(emptyList())
        override fun observeProject(projectId: String): Flow<AudioProject?> = projectFlow
        override suspend fun getProject(projectId: String): AppResult<AudioProject> = AppResult.Success(projectFlow.value!!)
        override suspend fun renameProject(projectId: String, title: String): AppResult<Unit> { renamed = title; return AppResult.Success(Unit) }
        override suspend fun deleteProject(projectId: String): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun loadWaveform(projectId: String, variantId: String): AppResult<StaticWaveform> { waveformId = variantId; return AppResult.Success(StaticWaveform(10000, 10, listOf(.5f))) }
        override suspend fun getOrGenerateWaveform(projectId: String, variantId: String): AppResult<StaticWaveform> {
            generateCalls++; waveformId = variantId
            return if (failWaveform) AppResult.Failure(AppError("waveform_failed", true)) else loadWaveform(projectId, variantId)
        }
        override suspend fun preparePlayback(variant: AudioVariant): AppResult<Unit> { preparedId = variant.id; playbackState.value = PlaybackState.Ready(variant.durationMs); return AppResult.Success(Unit) }
        override fun play(): AppResult<Unit> { playbackState.value = PlaybackState.Playing(position, 10000); return AppResult.Success(Unit) }
        override fun pausePlayback(): AppResult<Unit> { playbackState.value = PlaybackState.Paused(position, 10000); return AppResult.Success(Unit) }
        override fun seekTo(positionMs: Long): AppResult<Unit> { position = positionMs; playbackState.value = PlaybackState.Paused(positionMs, 10000); return AppResult.Success(Unit) }
        override suspend fun switchPlaybackKeepingPosition(variant: AudioVariant): AppResult<Unit> = AppResult.Success(Unit)
    }
    private class Editing : EditingGateway {
        var calls = 0; var source = ""; var preset = PresetOption.DEFAULT; var gate: CompletableDeferred<Unit>? = null
        override suspend fun startEnhancement(projectId: String, variantId: String, preset: PresetOption): AppResult<EnhancementTask> {
            calls++; source = variantId; this.preset = preset; gate?.await()
            return AppResult.Success(EnhancementTask("task", projectId, variantId, "out", preset, 0))
        }
        override suspend fun importAudio(uri: Uri): AppResult<String> = TODO()
        override suspend fun previewTrim(projectId: String, variantId: String, range: TrimRange): AppResult<Long> = TODO()
        override suspend fun applyTrim(projectId: String, variantId: String, range: TrimRange): AppResult<TrimResult> = TODO()
        override fun observeEnhancement(taskId: String): Flow<EnhancementTaskState?> = TODO()
        override suspend fun cancelEnhancement(taskId: String): AppResult<Unit> = TODO()
        override suspend fun retryEnhancement(taskId: String): AppResult<EnhancementTask> = TODO()
        override suspend fun awaitEnhancement(taskId: String): AppResult<EnhancementResult> = TODO()
        override suspend fun exportToDownloads(projectId: String, variantId: String): AppResult<ExportResult> = TODO()
        override suspend fun export(request: ExportRequest, onProgress: (ExportProgress) -> Unit): AppResult<ExportResult> = TODO()
        override suspend fun prepareShare(projectId: String, variantId: String): AppResult<Intent> = TODO()
    }
}
