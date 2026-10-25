package com.ideacode.soundly_sdk.api.capability

import android.content.Intent
import com.ideacode.soundly_sdk.api.model.audio.AudioSourceRef
import com.ideacode.soundly_sdk.api.model.domain.AudioFormat
import com.ideacode.soundly_sdk.api.model.export.ExportRequest
import com.ideacode.soundly_sdk.api.model.export.ExportResult
import com.ideacode.soundly_sdk.api.model.export.ExportProgress
import com.ideacode.soundly_sdk.api.model.export.ExportDestination
import com.ideacode.soundly_sdk.api.model.export.FileConflictStrategy
import com.ideacode.soundly_sdk.api.model.export.ShareRequest
import com.ideacode.soundly_sdk.api.model.export.ShareResult
import com.ideacode.soundly_sdk.api.model.audio.dsp.PresetOption
import com.ideacode.soundly_sdk.api.model.audio.processor.ProcessingProgress
import com.ideacode.soundly_sdk.api.model.domain.AudioMarker
import com.ideacode.soundly_sdk.api.model.enhancement.EnhancementRequest
import com.ideacode.soundly_sdk.api.model.enhancement.EnhancementTask
import com.ideacode.soundly_sdk.api.model.enhancement.EnhancementTaskState
import com.ideacode.soundly_sdk.api.result.SoundlyResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface AudioExporter {
    suspend fun export(
        request: ExportRequest,
        onProgress: (ExportProgress) -> Unit = {}
    ): SoundlyResult<ExportResult>

    /** Uses the persisted output format and the project title as the suggested file name. */
    suspend fun exportUsingDefaults(
        projectId: String,
        variantId: String,
        destination: ExportDestination,
        conflictStrategy: FileConflictStrategy = FileConflictStrategy.RENAME,
        onProgress: (ExportProgress) -> Unit = {}
    ): SoundlyResult<ExportResult>

    /** Copies a version to SDK cache and creates a read-only ACTION_SEND intent. */
    suspend fun prepareShare(request: ShareRequest): SoundlyResult<ShareResult>

    /**
     * Creates the ACTION_CREATE_DOCUMENT intent used by a system "Save as" flow.
     * Pass the returned URI back through [ExportDestination.DocumentUri].
     */
    fun createDocumentIntent(
        displayName: String,
        format: AudioFormat
    ): SoundlyResult<Intent>
}

data class EnhancementResult(
    val projectId: String,
    val variantId: String,
    val durationMs: Long,
    val preset: PresetOption,
    /** ID of the enhancement attempt that produced this version. */
    val taskId: String = ""
)

interface AudioEnhancer {
    /** Current state of every task created during this SDK process lifetime. */
    val tasks: StateFlow<Map<String, EnhancementTaskState>>

    /** Creates and starts an enhancement task, returning before processing completes. */
    suspend fun enhance(request: EnhancementRequest): SoundlyResult<EnhancementTask>

    /** Waits for one task to reach a terminal state. */
    suspend fun await(taskId: String): SoundlyResult<EnhancementResult>

    /** Cancels a running or queued task and waits until cleanup has completed. */
    suspend fun cancel(taskId: String): SoundlyResult<Unit>

    /** Creates a new task and output variant from a retryable failed/cancelled task. */
    suspend fun retry(taskId: String): SoundlyResult<EnhancementTask>

    /** Emits null for an unknown task ID, otherwise the current and future states. */
    fun observe(taskId: String): Flow<EnhancementTaskState?>

    /**
     * Compatibility entry point for callers that need a single suspending result.
     * New integrations should use [enhance], [observe], and [await].
     */
    suspend fun enhance(
        projectId: String,
        sourceVariantId: String,
        preset: PresetOption,
        onProgress: (ProcessingProgress) -> Unit = {}
    ): SoundlyResult<EnhancementResult>
}

data class TrimRange(
    val startMs: Long,
    val endMs: Long
)

data class TrimResult(
    val projectId: String,
    val variantId: String,
    val durationMs: Long,
    /** Project markers inside the retained range, shifted to the trimmed timeline. */
    val markers: List<TrimmedMarker> = emptyList()
)

/** Marker coordinates before and after a non-destructive trim. */
data class TrimmedMarker(
    val marker: AudioMarker,
    val sourcePositionMs: Long,
    val trimmedPositionMs: Long
)

/** Validated information needed to preview a trim before creating a new version. */
data class TrimPreview(
    val projectId: String,
    val sourceVariantId: String,
    val source: AudioSourceRef,
    val range: TrimRange,
    val durationMs: Long,
    val markers: List<TrimmedMarker>
)

interface AudioTrimmer {
    /** Validates a range and maps project markers without writing a new audio file. */
    suspend fun previewTrim(
        projectId: String,
        sourceVariantId: String,
        range: TrimRange
    ): SoundlyResult<TrimPreview>

    suspend fun applyTrim(
        projectId: String,
        sourceVariantId: String,
        range: TrimRange
    ): SoundlyResult<TrimResult>
}

data class StorageSnapshot(
    val originalsBytes: Long,
    val variantsBytes: Long,
    val cacheBytes: Long,
    val availableBytes: Long,
    val recoveryBytes: Long = 0L,
    val auxiliaryBytes: Long = 0L
)

interface StorageManager {
    suspend fun snapshot(): SoundlyResult<StorageSnapshot>
    suspend fun clearCache(): SoundlyResult<Unit>
    suspend fun deleteAllLocalData(): SoundlyResult<Unit>
}
