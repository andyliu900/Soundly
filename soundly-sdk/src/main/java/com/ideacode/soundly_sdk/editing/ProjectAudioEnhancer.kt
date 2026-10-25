package com.ideacode.soundly_sdk.editing

import android.net.Uri
import com.ideacode.soundly_sdk.api.capability.AudioEnhancer
import com.ideacode.soundly_sdk.api.capability.AudioMetadataReader
import com.ideacode.soundly_sdk.api.capability.AudioProjectRepository
import com.ideacode.soundly_sdk.api.capability.EnhancementResult
import com.ideacode.soundly_sdk.api.error.SoundlyError
import com.ideacode.soundly_sdk.api.error.SoundlyErrorMapper
import com.ideacode.soundly_sdk.api.model.audio.dsp.PresetOption
import com.ideacode.soundly_sdk.api.model.audio.processor.ProcessingProgress
import com.ideacode.soundly_sdk.api.model.audio.processor.ProcessingStage
import com.ideacode.soundly_sdk.api.model.domain.AudioFormat
import com.ideacode.soundly_sdk.api.model.domain.AudioVariant
import com.ideacode.soundly_sdk.api.model.domain.VariantType
import com.ideacode.soundly_sdk.api.model.enhancement.EnhancementRequest
import com.ideacode.soundly_sdk.api.model.enhancement.EnhancementStage
import com.ideacode.soundly_sdk.api.model.enhancement.EnhancementTask
import com.ideacode.soundly_sdk.api.model.enhancement.EnhancementTaskState
import com.ideacode.soundly_sdk.api.result.SoundlyResult
import com.ideacode.soundly_sdk.internal.SoundlyDispatchers
import com.ideacode.soundly_sdk.storage.AudioFileStore
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

