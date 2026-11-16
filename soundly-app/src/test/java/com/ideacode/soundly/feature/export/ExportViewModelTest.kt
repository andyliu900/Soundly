package com.ideacode.soundly.feature.export

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import com.ideacode.soundly.common.error.*
import com.ideacode.soundly.data.sdk.*
import com.ideacode.soundly_sdk.api.capability.*
import com.ideacode.soundly_sdk.api.model.audio.dsp.PresetOption
import com.ideacode.soundly_sdk.api.model.domain.*
import com.ideacode.soundly_sdk.api.model.enhancement.*
import com.ideacode.soundly_sdk.api.model.export.*
import com.ideacode.soundly_sdk.api.model.playback.PlaybackState
import com.ideacode.soundly_sdk.api.model.recording.StaticWaveform
import com.ideacode.soundly_sdk.api.model.settings.SoundlySettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class ExportViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun cleanup() = Dispatchers.resetMain()
    private val original = AudioVariant("/source", PresetOption.RAW, true, 44100, 1, 5000, id = "original", format = AudioFormat.M4A)
    private val enhanced = original.copy(id = "selected", isOriginal = false, variantType = VariantType.ENHANCED)
    private val project = AudioProject("project", "录音", AudioSourceType.RECORDED, RecordingScene.MEETING, original, listOf(enhanced), createdAt = 0, updatedAt = 0)
    private fun handle() = SavedStateHandle(mapOf("projectId" to "project", "variantId" to "selected"))
    private fun model(saved: SavedStateHandle, editing: Editing, verifier: ExportedFileVerifier = ExportedFileVerifier { true }, projectValue: AudioProject? = project, journal: ExportJournal = NoExportJournal) =
        ExportViewModel(saved, Projects(projectValue), Settings(), editing, verifier, journal)

    @Test fun `export uses selected source validates input reports progress and prevents duplicates`() = runTest(dispatcher) {
        val saved = handle(); val editing = Editing(); editing.gate = CompletableDeferred()
        val vm = model(saved, editing); advanceUntilIdle()
        assertEquals(listOf(AudioFormat.M4A, AudioFormat.WAV), vm.uiState.value.formats)
        assertEquals(AudioFormat.M4A, vm.uiState.value.format)
        assertEquals("M4A", saved.get<String>("export_format"))
        assertEquals("录音_增强", saved.get<String>("export_name"))
        assertTrue(vm.uiState.value.preferenceUnavailable)
        vm.updateName("  "); vm.save(false); assertTrue(vm.uiState.value.nameError); assertEquals(0, editing.requests.size)
        vm.updateName("../bad"); vm.save(false); assertTrue(vm.uiState.value.nameError)
        assertFalse(ExportViewModel.validName("录".repeat(70)))
        assertTrue(ExportViewModel.validName("录".repeat(40)))
        vm.updateName("会议纪要"); vm.selectFormat(AudioFormat.WAV)
        vm.save(false); vm.save(false); vm.share(); runCurrent()
        assertEquals(1, editing.requests.size); assertEquals(0, editing.shareCalls)
        assertEquals("selected", editing.requests.single().variantId)
        assertEquals("会议纪要", editing.requests.single().displayName)
        assertEquals(AudioFormat.WAV, editing.requests.single().format)
        assertEquals(FileConflictStrategy.RENAME, editing.requests.single().conflictStrategy)
        assertEquals(ExportStage.WRITING, vm.uiState.value.progress?.stage)
        assertTrue(vm.uiState.value.exporting)
        editing.gate!!.complete(Unit); advanceUntilIdle()
        assertEquals("会议纪要 (1).wav", vm.uiState.value.result?.displayName)
        assertEquals("content://audio/123", saved.get<String>("export_uri"))
        assertFalse(vm.uiState.value.exporting)
        vm.share(); advanceUntilIdle()
        assertEquals("selected", editing.shareVariant)
        assertEquals("share_failed", vm.uiState.value.error?.code)
    }

    @Test fun `permission is requested once and denied or granted explicitly`() = runTest(dispatcher) {
        val editing = Editing(); val vm = model(handle(), editing); advanceUntilIdle()
        vm.save(true); vm.save(true); runCurrent()
        assertEquals(1, vm.effects.value.size); assertEquals(0, editing.requests.size)
        vm.permissionResult(false); assertEquals("permission_denied", vm.uiState.value.error?.code)
        vm.save(true); vm.permissionResult(true); advanceUntilIdle()
        assertEquals(1, editing.requests.size)
        vm.permissionResult(true); advanceUntilIdle(); assertEquals(1, editing.requests.size)
    }

    @Test fun `trimmed source uses its own identity and a distinct default name`() = runTest(dispatcher) {
        val trimmed = original.copy(id = "trimmed", isOriginal = false, variantType = VariantType.TRIMMED, sourceVariantId = original.id)
        val imported = project.copy(sourceType = AudioSourceType.IMPORTED, variants = listOf(trimmed))
        val editing = Editing()
        val vm = model(SavedStateHandle(mapOf("projectId" to "project", "variantId" to "trimmed")), editing, projectValue = imported)
        advanceUntilIdle(); assertEquals("录音_裁剪", vm.uiState.value.name)
        vm.save(false); advanceUntilIdle()
        assertEquals("trimmed", editing.requests.single().variantId)
        vm.share(); advanceUntilIdle(); assertEquals("trimmed", editing.shareVariant)
    }

    @Test fun `failure can retry and interrupted operation never restarts itself`() = runTest(dispatcher) {
        val editing = Editing(); editing.fail = true
        val vm = model(handle(), editing); advanceUntilIdle(); vm.save(false); advanceUntilIdle()
        assertEquals("export_failed", vm.uiState.value.error?.code); assertNull(vm.uiState.value.result)
        editing.fail = false; vm.save(false); advanceUntilIdle(); assertEquals(2, editing.requests.size)
        val restored = handle(); restored["export_in_flight"] = true
        val next = model(restored, editing); advanceUntilIdle()
        assertTrue(next.uiState.value.interrupted); assertEquals(2, editing.requests.size)
    }

    @Test fun `restored result must still exist and missing source is never substituted`() = runTest(dispatcher) {
        val saved = handle().apply {
            set("export_uri", "content://audio/123"); set("export_result_name", "done.wav")
            set("export_result_format", "WAV"); set("export_result_size", 123L); set("export_result_quality", "PRESERVE_SOURCE")
        }
        val editing = Editing()
        val present = model(saved, editing); advanceUntilIdle(); assertEquals("done.wav", present.uiState.value.result?.displayName)
        val missing = model(saved, editing, ExportedFileVerifier { false }); advanceUntilIdle()
        assertTrue(missing.uiState.value.savedFileMissing); assertNull(missing.uiState.value.result); assertNull(saved.get<String>("export_uri"))
        val missingVersion = model(SavedStateHandle(mapOf("projectId" to "project", "variantId" to "absent")), editing)
        advanceUntilIdle(); assertTrue(missingVersion.uiState.value.missing)
        missingVersion.save(false); advanceUntilIdle(); assertEquals(0, editing.requests.size)
    }

    @Test fun `durable journal restores a verified result and warns for unfinished work`() = runTest(dispatcher) {
        val editing = Editing(); val journal = Journal()
        val first = model(handle(), editing, journal = journal); advanceUntilIdle()
        first.save(false); advanceUntilIdle()
        assertNotNull(journal.entry?.result); assertEquals(1, editing.requests.size)
        val afterProcessDeath = model(handle(), editing, journal = journal); advanceUntilIdle()
        assertEquals("会议纪要 (1).wav", afterProcessDeath.uiState.value.result?.displayName)
        assertEquals(1, editing.requests.size)
        afterProcessDeath.leaveSuccess(true); advanceUntilIdle()
        assertNull(journal.entry)
        assertTrue(afterProcessDeath.effects.value.single().value is ExportEffect.Leave)
        journal.entry = ExportJournalEntry(pending = true)
        val interrupted = model(handle(), editing, journal = journal); advanceUntilIdle()
        assertTrue(interrupted.uiState.value.interrupted); assertEquals(1, editing.requests.size)
        journal.entry = ExportJournalEntry(false, ExportResult("content://gone", "gone.wav", AudioFormat.WAV, 10, ExportQuality.PRESERVE_SOURCE))
        val deleted = model(handle(), editing, ExportedFileVerifier { false }, journal = journal); advanceUntilIdle()
        assertTrue(deleted.uiState.value.savedFileMissing); assertNull(deleted.uiState.value.result)
    }
    @Test fun `journal must persist before the exporter writes a file`() = runTest(dispatcher) {
        val editing = Editing(); val journal = Journal().apply { failPending = true }
        val vm = model(handle(), editing, journal = journal); advanceUntilIdle(); vm.save(false); advanceUntilIdle()
        assertEquals(0, editing.requests.size); assertEquals("export_failed", vm.uiState.value.error?.code)
    }
    @Test fun `failed export retains pending record and warns before a retry`() = runTest(dispatcher) {
        val editing = Editing().apply { fail = true }; val journal = Journal()
        val vm = model(handle(), editing, journal = journal); advanceUntilIdle()
        vm.save(false); advanceUntilIdle()
        assertTrue(vm.uiState.value.interrupted); assertTrue(journal.entry?.pending == true)
        val restored = model(handle(), editing, journal = journal); advanceUntilIdle()
        assertTrue(restored.uiState.value.interrupted); assertEquals(1, editing.requests.size)
    }
    @Test fun `unsupported sources and nonretryable errors cannot silently export`() = runTest(dispatcher) {
        val editing = Editing()
        val unknown = original.copy(id = "unknown", format = AudioFormat.UNKNOWN)
        val source = project.copy(original = unknown, variants = emptyList())
        val vm = model(SavedStateHandle(mapOf("projectId" to "project", "variantId" to "unknown")), editing, projectValue = source)
        advanceUntilIdle(); assertTrue(vm.uiState.value.formats.isEmpty()); vm.save(false); advanceUntilIdle()
        assertTrue(editing.requests.isEmpty())
        val other = model(handle(), editing); advanceUntilIdle()
        editing.failure = AppError("unsupported_format", false); other.save(false); advanceUntilIdle()
        assertTrue(other.uiState.value.exportBlocked); other.save(false); advanceUntilIdle(); assertEquals(1, editing.requests.size)
        other.selectFormat(AudioFormat.WAV); editing.failure = AppError("insufficient_storage", true)
        other.save(false); advanceUntilIdle(); assertEquals("insufficient_storage", other.uiState.value.error?.code)
        assertFalse(other.uiState.value.exportBlocked)
        editing.failure = null; other.save(false); advanceUntilIdle(); assertNotNull(other.uiState.value.result)
    }
    private class Journal : ExportJournal {
        var entry: ExportJournalEntry? = null; var failPending = false
        override suspend fun read() = entry
        override suspend fun markPending() { check(!failPending); entry = ExportJournalEntry(true) }
        override suspend fun markComplete(result: ExportResult) { entry = ExportJournalEntry(false, result) }
        override suspend fun clear() { entry = null }
    }

    private class Settings : SettingsStorageGateway {
        override fun observeSettings(): Flow<SoundlySettings> = flowOf(SoundlySettings(outputFormat = AudioFormat.MP3))
        override suspend fun updateSettings(settings: SoundlySettings): AppResult<Unit> = TODO()
        override suspend fun storageSnapshot(): AppResult<StorageSnapshot> = TODO()
        override suspend fun clearCache(): AppResult<Unit> = TODO()
        override suspend fun deleteAllLocalData(): AppResult<Unit> = TODO()
    }
    private class Projects(project: AudioProject?) : ProjectGateway {
        private val value = MutableStateFlow(project)
        override val playbackState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
        override fun observeProjects(): Flow<List<AudioProject>> = flowOf(emptyList())
        override fun searchProjects(query: String): Flow<List<AudioProject>> = flowOf(emptyList())
        override fun observeProject(projectId: String): Flow<AudioProject?> = value
        override suspend fun getProject(projectId: String): AppResult<AudioProject> = TODO()
        override suspend fun renameProject(projectId: String, title: String): AppResult<Unit> = TODO()
        override suspend fun deleteProject(projectId: String): AppResult<Unit> = TODO()
        override suspend fun loadWaveform(projectId: String, variantId: String): AppResult<StaticWaveform> = TODO()
        override suspend fun preparePlayback(variant: AudioVariant): AppResult<Unit> = TODO()
        override fun play(): AppResult<Unit> = TODO()
        override fun pausePlayback(): AppResult<Unit> = TODO()
        override fun seekTo(positionMs: Long): AppResult<Unit> = TODO()
        override suspend fun switchPlaybackKeepingPosition(variant: AudioVariant): AppResult<Unit> = TODO()
    }
    private class Editing : EditingGateway {
        val requests = mutableListOf<ExportRequest>(); var shareCalls = 0; var shareVariant = ""; var gate: CompletableDeferred<Unit>? = null; var fail = false; var failure: AppError? = null
        override suspend fun export(request: ExportRequest, onProgress: (ExportProgress) -> Unit): AppResult<ExportResult> {
            requests += request; onProgress(ExportProgress(ExportStage.WRITING, 37)); gate?.await()
            return if (failure != null || fail) AppResult.Failure(failure ?: AppError("export_failed", true)) else AppResult.Success(ExportResult("content://audio/123", "会议纪要 (1).wav", request.format, 123, ExportQuality.PRESERVE_SOURCE))
        }
        override suspend fun prepareShare(projectId: String, variantId: String): AppResult<Intent> { shareCalls++; shareVariant = variantId; return AppResult.Failure(AppError("share_failed", true)) }
        override suspend fun importAudio(uri: Uri): AppResult<String> = TODO()
        override suspend fun previewTrim(projectId: String, variantId: String, range: TrimRange): AppResult<Long> = TODO()
        override suspend fun applyTrim(projectId: String, variantId: String, range: TrimRange): AppResult<TrimResult> = TODO()
        override suspend fun startEnhancement(projectId: String, variantId: String, preset: PresetOption): AppResult<EnhancementTask> = TODO()
        override fun observeEnhancement(taskId: String): Flow<EnhancementTaskState?> = TODO()
        override suspend fun cancelEnhancement(taskId: String): AppResult<Unit> = TODO()
        override suspend fun retryEnhancement(taskId: String): AppResult<EnhancementTask> = TODO()
        override suspend fun awaitEnhancement(taskId: String): AppResult<EnhancementResult> = TODO()
        override suspend fun exportToDownloads(projectId: String, variantId: String): AppResult<ExportResult> = TODO()
    }
}
