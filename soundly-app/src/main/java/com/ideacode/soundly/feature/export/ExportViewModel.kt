package com.ideacode.soundly.feature.export

import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ideacode.soundly.common.error.AppError
import com.ideacode.soundly.common.error.AppResult
import com.ideacode.soundly.common.state.UiEffect
import com.ideacode.soundly.common.state.UiEffectQueue
import com.ideacode.soundly.data.sdk.EditingGateway
import com.ideacode.soundly.data.sdk.ProjectGateway
import com.ideacode.soundly.data.sdk.SettingsStorageGateway
import com.ideacode.soundly.domain.mainflow.ExportFormatOptions
import com.ideacode.soundly_sdk.api.model.domain.AudioFormat
import com.ideacode.soundly_sdk.api.model.domain.AudioProject
import com.ideacode.soundly_sdk.api.model.domain.AudioVariant
import com.ideacode.soundly_sdk.api.model.export.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

fun interface ExportedFileVerifier { suspend fun exists(uri: String): Boolean }

class ExportAudioUseCase(private val editing: EditingGateway) {
    suspend operator fun invoke(request: ExportRequest, onProgress: (ExportProgress) -> Unit) = editing.export(request, onProgress)
}

data class ExportScreenState(
    val loading: Boolean = true,
    val restoring: Boolean = false,
    val project: AudioProject? = null,
    val variant: AudioVariant? = null,
    val missing: Boolean = false,
    val name: String = "",
    val format: AudioFormat? = null,
    val formats: List<AudioFormat> = emptyList(),
    val preferenceUnavailable: Boolean = false,
    val nameError: Boolean = false,
    val exporting: Boolean = false,
    val permissionPending: Boolean = false,
    val sharing: Boolean = false,
    val leaving: Boolean = false,
    val progress: ExportProgress? = null,
    val error: AppError? = null,
    val interrupted: Boolean = false,
    val savedFileMissing: Boolean = false,
    val result: ExportResult? = null
) {
    val busy get() = exporting || permissionPending || sharing || leaving
    val exportBlocked get() = error?.code in setOf("invalid_request", "file_not_found", "invalid_audio_file", "unsupported_format", "project_not_found")
}
sealed interface ExportEffect : UiEffect {
    data object RequestStoragePermission : ExportEffect
    data class OpenShare(val intent: Intent) : ExportEffect
    data class Leave(val toLibrary: Boolean) : ExportEffect
}