internal class ProjectAudioEnhancer(
    private val processor: StreamingEnhancementProcessor,
    private val projects: AudioProjectRepository,
    private val metadataReader: AudioMetadataReader,
    private val fileStore: AudioFileStore,
    private val taskIdGenerator: () -> String = { UUID.randomUUID().toString() },
    private val variantIdGenerator: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val dispatchers: SoundlyDispatchers = SoundlyDispatchers.Default
) : AudioEnhancer {

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val stateLock = Any()
    private val jobs = mutableMapOf<String, Job>()
    private val projectLocks = ConcurrentHashMap<String, Mutex>()
    private val mutableTasks = MutableStateFlow<Map<String, EnhancementTaskState>>(emptyMap())

    override val tasks: StateFlow<Map<String, EnhancementTaskState>> = mutableTasks.asStateFlow()

    override suspend fun enhance(
        request: EnhancementRequest
    ): SoundlyResult<EnhancementTask> {
        validate(request)?.let { return SoundlyResult.Failure(it) }
        return createAndStartTask(request, retryOfTaskId = null)
    }

    override suspend fun enhance(
        projectId: String,
        sourceVariantId: String,
        preset: PresetOption,
        onProgress: (ProcessingProgress) -> Unit
    ): SoundlyResult<EnhancementResult> {
        val request = EnhancementRequest(projectId, sourceVariantId, preset)
        validate(request)?.let { return SoundlyResult.Failure(it) }
        val started = createAndStartTask(
            request = request,
            retryOfTaskId = null,
            listener = { state -> runCatching { onProgress(state.toLegacyProgress()) } }
        )
        return when (started) {
            is SoundlyResult.Success -> await(started.value.taskId)
            is SoundlyResult.Failure -> started
        }
    }

    override suspend fun await(taskId: String): SoundlyResult<EnhancementResult> {
        val current = mutableTasks.value[taskId]
            ?: return SoundlyResult.Failure(SoundlyError.InvalidRequest("taskId"))
        val terminal = if (current.isTerminal()) {
            current
        } else {
            observe(taskId).filterNotNull().first { it.isTerminal() }
        }
        return terminal.toResult()
    }

    override suspend fun cancel(taskId: String): SoundlyResult<Unit> {
        val job = synchronized(stateLock) {
            val state = mutableTasks.value[taskId]
                ?: return SoundlyResult.Failure(SoundlyError.InvalidRequest("taskId"))
            if (state.isTerminal()) {
                return SoundlyResult.Failure(SoundlyError.InvalidState("cancelEnhancement"))
            }
            jobs[taskId]
        } ?: return SoundlyResult.Failure(SoundlyError.InvalidState("cancelEnhancement"))

        job.cancelAndJoin()
        val afterJoin = mutableTasks.value[taskId]
        if (afterJoin is EnhancementTaskState.Running) {
            publish(
                EnhancementTaskState.Cancelled(
                    task = afterJoin.task,
                    cancelledAtStage = afterJoin.stage,
                    updatedAt = clock()
                ),
                listener = null
            )
        }
        return if (mutableTasks.value[taskId] is EnhancementTaskState.Cancelled) {
            SoundlyResult.Success(Unit)
        } else {
            SoundlyResult.Failure(SoundlyError.InvalidState("cancelEnhancement"))
        }
    }

    override suspend fun retry(taskId: String): SoundlyResult<EnhancementTask> {
        val previous = mutableTasks.value[taskId]
            ?: return SoundlyResult.Failure(SoundlyError.InvalidRequest("taskId"))
        val retryable = when (previous) {
            is EnhancementTaskState.Failed -> previous.retryable
            is EnhancementTaskState.Cancelled -> true
            else -> false
        }
        if (!retryable) {
            return SoundlyResult.Failure(SoundlyError.InvalidState("retryEnhancement"))
        }
        return createAndStartTask(previous.task.request, retryOfTaskId = taskId)
    }

    override fun observe(taskId: String): Flow<EnhancementTaskState?> = mutableTasks
        .map { taskStates -> taskStates[taskId] }
        .distinctUntilChanged()

    private fun createAndStartTask(
        request: EnhancementRequest,
        retryOfTaskId: String?,
        listener: ((EnhancementTaskState) -> Unit)? = null
    ): SoundlyResult<EnhancementTask> = try {
        val task = EnhancementTask(
            taskId = nextTaskId(),
            projectId = request.projectId,
            sourceVariantId = request.sourceVariantId,
            variantId = variantIdGenerator(),
            preset = request.preset,
            createdAt = clock(),
            retryOfTaskId = retryOfTaskId
        )
        val initial = EnhancementTaskState.Running(
            task = task,
            stage = EnhancementStage.PREPARING,
            progressPercent = 0,
            updatedAt = clock()
        )
        publish(initial, listener)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val projectLock = projectLocks.getOrPut(task.projectId) { Mutex() }
            projectLock.withLock { execute(task, listener) }
        }
        synchronized(stateLock) { jobs[task.taskId] = job }
        job.invokeOnCompletion {
            synchronized(stateLock) { jobs.remove(task.taskId) }
        }
        job.start()
        SoundlyResult.Success(task)
    } catch (error: Exception) {
        SoundlyResult.Failure(SoundlyError.ProcessingFailed(error))
    }

    private suspend fun execute(
        task: EnhancementTask,
        listener: ((EnhancementTaskState) -> Unit)?
    ) {
        var taskTemp: File? = null
        var publishedDestination: File? = null
        try {
            val project = when (val result = projects.getProject(task.projectId)) {
                is SoundlyResult.Success -> result.value
                is SoundlyResult.Failure -> return fail(task, result.error, listener)
            }
            val sourceVariant = (listOf(project.original) + project.variants)
                .firstOrNull { it.id == task.sourceVariantId }
                ?: return fail(task, SoundlyError.InvalidRequest("sourceVariantId"), listener)
            val sourceFile = File(sourceVariant.filePath)
            if (!sourceFile.isFile) {
                return fail(task, SoundlyError.FileNotFound(sourceVariant.filePath), listener)
            }

            taskTemp = fileStore.createTempFile(".wav")
            advance(task, EnhancementStage.LOADING, 0, listener)
            processor.process(
                source = sourceFile,
                destination = taskTemp,
                preset = task.preset
            ) { progress -> advance(task, progress.stage, progress.percent, listener) }
            if (!taskTemp.isFile || taskTemp.length() <= WAV_HEADER_BYTES) {
                return fail(task, SoundlyError.ProcessingFailed(), listener)
            }

            advance(task, EnhancementStage.SAVING, SAVING_START_PERCENT, listener)
            val destination = fileStore.variantFile(task.projectId, task.variantId, AudioFormat.WAV)
            fileStore.moveAtomically(taskTemp, destination)
            taskTemp = null
            publishedDestination = destination

            val metadata = when (val result = metadataReader.read(Uri.fromFile(destination))) {
                is SoundlyResult.Success -> result.value
                is SoundlyResult.Failure -> return fail(task, result.error, listener)
            }
            val variant = AudioVariant(
                id = task.variantId,
                projectId = task.projectId,
                filePath = destination.absolutePath,
                presetOption = task.preset,
                isOriginal = false,
                sampleRate = metadata.sampleRate,
                channels = metadata.channels,
                durationMs = metadata.durationMs,
                format = metadata.format,
                mimeType = metadata.mimeType,
                fileSizeBytes = destination.length(),
                createdAt = clock(),
                variantType = VariantType.ENHANCED,
                sourceVariantId = task.sourceVariantId
            )
            when (val result = projects.addVariant(task.projectId, variant)) {
                is SoundlyResult.Success -> {
                    advance(task, EnhancementStage.SAVING, SAVING_COMPLETE_PERCENT, listener)
                    publishedDestination = null
                    publish(
                        EnhancementTaskState.Completed(
                            task = task,
                            result = EnhancementResult(
                                projectId = task.projectId,
                                variantId = task.variantId,
                                durationMs = metadata.durationMs,
                                preset = task.preset,
                                taskId = task.taskId
                            ),
                            updatedAt = clock()
                        ),
                        listener
                    )
                }
                is SoundlyResult.Failure -> fail(task, result.error, listener)
            }
        } catch (cancellation: CancellationException) {
            publish(
                EnhancementTaskState.Cancelled(
                    task = task,
                    cancelledAtStage = currentRunningStage(task.taskId),
                    updatedAt = clock()
                ),
                listener
            )
            throw cancellation
        } catch (error: Exception) {
            fail(task, SoundlyErrorMapper.processing(error), listener)
        } finally {
            cleanup(taskTemp)
            cleanup(publishedDestination)
        }
    }

    private fun advance(
        task: EnhancementTask,
        target: EnhancementStage,
        progress: Int,
        listener: ((EnhancementTaskState) -> Unit)?
    ) {
        val current = mutableTasks.value[task.taskId] as? EnhancementTaskState.Running ?: return
        val currentIndex = RUNNING_STAGES.indexOf(current.stage)
        val targetIndex = RUNNING_STAGES.indexOf(target)
        if (targetIndex < currentIndex || targetIndex < 0) return
        if (targetIndex == currentIndex) {
            val monotonic = progress.coerceIn(current.progressPercent, 100)
            if (monotonic != current.progressPercent) {
                publish(current.copy(progressPercent = monotonic, updatedAt = clock()), listener)
            }
            return
        }
        var lastPercent = current.progressPercent
        for (index in (currentIndex + 1)..targetIndex) {
            val stagePercent = if (index == targetIndex) {
                progress.coerceIn(lastPercent, 100)
            } else {
                lastPercent
            }
            publish(
                EnhancementTaskState.Running(
                    task = task,
                    stage = RUNNING_STAGES[index],
                    progressPercent = stagePercent,
                    updatedAt = clock()
                ),
                listener
            )
            lastPercent = stagePercent
        }
    }

    private fun fail(
        task: EnhancementTask,
        error: SoundlyError,
        listener: ((EnhancementTaskState) -> Unit)?
    ) {
        publish(
            EnhancementTaskState.Failed(
                task = task,
                error = error,
                failedAtStage = currentRunningStage(task.taskId),
                updatedAt = clock()
            ),
            listener
        )
    }

    private fun publish(state: EnhancementTaskState, listener: ((EnhancementTaskState) -> Unit)?) {
        synchronized(stateLock) {
            val previous = mutableTasks.value[state.task.taskId]
            if (previous?.isTerminal() == true) return
            mutableTasks.value = mutableTasks.value + (state.task.taskId to state)
        }
        listener?.let { callback ->
            runCatching { callback(state) }
                .onFailure { Timber.w(it, "Enhancement state listener failed") }
        }
    }

    private fun cleanup(file: File?) {
        if (file == null || !file.exists()) return
        runCatching {
            if (fileStore.isManaged(file)) fileStore.deleteFile(file)
        }.onFailure { Timber.w(it, "Unable to clean enhancement task file") }
    }

    private fun currentRunningStage(taskId: String): EnhancementStage =
        (mutableTasks.value[taskId] as? EnhancementTaskState.Running)?.stage
            ?: EnhancementStage.PREPARING

    private fun nextTaskId(): String {
        repeat(MAX_ID_ATTEMPTS) {
            val candidate = taskIdGenerator()
            if (candidate.matches(SAFE_IDENTIFIER) && candidate !in mutableTasks.value) return candidate
        }
        throw IllegalStateException("Unable to allocate enhancement task ID")
    }

    private fun validate(request: EnhancementRequest): SoundlyError? = when {
        request.projectId.isBlank() -> SoundlyError.InvalidRequest("projectId")
        request.sourceVariantId.isBlank() -> SoundlyError.InvalidRequest("sourceVariantId")
        request.preset == PresetOption.RAW -> SoundlyError.InvalidRequest("preset")
        else -> null
    }

    private fun EnhancementTaskState.isTerminal(): Boolean =
        this is EnhancementTaskState.Completed ||
            this is EnhancementTaskState.Cancelled ||
            this is EnhancementTaskState.Failed

    private fun EnhancementTaskState.toResult(): SoundlyResult<EnhancementResult> = when (this) {
        is EnhancementTaskState.Completed -> SoundlyResult.Success(result)
        is EnhancementTaskState.Cancelled -> SoundlyResult.Failure(SoundlyError.ProcessingCancelled)
        is EnhancementTaskState.Failed -> SoundlyResult.Failure(error)
        is EnhancementTaskState.Running -> SoundlyResult.Failure(SoundlyError.InvalidState("awaitEnhancement"))
    }

    private fun EnhancementTaskState.toLegacyProgress(): ProcessingProgress = ProcessingProgress(
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
        percent = when (this) {
            is EnhancementTaskState.Running -> progressPercent
            is EnhancementTaskState.Completed -> 100
            else -> 0
        }
    )

    private companion object {
        const val MAX_ID_ATTEMPTS = 10
        const val WAV_HEADER_BYTES = 44L
        const val SAVING_START_PERCENT = 96
        const val SAVING_COMPLETE_PERCENT = 99
        val SAFE_IDENTIFIER = Regex("[A-Za-z0-9_-]{1,128}")
        val RUNNING_STAGES = listOf(
            EnhancementStage.PREPARING,
            EnhancementStage.LOADING,
            EnhancementStage.ANALYZING,
            EnhancementStage.RECOMMENDING,
            EnhancementStage.PROCESSING,
            EnhancementStage.WRITING,
            EnhancementStage.SAVING
        )
    }
}
