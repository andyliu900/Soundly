package com.ideacode.soundly_sdk.editing

import com.ideacode.soundly_sdk.api.capability.*
import com.ideacode.soundly_sdk.api.error.SoundlyError
import com.ideacode.soundly_sdk.api.model.audio.dsp.PresetOption
import com.ideacode.soundly_sdk.api.model.audio.processor.ProcessingProgress
import com.ideacode.soundly_sdk.api.model.enhancement.EnhancementRequest
import com.ideacode.soundly_sdk.api.model.enhancement.EnhancementTask
import com.ideacode.soundly_sdk.api.model.export.*
import com.ideacode.soundly_sdk.api.model.importer.AudioImportRequest
import com.ideacode.soundly_sdk.api.result.SoundlyResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal class GuardedImporter(private val delegate: AudioImporter, private val gate: StorageOperationGate) : AudioImporter by delegate {
    override suspend fun importAudio(request: AudioImportRequest) = gate.work { delegate.importAudio(request) }
    override suspend fun importAudio(operationId: String, request: AudioImportRequest) = gate.work { delegate.importAudio(operationId, request) }
    override suspend fun inspectImport(operationId: String) = gate.work { delegate.inspectImport(operationId) }
}
internal class GuardedTrimmer(private val delegate: AudioTrimmer, private val gate: StorageOperationGate) : AudioTrimmer by delegate {
    override suspend fun previewTrim(projectId: String, sourceVariantId: String, range: TrimRange) = gate.work { delegate.previewTrim(projectId, sourceVariantId, range) }
    override suspend fun applyTrim(projectId: String, sourceVariantId: String, range: TrimRange) = gate.work { delegate.applyTrim(projectId, sourceVariantId, range) }
}
internal class GuardedExporter(private val delegate: AudioExporter, private val gate: StorageOperationGate) : AudioExporter by delegate {
    override suspend fun export(request: ExportRequest, onProgress: (ExportProgress) -> Unit) = gate.work { delegate.export(request, onProgress) }
    override suspend fun exportUsingDefaults(projectId: String, variantId: String, destination: ExportDestination, conflictStrategy: FileConflictStrategy, onProgress: (ExportProgress) -> Unit) =
        gate.work { delegate.exportUsingDefaults(projectId, variantId, destination, conflictStrategy, onProgress) }
    override suspend fun prepareShare(request: ShareRequest) = gate.work { delegate.prepareShare(request) }
}
internal class GuardedEnhancer(private val delegate: ProjectAudioEnhancer, private val gate: StorageOperationGate) : AudioEnhancer by delegate {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override suspend fun enhance(request: EnhancementRequest) = start { delegate.enhance(request) }
    override suspend fun retry(taskId: String) = start { delegate.retry(taskId) }
    override suspend fun enhance(projectId: String, sourceVariantId: String, preset: PresetOption, onProgress: (ProcessingProgress) -> Unit) =
        gate.work { delegate.enhance(projectId, sourceVariantId, preset, onProgress) }
    private suspend fun start(block: suspend () -> SoundlyResult<EnhancementTask>): SoundlyResult<EnhancementTask> {
        if (!gate.beginWork()) return SoundlyResult.Failure(SoundlyError.InvalidState("storageMaintenance"))
        var handedOff = false
        try {
            val result = block()
            if (result is SoundlyResult.Success) {
                scope.launch { try { delegate.awaitCleanup(result.value.taskId) } finally { gate.endWork() } }
                handedOff = true
            }
            return result
        } finally { if (!handedOff) gate.endWork() }
    }
}
