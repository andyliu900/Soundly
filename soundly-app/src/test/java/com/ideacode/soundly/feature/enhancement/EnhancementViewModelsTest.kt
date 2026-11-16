package com.ideacode.soundly.feature.enhancement

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
class EnhancementViewModelsTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun teardown() = Dispatchers.resetMain()
    private val original = AudioVariant("/original", PresetOption.DEFAULT, true, 44100, 1, 10000, id = "original")
    private val enhanced = original.copy(filePath = "/enhanced", isOriginal = false, id = "enhanced", presetOption = PresetOption.AI, variantType = VariantType.ENHANCED, sourceVariantId = "original")
    private val project = AudioProject("p", "test", AudioSourceType.RECORDED, null, original, listOf(enhanced), createdAt = 0, updatedAt = 0)
    @Test fun `switch succeeds at same position and failure restores prior source and selection`() = runTest(dispatcher) {
        val gateway = Projects(project)
        val model = EnhancementResultViewModel(SavedStateHandle(mapOf("projectId" to "p", "originalVariantId" to "original", "enhancedVariantId" to "enhanced")), gateway)
        model.setActive(true); advanceUntilIdle()
        assertEquals("enhanced", model.uiState.value.selectedId)
        model.dispatch(ResultAction.Seek(4200)); advanceUntilIdle()
        model.dispatch(ResultAction.Toggle); advanceUntilIdle()
        model.dispatch(ResultAction.Select("original")); advanceUntilIdle()
        assertEquals("original", model.uiState.value.selectedId); assertEquals(4200, gateway.position)
        gateway.failSwitch = true
        model.dispatch(ResultAction.Select("enhanced")); advanceUntilIdle()
        assertEquals("original", model.uiState.value.selectedId); assertEquals("original", gateway.preparedId)
        assertEquals(4200, gateway.position); assertTrue(gateway.playbackState.value is PlaybackState.Playing)
        assertEquals("h6_switch_failed", (model.effects.value.single().value as ResultEffect.Error).error.code)
    }
    @Test fun `missing explicit result version never falls back`() = runTest(dispatcher) {
        val model = EnhancementResultViewModel(SavedStateHandle(mapOf("projectId" to "p", "originalVariantId" to "missing", "enhancedVariantId" to "enhanced")), Projects(project))
        advanceUntilIdle(); assertTrue(model.uiState.value.missing); assertNull(model.uiState.value.source)
    }
    @Test fun `result refuses an unrelated enhancement and restores only valid selection`() = runTest(dispatcher) {
        val trimmed = original.copy(id = "trimmed", isOriginal = false, variantType = VariantType.TRIMMED, sourceVariantId = "original", durationMs = 6000)
        val result = enhanced.copy(sourceVariantId = "trimmed", durationMs = 5000)
        val imported = project.copy(sourceType = AudioSourceType.IMPORTED, variants = listOf(trimmed, result))
        val gateway = Projects(imported)
        val args = mapOf("projectId" to "p", "originalVariantId" to "trimmed", "enhancedVariantId" to "enhanced", "h6_selected" to "unrelated")
        val model = EnhancementResultViewModel(SavedStateHandle(args), gateway)
        model.setActive(true); advanceUntilIdle()
        assertFalse(model.uiState.value.missing); assertEquals("enhanced", model.uiState.value.selectedId)
        model.dispatch(ResultAction.Select("trimmed")); advanceUntilIdle()
        assertEquals("trimmed", model.uiState.value.selectedId)
        gateway.projectFlow.value = imported.copy(variants = listOf(trimmed, result.copy(sourceVariantId = "original")))
        advanceUntilIdle(); assertTrue(model.uiState.value.missing)
    }
    @Test fun `switch to a shorter input clamps the preserved position`() = runTest(dispatcher) {
        val short = original.copy(durationMs = 2000)
        val long = enhanced.copy(durationMs = 6000)
        val gateway = Projects(project.copy(original = short, variants = listOf(long)))
        val model = EnhancementResultViewModel(SavedStateHandle(mapOf("projectId" to "p", "originalVariantId" to "original", "enhancedVariantId" to "enhanced")), gateway)
        model.setActive(true); advanceUntilIdle()
        model.dispatch(ResultAction.Seek(4500)); advanceUntilIdle()
        model.dispatch(ResultAction.Select("original")); advanceUntilIdle()
        assertEquals("original", model.uiState.value.selectedId); assertEquals(2000L, gateway.position)
    }
    @Test fun `retry replaces task id once and unknown task retains source context`() = runTest(dispatcher) {
        val gateway = Editing(); val task = EnhancementTask("old", "p", "original", "out", PresetOption.AI, 0)
        gateway.states["old"] = MutableStateFlow(EnhancementTaskState.Failed(task, com.ideacode.soundly_sdk.api.error.SoundlyError.ProcessingFailed(), EnhancementStage.PROCESSING, 0))
        val gate = CompletableDeferred<Unit>(); gateway.gate = gate
        val saved = SavedStateHandle(mapOf("taskId" to "old", "projectId" to "p", "sourceVariantId" to "original"))
        val model = EnhancementProgressViewModel(saved, gateway); advanceUntilIdle()
        model.dispatch(ProgressAction.Retry); model.dispatch(ProgressAction.Retry); runCurrent(); assertEquals(1, gateway.retryCalls)
        gate.complete(Unit); advanceUntilIdle(); assertEquals("retry", saved.get<String>("activeTaskId")); assertTrue(model.uiState.value.task is EnhancementTaskState.Running)
        val unknown = EnhancementProgressViewModel(SavedStateHandle(mapOf("taskId" to "absent", "projectId" to "p", "sourceVariantId" to "original")), gateway)
        advanceUntilIdle(); assertTrue(unknown.uiState.value.unknown); assertEquals("original", unknown.uiState.value.sourceId)
    }
    @Test fun `cancel is serialized and nonretryable failure never starts retry`() = runTest(dispatcher) {
        val gateway = Editing()
        val task = EnhancementTask("running", "p", "original", "out", PresetOption.AI, 0)
        gateway.states["running"] = MutableStateFlow(EnhancementTaskState.Running(task, EnhancementStage.PROCESSING, 30, 0))
        val gate = CompletableDeferred<Unit>(); gateway.gate = gate
        val model = EnhancementProgressViewModel(SavedStateHandle(mapOf("taskId" to "running")), gateway)
        advanceUntilIdle(); model.dispatch(ProgressAction.Cancel); model.dispatch(ProgressAction.Cancel); runCurrent()
        assertEquals(1, gateway.cancelCalls); assertTrue(model.uiState.value.busy)
        gateway.states["running"]!!.value = EnhancementTaskState.Failed(task, com.ideacode.soundly_sdk.api.error.SoundlyError.InvalidRequest(), EnhancementStage.PROCESSING, 0)
        gate.complete(Unit); advanceUntilIdle()
        model.dispatch(ProgressAction.Retry); advanceUntilIdle(); assertEquals(0, gateway.retryCalls)
        assertEquals("p", model.uiState.value.projectId); assertEquals("original", model.uiState.value.sourceId)
    }
    private class Projects(project: AudioProject) : ProjectGateway {
        val projectFlow = MutableStateFlow<AudioProject?>(project)
        override val playbackState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
        var waveformId = ""; var preparedId = ""; var position = 0L; var renamed = ""; var failSwitch = false; var switches = 0
        override fun observeProjects(): Flow<List<AudioProject>> = flowOf(emptyList())
        override fun searchProjects(query: String): Flow<List<AudioProject>> = flowOf(emptyList())
        override fun observeProject(projectId: String): Flow<AudioProject?> = projectFlow
        override suspend fun getProject(projectId: String): AppResult<AudioProject> = AppResult.Success(projectFlow.value!!)
        override suspend fun renameProject(projectId: String, title: String): AppResult<Unit> { renamed = title; return AppResult.Success(Unit) }
        override suspend fun deleteProject(projectId: String): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun loadWaveform(projectId: String, variantId: String): AppResult<StaticWaveform> { waveformId = variantId; return AppResult.Success(StaticWaveform(10000, 10, listOf(.5f))) }
        override suspend fun preparePlayback(variant: AudioVariant): AppResult<Unit> { preparedId = variant.id; playbackState.value = PlaybackState.Ready(variant.durationMs); return AppResult.Success(Unit) }
        override fun play(): AppResult<Unit> { playbackState.value = PlaybackState.Playing(position, 10000); return AppResult.Success(Unit) }
        override fun pausePlayback(): AppResult<Unit> { playbackState.value = PlaybackState.Paused(position, 10000); return AppResult.Success(Unit) }
        override fun seekTo(positionMs: Long): AppResult<Unit> { position = positionMs; playbackState.value = PlaybackState.Paused(positionMs, 10000); return AppResult.Success(Unit) }
        override suspend fun switchPlaybackKeepingPosition(variant: AudioVariant): AppResult<Unit> {
            switches++
            if (failSwitch) { playbackState.value = PlaybackState.Idle; return AppResult.Failure(AppError("playback_failed", true)) }
            preparedId = variant.id
            return AppResult.Success(Unit)
        }
    }
    private class Editing : EditingGateway {
        var calls = 0; var source = ""; var preset = PresetOption.DEFAULT; var gate: CompletableDeferred<Unit>? = null
        val states = mutableMapOf<String, MutableStateFlow<EnhancementTaskState?>>()
        var retryCalls = 0; var cancelCalls = 0
        override suspend fun startEnhancement(projectId: String, variantId: String, preset: PresetOption): AppResult<EnhancementTask> {
            calls++; source = variantId; this.preset = preset; gate?.await()
            return AppResult.Success(EnhancementTask("task", projectId, variantId, "out", preset, 0))
        }
        override suspend fun importAudio(uri: Uri): AppResult<String> = TODO()
        override suspend fun previewTrim(projectId: String, variantId: String, range: TrimRange): AppResult<Long> = TODO()
        override suspend fun applyTrim(projectId: String, variantId: String, range: TrimRange): AppResult<TrimResult> = TODO()
        override fun observeEnhancement(taskId: String): Flow<EnhancementTaskState?> = states.getOrPut(taskId) { MutableStateFlow(null) }
        override suspend fun cancelEnhancement(taskId: String): AppResult<Unit> { cancelCalls++; gate?.await(); return AppResult.Success(Unit) }
        override suspend fun retryEnhancement(taskId: String): AppResult<EnhancementTask> {
            retryCalls++; gate?.await()
            val task = states[taskId]!!.value!!.task.copy(taskId = "retry")
            states["retry"] = MutableStateFlow(EnhancementTaskState.Running(task, EnhancementStage.PREPARING, 0, 0))
            return AppResult.Success(task)
        }
        override suspend fun awaitEnhancement(taskId: String): AppResult<EnhancementResult> = TODO()
        override suspend fun exportToDownloads(projectId: String, variantId: String): AppResult<ExportResult> = TODO()
        override suspend fun export(request: ExportRequest, onProgress: (ExportProgress) -> Unit): AppResult<ExportResult> = TODO()
        override suspend fun prepareShare(projectId: String, variantId: String): AppResult<Intent> = TODO()
    }
}
