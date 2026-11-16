package com.ideacode.soundly.data.sdk

import android.content.Intent
import android.net.Uri
import com.ideacode.soundly.common.error.AppResult
import com.ideacode.soundly_sdk.api.capability.EnhancementResult
import com.ideacode.soundly_sdk.api.capability.TrimRange
import com.ideacode.soundly_sdk.api.capability.TrimResult
import com.ideacode.soundly_sdk.api.capability.TrimPreview
import com.ideacode.soundly_sdk.api.model.audio.dsp.PresetOption
import com.ideacode.soundly_sdk.api.model.enhancement.EnhancementRequest
import com.ideacode.soundly_sdk.api.model.enhancement.EnhancementTask
import com.ideacode.soundly_sdk.api.model.enhancement.EnhancementTaskState
import com.ideacode.soundly_sdk.api.model.export.ExportDestination
import com.ideacode.soundly_sdk.api.model.export.ExportResult
import com.ideacode.soundly_sdk.api.model.export.ExportRequest
import com.ideacode.soundly_sdk.api.model.export.ExportProgress
import com.ideacode.soundly_sdk.api.model.export.ShareRequest
import com.ideacode.soundly_sdk.api.model.importer.AudioImportRequest
import com.ideacode.soundly_sdk.api.model.importer.AudioImportResult
import com.ideacode.soundly_sdk.api.model.importer.ImportOperationStatus
import com.ideacode.soundly.common.error.AppError
import kotlinx.coroutines.flow.Flow

/** SDK editing, enhancement, export and import boundary for M3 features. */
interface EditingGateway {
    suspend fun importAudio(uri: Uri): AppResult<String>
    suspend fun importAudio(operationId: String, uri: Uri): AppResult<AudioImportResult> = AppResult.Failure(AppError("import_unavailable", false))
    suspend fun inspectImport(operationId: String): AppResult<ImportOperationStatus> = AppResult.Failure(AppError("import_unavailable", false))
    suspend fun previewTrim(projectId: String, variantId: String, range: TrimRange): AppResult<Long>
    suspend fun previewTrimDetails(projectId: String, variantId: String, range: TrimRange): AppResult<TrimPreview> =
        AppResult.Failure(AppError("trim_preview_unavailable", false))
    suspend fun applyTrim(projectId: String, variantId: String, range: TrimRange): AppResult<TrimResult>
    suspend fun startEnhancement(projectId: String, variantId: String, preset: PresetOption): AppResult<EnhancementTask>
    fun observeEnhancement(taskId: String): Flow<EnhancementTaskState?>
    suspend fun cancelEnhancement(taskId: String): AppResult<Unit>
    suspend fun retryEnhancement(taskId: String): AppResult<EnhancementTask>
    suspend fun awaitEnhancement(taskId: String): AppResult<EnhancementResult>
    suspend fun exportToDownloads(projectId: String, variantId: String): AppResult<ExportResult>
    suspend fun export(request: ExportRequest, onProgress: (ExportProgress) -> Unit = {}): AppResult<ExportResult>
    suspend fun prepareShare(projectId: String, variantId: String): AppResult<Intent>
}

class DefaultEditingGateway(
    private val provider: SoundlySdkProvider
) : EditingGateway {
    private val sdk get() = provider.requireSdk()

    override suspend fun importAudio(uri: Uri): AppResult<String> = sdk.importer.importAudio(
        AudioImportRequest(source = uri)
    ).toAppResult().map { it.project.id }

    override suspend fun importAudio(operationId: String, uri: Uri): AppResult<AudioImportResult> =
        sdk.importer.importAudio(operationId, AudioImportRequest(uri)).toAppResult()

    override suspend fun inspectImport(operationId: String): AppResult<ImportOperationStatus> =
        sdk.importer.inspectImport(operationId).toAppResult()

    override suspend fun previewTrim(
        projectId: String,
        variantId: String,
        range: TrimRange
    ): AppResult<Long> = sdk.trimmer.previewTrim(projectId, variantId, range)
        .toAppResult().map { it.durationMs }

    override suspend fun previewTrimDetails(projectId: String, variantId: String, range: TrimRange): AppResult<TrimPreview> =
        sdk.trimmer.previewTrim(projectId, variantId, range).toAppResult()

    override suspend fun applyTrim(
        projectId: String,
        variantId: String,
        range: TrimRange
    ): AppResult<TrimResult> = sdk.trimmer.applyTrim(projectId, variantId, range).toAppResult()

    override suspend fun startEnhancement(
        projectId: String,
        variantId: String,
        preset: PresetOption
    ): AppResult<EnhancementTask> = sdk.enhancer.enhance(
        EnhancementRequest(projectId, variantId, preset)
    ).toAppResult()

    override fun observeEnhancement(taskId: String): Flow<EnhancementTaskState?> =
        sdk.enhancer.observe(taskId)

    override suspend fun cancelEnhancement(taskId: String): AppResult<Unit> =
        sdk.enhancer.cancel(taskId).toAppResult()

    override suspend fun retryEnhancement(taskId: String): AppResult<EnhancementTask> =
        sdk.enhancer.retry(taskId).toAppResult()

    override suspend fun awaitEnhancement(taskId: String): AppResult<EnhancementResult> =
        sdk.enhancer.await(taskId).toAppResult()

    override suspend fun exportToDownloads(projectId: String, variantId: String): AppResult<ExportResult> =
        sdk.exporter.exportUsingDefaults(projectId, variantId, ExportDestination.Downloads).toAppResult()

    override suspend fun export(request: ExportRequest, onProgress: (ExportProgress) -> Unit): AppResult<ExportResult> =
        sdk.exporter.export(request, onProgress).toAppResult()

    override suspend fun prepareShare(projectId: String, variantId: String): AppResult<Intent> =
        sdk.exporter.prepareShare(ShareRequest(projectId, variantId)).toAppResult().map { it.sendIntent }
}