class ExportViewModel(
    private val saved: SavedStateHandle,
    private val projects: ProjectGateway,
    private val settings: SettingsStorageGateway,
    private val editing: EditingGateway,
    private val verifier: ExportedFileVerifier,
    private val journal: ExportJournal = NoExportJournal
) : ViewModel() {
    private val projectId = saved.get<String>("projectId").orEmpty()
    private val variantId = saved.get<String>("variantId").orEmpty()
    private val exporter = ExportAudioUseCase(editing)
    private val queue = UiEffectQueue<ExportEffect>()
    val effects = queue.pending
    private val mutableState = MutableStateFlow(ExportScreenState(interrupted = saved.get<Boolean>("export_in_flight") == true, restoring = true))
    val uiState = mutableState.asStateFlow()
    private var initialized = false

    init {
        saved["export_in_flight"] = false
        viewModelScope.launch {
            val entry = try { journal.read() } catch (e: CancellationException) { throw e } catch (_: Exception) { ExportJournalEntry(pending = true) }
            val result = restoredResult() ?: entry?.result
            if (result != null) {
                val exists = try { verifier.exists(result.destinationUri) } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
                if (exists) {
                    storeResult(result)
                    mutableState.update { it.copy(result = result, interrupted = false, restoring = false) }
                } else {
                    clearResult()
                    mutableState.update { it.copy(savedFileMissing = true, restoring = false) }
                }
            } else {
                val incompleteResult = saved.get<String>("export_uri") != null
                if (incompleteResult) clearResult()
                mutableState.update { it.copy(interrupted = it.interrupted || entry?.pending == true,
                    savedFileMissing = incompleteResult, restoring = false) }
            }
        }
        viewModelScope.launch {
            val preferred = try { settings.observeSettings().first().outputFormat } catch (e: CancellationException) { throw e } catch (_: Exception) { AudioFormat.UNKNOWN }
            try {
                projects.observeProject(projectId).collect { project ->
                    val variant = project?.let { p -> (listOf(p.original) + p.variants).firstOrNull { it.id == variantId && variantId.isNotBlank() } }
                    val available = variant?.let { ExportFormatOptions.availableFor(it.format) }.orEmpty()
                    if (!initialized && project != null && variant != null) {
                        initialized = true
                        val priorFormat = saved.get<String>("export_format")?.let { value -> AudioFormat.entries.firstOrNull { it.name == value } }
                        val selected = priorFormat?.takeIf { it in available } ?: ExportFormatOptions.initialFormat(variant.format, preferred)
                        val name = saved.get<String>("export_name") ?: initialName(project.title, variant)
                        saved["export_name"] = name
                        if (selected != null) saved["export_format"] = selected.name
                        mutableState.update { it.copy(loading = false, project = project, variant = variant, missing = false,
                            name = name, formats = available, format = selected,
                            preferenceUnavailable = preferred != AudioFormat.UNKNOWN && preferred !in available) }
                    } else mutableState.update { it.copy(loading = false, project = project, variant = variant,
                        missing = project == null || variant == null, formats = available,
                        format = it.format?.takeIf { f -> f in available }) }
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                mutableState.update { it.copy(loading = false, missing = true) }
            }
        }
    }

    fun updateName(value: String) {
        if (uiState.value.busy || uiState.value.result != null) return
        saved["export_name"] = value
        mutableState.update { it.copy(name = value, nameError = false, error = null) }
    }
    fun selectFormat(value: AudioFormat) {
        if (uiState.value.busy || value !in uiState.value.formats) return
        saved["export_format"] = value.name
        mutableState.update { it.copy(format = value, error = null) }
    }
    fun save(needsPermission: Boolean) {
        val s = uiState.value
        if (s.busy || s.restoring || s.result != null || s.missing || s.exportBlocked || s.variant == null || s.format == null) return
        if (!validName(s.name)) { mutableState.update { it.copy(nameError = true) }; return }
        if (needsPermission) {
            mutableState.update { it.copy(permissionPending = true, error = null) }
            queue.post(ExportEffect.RequestStoragePermission)
        } else startExport()
    }
    fun permissionResult(granted: Boolean) {
        if (!uiState.value.permissionPending) return
        mutableState.update { it.copy(permissionPending = false) }
        if (granted) startExport() else mutableState.update { it.copy(error = AppError("permission_denied", false)) }
    }
    private fun startExport() {
        val s = uiState.value
        val format = s.format ?: return
        if (s.busy || s.variant == null || s.missing || !validName(s.name)) return
        saved["export_in_flight"] = true
        mutableState.update { it.copy(exporting = true, error = null, interrupted = false, savedFileMissing = false, progress = null) }
        viewModelScope.launch {
            var pending = false
            try {
                journal.markPending()
                pending = true
                val request = ExportRequest(projectId, variantId, s.name.trim(), format, ExportDestination.Downloads, FileConflictStrategy.RENAME)
                when (val result = exporter(request) { progress -> mutableState.update { it.copy(progress = progress) } }) {
                    is AppResult.Success -> {
                        storeResult(result.value)
                        // A failed journal update cannot turn an already written file into a failed export.
                        try { journal.markComplete(result.value) } catch (e: CancellationException) { throw e } catch (_: Exception) { /* pending record still prompts a manual check */ }
                        mutableState.update { it.copy(result = result.value, exporting = false, progress = null) }
                    }
                    is AppResult.Failure -> {
                        // A destination can be published before a provider reports failure.
                        // Keep the record until the user explicitly retries or leaves.
                        mutableState.update { it.copy(error = result.error, interrupted = true, exporting = false, progress = null) }
                    }
                }
                saved["export_in_flight"] = false
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                saved["export_in_flight"] = false
                mutableState.update { it.copy(error = AppError("export_failed", true), interrupted = pending, exporting = false, progress = null) }
            }
        }
    }
    fun share() {
        val s = uiState.value
        if (s.busy || s.restoring || s.variant == null || s.missing) return
        mutableState.update { it.copy(sharing = true, error = null) }
        viewModelScope.launch {
            try {
                when (val result = editing.prepareShare(projectId, variantId)) {
                    is AppResult.Success -> queue.post(ExportEffect.OpenShare(result.value))
                    is AppResult.Failure -> mutableState.update { it.copy(sharing = false, error = result.error) }
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                mutableState.update { it.copy(sharing = false, error = AppError("share_failed", true)) }
            }
        }
    }
    fun acknowledge(id: Long, shareHandled: Boolean = false) {
        queue.acknowledge(id)
        if (shareHandled) mutableState.update { it.copy(sharing = false) }
    }
    fun leaveNavigationFailed() { mutableState.update { it.copy(leaving = false, error = AppError("file_operation_failed", true)) } }
    fun shareLaunchFailed() { mutableState.update { it.copy(sharing = false, error = AppError("share_unavailable", true)) } }
    fun leaveSuccess(toLibrary: Boolean) {
        if (uiState.value.busy || uiState.value.result == null) return
        mutableState.update { it.copy(leaving = true) }
        viewModelScope.launch {
            try {
                journal.clear()
                clearResult()
                queue.post(ExportEffect.Leave(toLibrary))
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                mutableState.update { it.copy(leaving = false, error = AppError("file_operation_failed", true)) }
            }
        }
    }

    private fun storeResult(result: ExportResult) {
        saved["export_uri"] = result.destinationUri; saved["export_result_name"] = result.displayName
        saved["export_result_format"] = result.format.name; saved["export_result_size"] = result.fileSizeBytes
        saved["export_result_quality"] = result.quality.name
    }
    private fun clearResult() { listOf("export_uri", "export_result_name", "export_result_format", "export_result_size", "export_result_quality").forEach { saved.remove<Any>(it) } }
    private fun restoredResult(): ExportResult? {
        val uri = saved.get<String>("export_uri") ?: return null
        val name = saved.get<String>("export_result_name") ?: return null
        val format = AudioFormat.entries.firstOrNull { it.name == saved.get<String>("export_result_format") } ?: return null
        val quality = ExportQuality.entries.firstOrNull { it.name == saved.get<String>("export_result_quality") } ?: return null
        return ExportResult(uri, name, format, saved.get<Long>("export_result_size") ?: 0L, quality)
    }
    companion object {
        private val unsafe = Regex("[\\u0000-\\u001F\\u007F\\\\/:*?\"<>|]")
        fun validName(value: String): Boolean {
            val name = value.trim()
            return name.isNotEmpty() && name.length <= 100 && name.toByteArray(Charsets.UTF_8).size <= 180 &&
                !name.startsWith('.') && !unsafe.containsMatchIn(name) && !name.endsWith('.') &&
                AudioFormat.entries.filter { it != AudioFormat.UNKNOWN }.none { name.endsWith(".${it.name}", true) }
        }
        private fun initialName(title: String, variant: AudioVariant): String {
            var safe = title.replace(unsafe, "_").trim('.', ' ').take(90).ifBlank { "Soundly" }
            AudioFormat.entries.filter { it != AudioFormat.UNKNOWN }.firstOrNull { safe.endsWith(".${it.name}", true) }?.let {
                safe = safe.dropLast(it.name.length + 1).trimEnd('.', ' ').ifBlank { "Soundly" }
            }
            val suffix = when (variant.variantType) {
                com.ideacode.soundly_sdk.api.model.domain.VariantType.ENHANCED -> "_增强"
                com.ideacode.soundly_sdk.api.model.domain.VariantType.TRIMMED -> "_裁剪"
                com.ideacode.soundly_sdk.api.model.domain.VariantType.ORIGINAL -> ""
            }
            val limit = 180 - suffix.toByteArray(Charsets.UTF_8).size
            val output = StringBuilder()
            var bytes = 0
            var offset = 0
            while (offset < safe.length) {
                val codePoint = Character.codePointAt(safe, offset)
                val part = String(Character.toChars(codePoint))
                val size = part.toByteArray(Charsets.UTF_8).size
                if (bytes + size > limit) break
                output.append(part); bytes += size; offset += Character.charCount(codePoint)
            }
            return output.toString().trimEnd('.', ' ').ifBlank { "Soundly" } + suffix
        }
    }
}
