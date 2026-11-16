package com.ideacode.soundly.feature.editing

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import com.ideacode.soundly.common.error.*
import com.ideacode.soundly.data.sdk.*
import com.ideacode.soundly_sdk.api.capability.*
import com.ideacode.soundly_sdk.api.model.audio.AudioSourceRef
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
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class TrimViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun teardown() = Dispatchers.resetMain()
    private val source = AudioVariant("/source.wav", PresetOption.RAW, true, 44100, 1, 10000, id = "source", projectId = "p", format = AudioFormat.WAV)
    private val project = AudioProject("p", "test", AudioSourceType.IMPORTED, null, source, createdAt = 0, updatedAt = 0)
    private fun handle() = SavedStateHandle(mapOf("projectId" to "p", "variantId" to "source", "durationMs" to 1L))

    @Test fun `uses actual duration validates range and preserves precise input`() = runTest(dispatcher) {
        val saved = handle(); val model = TrimViewModel(saved, Projects(project), Editing(), Journal())
        advanceUntilIdle()
        assertEquals(10000L, model.state.value.endMs)
        model.setRange(1501, 8000); assertTrue(model.state.value.valid)
        model.setRange(8000, 8000); assertFalse(model.state.value.valid)
        val restored = TrimViewModel(saved, Projects(project), Editing(), Journal()); advanceUntilIdle()
        assertEquals(8000L, restored.state.value.startMs); assertEquals(8000L, restored.state.value.endMs)
        assertEquals(3723500L, TrimTime.parse("01:02:03.500"))
        assertEquals("01:02:03.500", TrimTime.format(3723500))
        assertNull(TrimTime.parse("00:60")); assertNull(TrimTime.parse("999999999999999999999:00:00"))
    }

    @Test fun `preview stops at selected end and repeated apply creates one version`() = runTest(dispatcher) {
        val projects = Projects(project); val editing = Editing(); val journal = Journal(); val gate = CompletableDeferred<Unit>()
        editing.gate = gate
        val model = TrimViewModel(handle(), projects, editing, journal); model.setActive(true); advanceUntilIdle()
        model.setRange(1000, 3000); model.togglePreview(); runCurrent()
        assertTrue(model.state.value.playing); assertEquals(1000L, projects.position)
        projects.playbackState.value = PlaybackState.Playing(3100, 10000); advanceUntilIdle()
        assertFalse(model.state.value.playing); assertEquals(1000L, projects.position)
        model.apply(); model.apply(); runCurrent()
        assertTrue(model.state.value.busy); assertEquals(1, editing.applyCalls); assertTrue(journal.pending)
        gate.complete(Unit); advanceUntilIdle()
        assertEquals("trimmed", model.state.value.resultId); assertEquals("trimmed", journal.result)
        assertEquals(TrimRange(1000, 3000), editing.appliedRange)
    }

    @Test fun `uncertain outcome blocks repeat until explicit acknowledgement`() = runTest(dispatcher) {
        val journal = Journal().apply { pending = true }
        val editing = Editing(); val model = TrimViewModel(handle(), Projects(project), editing, journal)
        advanceUntilIdle(); assertTrue(model.state.value.uncertain)
        model.apply(); advanceUntilIdle(); assertEquals(0, editing.applyCalls)
        model.acknowledgeUncertain(); advanceUntilIdle(); assertFalse(model.state.value.uncertain)
        model.apply(); advanceUntilIdle(); assertEquals(1, editing.applyCalls)
    }
    @Test fun `preview timer stops short selection without waiting for player ticker`() = runTest(dispatcher) {
        val projects = Projects(project); val model = TrimViewModel(handle(), projects, Editing(), Journal())
        model.setActive(true); advanceUntilIdle()
        model.setRange(1000, 1050); model.togglePreview(); runCurrent()
        assertTrue(model.state.value.playing)
        advanceTimeBy(50); runCurrent()
        assertFalse(model.state.value.playing)
        assertTrue(projects.playbackState.value is PlaybackState.Paused)
        assertEquals(1000L, projects.position)
    }
    @Test fun `restored result requires a readable matching version`() = runTest(dispatcher) {
        val result = source.copy(id = "trimmed", isOriginal = false, variantType = VariantType.TRIMMED, sourceVariantId = source.id)
        val projects = Projects(project.copy(variants = listOf(result)))
        val journal = Journal().apply { this.result = "trimmed" }
        projects.readable = false
        val missing = TrimViewModel(handle(), projects, Editing(), journal); advanceUntilIdle()
        assertTrue(missing.state.value.uncertain); assertNull(missing.state.value.resultId)
        projects.readable = true
        val restored = TrimViewModel(handle(), projects, Editing(), journal); advanceUntilIdle()
        assertEquals("trimmed", restored.state.value.resultId); assertFalse(restored.state.value.uncertain)
    }

    private class Journal : TrimJournal {
        var pending = false; var result: String? = null
        override suspend fun read(): TrimJournalEntry? = if (pending || result != null) TrimJournalEntry(result) else null
        override suspend fun markPending() { pending = true; result = null }
        override suspend fun markComplete(variantId: String) { result = variantId; pending = false }
        override suspend fun clear() { pending = false; result = null }
    }
    private class Projects(project: AudioProject) : ProjectGateway {
        val current = MutableStateFlow<AudioProject?>(project)
        override val playbackState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
        var position = 0L
        var readable = true
        override fun observeProjects(): Flow<List<AudioProject>> = flowOf(emptyList())
        override fun searchProjects(query: String): Flow<List<AudioProject>> = flowOf(emptyList())
        override fun observeProject(projectId: String): Flow<AudioProject?> = current
        override suspend fun getProject(projectId: String): AppResult<AudioProject> = AppResult.Success(current.value!!)
        override suspend fun isVariantReadable(variant: AudioVariant): AppResult<Boolean> = AppResult.Success(readable)
        override suspend fun renameProject(projectId: String, title: String): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun deleteProject(projectId: String): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun loadWaveform(projectId: String, variantId: String): AppResult<StaticWaveform> = AppResult.Success(StaticWaveform(10000, 50, listOf(.1f, .4f)))
        override suspend fun preparePlayback(variant: AudioVariant): AppResult<Unit> { playbackState.value = PlaybackState.Ready(variant.durationMs); return AppResult.Success(Unit) }
        override fun play(): AppResult<Unit> { playbackState.value = PlaybackState.Playing(position, 10000); return AppResult.Success(Unit) }
        override fun pausePlayback(): AppResult<Unit> { playbackState.value = PlaybackState.Paused(position, 10000); return AppResult.Success(Unit) }
        override fun seekTo(positionMs: Long): AppResult<Unit> { position = positionMs; playbackState.value = PlaybackState.Paused(position, 10000); return AppResult.Success(Unit) }
        override suspend fun switchPlaybackKeepingPosition(variant: AudioVariant): AppResult<Unit> = AppResult.Success(Unit)
    }
    private class Editing : EditingGateway {
        var applyCalls = 0; var appliedRange: TrimRange? = null; var gate: CompletableDeferred<Unit>? = null
        override suspend fun previewTrimDetails(projectId: String, variantId: String, range: TrimRange): AppResult<TrimPreview> = AppResult.Success(TrimPreview(projectId, variantId, AudioSourceRef.LocalFile(File("/source.wav")), range, range.endMs - range.startMs, emptyList()))
        override suspend fun applyTrim(projectId: String, variantId: String, range: TrimRange): AppResult<TrimResult> { applyCalls++; appliedRange = range; gate?.await(); return AppResult.Success(TrimResult(projectId, "trimmed", range.endMs - range.startMs)) }
        override suspend fun importAudio(uri: Uri): AppResult<String> = TODO()
        override suspend fun previewTrim(projectId: String, variantId: String, range: TrimRange): AppResult<Long> = TODO()
        override suspend fun startEnhancement(projectId: String, variantId: String, preset: PresetOption): AppResult<EnhancementTask> = TODO()
        override fun observeEnhancement(taskId: String): Flow<EnhancementTaskState?> = TODO()
        override suspend fun cancelEnhancement(taskId: String): AppResult<Unit> = TODO()
        override suspend fun retryEnhancement(taskId: String): AppResult<EnhancementTask> = TODO()
        override suspend fun awaitEnhancement(taskId: String): AppResult<EnhancementResult> = TODO()
        override suspend fun exportToDownloads(projectId: String, variantId: String): AppResult<ExportResult> = TODO()
        override suspend fun export(request: ExportRequest, onProgress: (ExportProgress) -> Unit): AppResult<ExportResult> = TODO()
        override suspend fun prepareShare(projectId: String, variantId: String): AppResult<Intent> = TODO()
    }
}
