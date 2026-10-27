package com.ideacode.soundly_sdk.processor

import android.content.ContentResolver
import android.net.Uri
import com.ideacode.soundly_sdk.api.capability.AudioEnhancer
import com.ideacode.soundly_sdk.api.capability.AudioImporter
import com.ideacode.soundly_sdk.api.capability.AudioProjectRepository
import com.ideacode.soundly_sdk.api.error.SoundlyError
import com.ideacode.soundly_sdk.api.error.SoundlyErrorMapper
import com.ideacode.soundly_sdk.api.model.audio.AudioSourceRef
import com.ideacode.soundly_sdk.api.model.audio.dsp.PresetOption
import com.ideacode.soundly_sdk.core.audio.processor.state.ProcessingProcess
import com.ideacode.soundly_sdk.api.model.audio.processor.ProcessingProgress
import com.ideacode.soundly_sdk.api.model.audio.processor.ProcessingRequest
import com.ideacode.soundly_sdk.api.model.audio.processor.ProcessingResult
import com.ideacode.soundly_sdk.api.model.audio.processor.ProcessingStage
import com.ideacode.soundly_sdk.api.model.enhancement.EnhancementRequest
import com.ideacode.soundly_sdk.api.model.enhancement.EnhancementStage
import com.ideacode.soundly_sdk.api.model.enhancement.EnhancementTaskState
import com.ideacode.soundly_sdk.api.model.importer.AudioImportRequest
import com.ideacode.soundly_sdk.api.result.SoundlyResult
import com.ideacode.soundly_sdk.internal.SoundlyDispatchers
import com.ideacode.soundly_sdk.editing.StreamingEnhancementProcessor
import com.ideacode.soundly_sdk.editing.StreamingEnhancementProgress
import com.ideacode.soundly_sdk.storage.AudioFileStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class SdkAudioProcessor(
    private val streamingProcessor: StreamingEnhancementProcessor? = null,
    private val fileStore: AudioFileStore? = null,
    private val contentResolver: ContentResolver? = null,
    private val importer: AudioImporter? = null,
    private val enhancer: AudioEnhancer? = null,
    private val projects: AudioProjectRepository? = null
) : AudioProcessingService {

    private fun AudioSourceRef.toUri(): Uri = when (this) {
        is AudioSourceRef.ContentUri -> uri
        is AudioSourceRef.LocalFile -> Uri.fromFile(file)
    }

    override suspend fun process(
        request: ProcessingRequest,
        onProgress: (ProcessingProgress) -> Unit
    ): SoundlyResult<ProcessingResult> = processInternal(
        request = request,
        onProcess = { process ->
            onProgress(
                ProcessingProgress(
                    stage = process.stage,
                    percent = process.percent.coerceIn(0, 100)
                )
            )
        }
    )

    private suspend fun processInternal(
        request: ProcessingRequest,
        onProcess: (ProcessingProcess) -> Unit
    ): SoundlyResult<ProcessingResult> {
        if (!request.outputFileName.isSafeOutputName()) {
            return SoundlyResult.Failure(
                SoundlyError.InvalidRequest(field = "outputFileName")
            )
        }

        val localFile = (request.source as? AudioSourceRef.LocalFile)?.file
        if (localFile != null && !localFile.exists()) {
            return SoundlyResult.Failure(
                SoundlyError.FileNotFound(path = localFile.absolutePath)
            )
        }

        if (importer != null && enhancer != null && projects != null) {
            return processAsProject(request, onProcess)
        }
        if (streamingProcessor == null || fileStore == null) {
            return SoundlyResult.Failure(SoundlyError.InvalidState("processAudio"))
        }
        return processStreaming(request, onProcess)
    }

    /**
     * Compatibility processing is persisted through the SDK 2 project model. This keeps the
     * legacy one-shot API and the project/history APIs on one source of truth.
     */
    private suspend fun processAsProject(
        request: ProcessingRequest,
        onProcess: (ProcessingProcess) -> Unit
    ): SoundlyResult<ProcessingResult> {
        val audioImporter = requireNotNull(importer)
        val audioEnhancer = requireNotNull(enhancer)
        val projectRepository = requireNotNull(projects)
        var projectId: String? = null
        var taskId: String? = null

        return try {
            emitSafely(onProcess, ProcessingProcess(ProcessingStage.LOADING, percent = 0))
            val imported = when (val result = audioImporter.importAudio(
                AudioImportRequest(
                    source = request.source.toUri(),
                    title = request.outputFileName
                )
            )) {
                is SoundlyResult.Success -> result.value
                is SoundlyResult.Failure -> {
                    emitSafely(onProcess, ProcessingProcess(ProcessingStage.ERROR))
                    return result
                }
            }
            val project = imported.project
            projectId = project.id

            if (request.presetOption == PresetOption.RAW) {
                emitSafely(
                    onProcess,
                    ProcessingProcess(
                        stage = ProcessingStage.COMPLETED,
                        percent = 100,
                        wavFilePath = project.original.filePath
                    )
                )
                return SoundlyResult.Success(project.original.toProcessingResult(project.id))
            }

            val started = when (val result = audioEnhancer.enhance(
                EnhancementRequest(
                    projectId = project.id,
                    sourceVariantId = project.original.id,
                    preset = request.presetOption
                )
            )) {
                is SoundlyResult.Success -> result.value
                is SoundlyResult.Failure -> {
                    rollbackProject(projectRepository, project.id)
                    projectId = null
                    emitSafely(onProcess, ProcessingProcess(ProcessingStage.ERROR))
                    return result
                }
            }
            taskId = started.taskId

            val enhanced = coroutineScope {
                val progressJob = launch(start = CoroutineStart.UNDISPATCHED) {
                    audioEnhancer.observe(started.taskId)
                        .filterNotNull()
                        .collect { state ->
                            if (state is EnhancementTaskState.Running) {
                                emitSafely(onProcess, state.toLegacyProcess())
                            }
                        }
                }
                try {
                    audioEnhancer.await(started.taskId)
                } finally {
                    progressJob.cancelAndJoin()
                }
            }
            val enhancementResult = when (enhanced) {
                is SoundlyResult.Success -> enhanced.value
                is SoundlyResult.Failure -> {
                    rollbackProject(projectRepository, project.id)
                    projectId = null
                    emitSafely(onProcess, ProcessingProcess(ProcessingStage.ERROR))
                    return enhanced
                }
            }

            val persistedProject = when (val result = projectRepository.getProject(project.id)) {
                is SoundlyResult.Success -> result.value
                is SoundlyResult.Failure -> {
                    rollbackProject(projectRepository, project.id)
                    projectId = null
                    emitSafely(onProcess, ProcessingProcess(ProcessingStage.ERROR))
                    return result
                }
            }
            val variant = persistedProject.variants.firstOrNull {
                it.id == enhancementResult.variantId
            } ?: run {
                rollbackProject(projectRepository, project.id)
                projectId = null
                emitSafely(onProcess, ProcessingProcess(ProcessingStage.ERROR))
                return SoundlyResult.Failure(SoundlyError.InvalidState("persistEnhancement"))
            }

            emitSafely(
                onProcess,
                ProcessingProcess(
                    stage = ProcessingStage.COMPLETED,
                    percent = 100,
                    wavFilePath = variant.filePath
                )
            )
            SoundlyResult.Success(variant.toProcessingResult(project.id))
        } catch (cancellation: CancellationException) {
            withContext(NonCancellable) {
                taskId?.let { id ->
                    if (audioEnhancer.tasks.value[id] is EnhancementTaskState.Running) {
                        audioEnhancer.cancel(id)
                    }
                }
                projectId?.let { rollbackProject(projectRepository, it) }
            }
            emitSafely(onProcess, ProcessingProcess(ProcessingStage.CANCELLED))
            throw cancellation
        } catch (error: Exception) {
            withContext(NonCancellable) {
                projectId?.let { rollbackProject(projectRepository, it) }
            }
            emitSafely(onProcess, ProcessingProcess(ProcessingStage.ERROR))
            SoundlyResult.Failure(SoundlyErrorMapper.processing(error))
        }
    }

    private suspend fun processStreaming(
        request: ProcessingRequest,
        onProcess: (ProcessingProcess) -> Unit
    ): SoundlyResult<ProcessingResult> {
        val store = requireNotNull(fileStore)
        var copiedSource: java.io.File? = null
        var destination: java.io.File? = null
        return try {
            val source = when (val ref = request.source) {
                is AudioSourceRef.LocalFile -> ref.file
                is AudioSourceRef.ContentUri -> {
                    val resolver = requireNotNull(contentResolver)
                    val temporary = store.createTempFile(".audio")
                    copiedSource = temporary
                    val input = resolver.openInputStream(ref.uri)
                        ?: return SoundlyResult.Failure(SoundlyError.FileNotFound())
                    store.copyAtomically(input, temporary)
                }
            }
            destination = store.exportCacheFile("${request.outputFileName}.wav")
            val result = requireNotNull(streamingProcessor).process(
                source = source,
                destination = destination,
                preset = request.presetOption
            ) { progress -> onProcess(progress.toLegacyProcess()) }
            onProcess(
                ProcessingProcess(
                    stage = ProcessingStage.COMPLETED,
                    percent = 100,
                    wavFilePath = destination.absolutePath
                )
            )
            SoundlyResult.Success(
                ProcessingResult(
                    outputFilePath = destination.absolutePath,
                    historyId = null,
                    presetOption = request.presetOption,
                    sampleRate = result.sampleRate,
                    channels = result.channels,
                    durationMs = result.durationMs
                )
            )
        } catch (cancellation: CancellationException) {
            destination?.let(::deleteManagedFile)
            onProcess(ProcessingProcess(stage = ProcessingStage.CANCELLED))
            throw cancellation
        } catch (error: Exception) {
            destination?.let(::deleteManagedFile)
            onProcess(ProcessingProcess(stage = ProcessingStage.ERROR))
            SoundlyResult.Failure(SoundlyErrorMapper.processing(error))
        } finally {
            copiedSource?.let(::deleteManagedFile)
        }
    }

    private fun deleteManagedFile(file: java.io.File) {
        runCatching {
            if (fileStore?.isManaged(file) == true) fileStore.deleteFile(file)
        }
    }

    private suspend fun rollbackProject(
        repository: AudioProjectRepository,
        projectId: String
    ) {
        repository.deleteProject(projectId)
    }

    private fun emitSafely(
        callback: (ProcessingProcess) -> Unit,
        process: ProcessingProcess
    ) {
        runCatching { callback(process) }
    }

    private fun com.ideacode.soundly_sdk.api.model.domain.AudioVariant.toProcessingResult(
        ownerProjectId: String
    ): ProcessingResult = ProcessingResult(
        outputFilePath = filePath,
        historyId = ownerProjectId,
        presetOption = presetOption,
        sampleRate = sampleRate,
        channels = channels,
        durationMs = durationMs
    )

    private fun EnhancementTaskState.Running.toLegacyProcess(): ProcessingProcess =
        ProcessingProcess(
            stage = when (stage) {
                EnhancementStage.PREPARING,
                EnhancementStage.LOADING -> ProcessingStage.LOADING
                EnhancementStage.ANALYZING,
                EnhancementStage.RECOMMENDING -> ProcessingStage.ANALYZING
                EnhancementStage.PROCESSING -> ProcessingStage.DSP_PROCESSING
                EnhancementStage.WRITING,
                EnhancementStage.SAVING -> ProcessingStage.WRITING_WAV
                EnhancementStage.COMPLETED -> ProcessingStage.COMPLETED
                EnhancementStage.CANCELLED -> ProcessingStage.CANCELLED
                EnhancementStage.FAILED -> ProcessingStage.ERROR
            },
            percent = progressPercent
        )

    private fun StreamingEnhancementProgress.toLegacyProcess(): ProcessingProcess =
        ProcessingProcess(
            stage = when (stage) {
                com.ideacode.soundly_sdk.api.model.enhancement.EnhancementStage.PREPARING,
                com.ideacode.soundly_sdk.api.model.enhancement.EnhancementStage.LOADING ->
                    ProcessingStage.LOADING
                com.ideacode.soundly_sdk.api.model.enhancement.EnhancementStage.ANALYZING,
                com.ideacode.soundly_sdk.api.model.enhancement.EnhancementStage.RECOMMENDING ->
                    ProcessingStage.ANALYZING
                com.ideacode.soundly_sdk.api.model.enhancement.EnhancementStage.PROCESSING ->
                    ProcessingStage.DSP_PROCESSING
                com.ideacode.soundly_sdk.api.model.enhancement.EnhancementStage.WRITING,
                com.ideacode.soundly_sdk.api.model.enhancement.EnhancementStage.SAVING ->
                    ProcessingStage.WRITING_WAV
                com.ideacode.soundly_sdk.api.model.enhancement.EnhancementStage.COMPLETED ->
                    ProcessingStage.COMPLETED
                com.ideacode.soundly_sdk.api.model.enhancement.EnhancementStage.CANCELLED ->
                    ProcessingStage.CANCELLED
                com.ideacode.soundly_sdk.api.model.enhancement.EnhancementStage.FAILED ->
                    ProcessingStage.ERROR
            },
            percent = percent
        )

    private fun String.isSafeOutputName(): Boolean =
        isNotBlank() &&
            length <= MAX_OUTPUT_NAME_LENGTH &&
            this == java.io.File(this).name &&
            !startsWith('.') &&
            matches(SAFE_OUTPUT_NAME)

    private companion object {
        const val MAX_OUTPUT_NAME_LENGTH = 120
        val SAFE_OUTPUT_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._ -]*")
    }

}

@JvmSynthetic
@Suppress("UNUSED_PARAMETER")
internal fun createAudioProcessingService(
    audioLoader: com.ideacode.soundly_sdk.core.audio.io.AudioLoader?,
    audioProcessor: com.ideacode.soundly_sdk.core.audio.processor.AudioProcessor?,
    streamingProcessor: StreamingEnhancementProcessor? = null,
    fileStore: AudioFileStore? = null,
    contentResolver: ContentResolver? = null,
    importer: AudioImporter? = null,
    enhancer: AudioEnhancer? = null,
    projects: AudioProjectRepository? = null,
    dispatchers: SoundlyDispatchers = SoundlyDispatchers.Default
): AudioProcessingService = SdkAudioProcessor(
    streamingProcessor = streamingProcessor,
    fileStore = fileStore,
    contentResolver = contentResolver,
    importer = importer,
    enhancer = enhancer,
    projects = projects
)
