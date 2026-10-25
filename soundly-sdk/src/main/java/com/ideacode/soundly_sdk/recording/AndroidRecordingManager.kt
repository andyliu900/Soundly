package com.ideacode.soundly_sdk.recording

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.ideacode.soundly_sdk.api.capability.AudioMetadataReader
import com.ideacode.soundly_sdk.api.capability.AudioProjectRepository
import com.ideacode.soundly_sdk.api.capability.RecordingManager
import com.ideacode.soundly_sdk.api.error.SoundlyError
import com.ideacode.soundly_sdk.api.error.SoundlyErrorMapper
import com.ideacode.soundly_sdk.api.model.audio.dsp.PresetOption
import com.ideacode.soundly_sdk.api.model.domain.AudioFormat
import com.ideacode.soundly_sdk.api.model.domain.AudioMarker
import com.ideacode.soundly_sdk.api.model.domain.AudioProject
import com.ideacode.soundly_sdk.api.model.domain.AudioSourceType
import com.ideacode.soundly_sdk.api.model.domain.AudioVariant
import com.ideacode.soundly_sdk.api.model.domain.ProjectStatus
import com.ideacode.soundly_sdk.api.model.domain.RecordingScene
import com.ideacode.soundly_sdk.api.model.domain.VariantType
import com.ideacode.soundly_sdk.api.model.recording.AudioMeter
import com.ideacode.soundly_sdk.api.model.recording.RecoverableRecording
import com.ideacode.soundly_sdk.api.model.recording.RecordingConfig
import com.ideacode.soundly_sdk.api.model.recording.RecordingInterruptionReason
import com.ideacode.soundly_sdk.api.model.recording.RecordingResult
import com.ideacode.soundly_sdk.api.model.recording.RecordingSession
import com.ideacode.soundly_sdk.api.model.recording.RecordingState
import com.ideacode.soundly_sdk.api.model.recording.StaticWaveform
import com.ideacode.soundly_sdk.api.model.recording.WaveformFrame
import com.ideacode.soundly_sdk.api.model.settings.SoundlySettingsRepository
import com.ideacode.soundly_sdk.api.model.settings.toRecordingConfig
import com.ideacode.soundly_sdk.api.model.settings.createRecordingTitle
import com.ideacode.soundly_sdk.api.result.SoundlyResult
import com.ideacode.soundly_sdk.project.data.dao.AudioProjectDao
import com.ideacode.soundly_sdk.project.data.entity.RecoverySessionEntity
import com.ideacode.soundly_sdk.storage.AudioFileStore
import com.ideacode.soundly_sdk.settings.SharedPreferencesSoundlySettingsRepository
import com.ideacode.soundly_sdk.internal.SoundlyDispatchers
import com.ideacode.soundly_sdk.core.audio.decode.AudioDecodingException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.UUID

internal class AndroidRecordingManager(
    private val context: Context,
    private val projects: AudioProjectRepository,
    private val metadataReader: AudioMetadataReader,
    private val fileStore: AudioFileStore,
    private val dao: AudioProjectDao,
    private val settings: SoundlySettingsRepository =
        SharedPreferencesSoundlySettingsRepository(context),
    private val engineFactory: () -> RecorderEngine = { MediaRecorderEngine(context) },
    private val recordPermissionChecker: () -> Boolean = {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    },
    private val focusController: RecordingFocusController =
        AndroidRecordingFocusController(context),
    private val deviceMonitor: RecordingDeviceMonitor =
        AndroidRecordingDeviceMonitor(context),
    private val waveformGenerator: StaticWaveformGenerator =
        MediaCodecStaticWaveformGenerator(),
    private val recordingFileFinalizer: RecordingFileFinalizer =
        AndroidRecordingFileFinalizer(fileStore),
    private val foregroundController: RecordingForegroundController =
        AndroidRecordingForegroundController(context),
    private val clock: () -> Long = System::currentTimeMillis,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val dispatchers: SoundlyDispatchers = SoundlyDispatchers.Default
) : RecordingManager {

    private val operationMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.computation)
    private val mutableState = MutableStateFlow<RecordingState>(RecordingState.Idle)
    private val mutableMeter = MutableSharedFlow<AudioMeter>(extraBufferCapacity = 8)
    private val mutableWaveform = MutableSharedFlow<WaveformFrame>(extraBufferCapacity = 8)
    private val mutableMarkers = MutableStateFlow<List<AudioMarker>>(emptyList())

    override val state: StateFlow<RecordingState> = mutableState.asStateFlow()
    override val meter: Flow<AudioMeter> = mutableMeter.asSharedFlow()
    override val waveform: Flow<WaveformFrame> = mutableWaveform.asSharedFlow()
    override val markers: StateFlow<List<AudioMarker>> = mutableMarkers.asStateFlow()

    private var prepared: PreparedRecording? = null
    private var engine: RecorderEngine? = null
    private var monitorJob: Job? = null
    private var accumulatedDurationMs = 0L
    private var segmentStartedAtMs: Long? = null
    private val markerBuffer = mutableListOf<AudioMarker>()
    private val signalAccumulator = RecordingSignalAccumulator()
    private val staticWaveforms = StaticWaveformFileStore(fileStore)

    override suspend fun prepareUsingDefaults(title: String?): SoundlyResult<Unit> =
        when (val result = settings.get()) {
            is SoundlyResult.Success -> {
                val normalizedTitle = title?.trim()?.takeIf(String::isNotBlank)
                    ?: result.value.createRecordingTitle(clock())
                prepare(result.value.toRecordingConfig(normalizedTitle))
            }
            is SoundlyResult.Failure -> result
        }

    override suspend fun prepare(config: RecordingConfig): SoundlyResult<Unit> =
        operationMutex.withLock {
            val validation = validateConfig(config)
            if (validation != null) return@withLock SoundlyResult.Failure(validation)
            if (isActivelyRecording()) {
                return@withLock SoundlyResult.Failure(SoundlyError.InvalidState("prepare"))
            }
            if (!hasRecordPermission()) {
                return@withLock SoundlyResult.Failure(SoundlyError.PermissionDenied)
            }
            if (fileStore.availableBytes() < MINIMUM_FREE_BYTES) {
                return@withLock SoundlyResult.Failure(SoundlyError.InsufficientStorage)
            }

            mutableState.value = RecordingState.Preparing
            releasePrepared(deleteFiles = true)
            val sessionId = idGenerator()
            val projectId = idGenerator()
            val originalVariantId = idGenerator()
            val recoveryFile = try {
                fileStore.recoveryFile(sessionId, RECOVERY_FORMAT)
            } catch (error: Exception) {
                mutableState.value = RecordingState.Failed(null, SoundlyError.FileOperationFailed(error))
                return@withLock SoundlyResult.Failure(SoundlyError.FileOperationFailed(error))
            }

            return@withLock try {
                val newEngine = engineFactory()
                newEngine.setOnErrorListener { failure ->
                    scope.launch { handleEngineFailure(failure) }
                }
                newEngine.prepare(config.copy(format = RECOVERY_FORMAT), recoveryFile)
                engine = newEngine
                prepared = PreparedRecording(
                    sessionId = sessionId,
                    projectId = projectId,
                    originalVariantId = originalVariantId,
                    config = config,
                    recoveryFile = recoveryFile,
                    preparedAt = clock()
                )
                accumulatedDurationMs = 0L
                segmentStartedAtMs = null
                markerBuffer.clear()
                mutableMarkers.value = emptyList()
                signalAccumulator.reset()
                writeRecoveryMetadata(requireNotNull(prepared))
                mutableState.value = RecordingState.Ready(sessionId, projectId)
                SoundlyResult.Success(Unit)
            } catch (error: Exception) {
                engine?.release()
                engine = null
                prepared = null
                fileStore.deleteFile(recoveryFile)
                val mapped = SoundlyErrorMapper.recording(error)
                mutableState.value = RecordingState.Failed(null, mapped)
                SoundlyResult.Failure(mapped)
            }
        }

    override suspend fun start(): SoundlyResult<RecordingSession> = operationMutex.withLock {
        val current = prepared
            ?: return@withLock SoundlyResult.Failure(SoundlyError.InvalidState("start"))
        if (mutableState.value !is RecordingState.Ready) {
            return@withLock SoundlyResult.Failure(SoundlyError.InvalidState("start"))
        }
        return@withLock try {
            if (fileStore.availableBytes() < MINIMUM_FREE_BYTES) {
                return@withLock SoundlyResult.Failure(SoundlyError.InsufficientStorage)
            }
            if (!requestRecordingFocus()) {
                return@withLock SoundlyResult.Failure(SoundlyError.MicrophoneUnavailable)
            }
            val now = clock()
            dao.upsertRecoverySession(
                RecoverySessionEntity(
                    id = current.sessionId,
                    projectId = current.projectId,
                    tempFilePath = current.recoveryFile.absolutePath,
                    recordedDurationMs = 0L,
                    recordedBytes = 0L,
                    status = STATUS_RECORDING,
                    updatedAt = now
                )
            )
            requireNotNull(engine).start()
            segmentStartedAtMs = elapsedRealtime()
            mutableState.value = current.recordingState(0L, 0L, 0)
            startDeviceMonitoring()
            startMonitoring(current)
            if (current.config.enableBackgroundNotification) {
                foregroundController.start()
            }
            SoundlyResult.Success(
                RecordingSession(
                    id = current.sessionId,
                    projectId = current.projectId,
                    startedAt = now,
                    config = current.config
                )
            )
        } catch (error: Exception) {
            focusController.abandon()
            markRecoveryRequired(current, error)
            val mapped = SoundlyErrorMapper.recording(error)
            mutableState.value = RecordingState.Failed(current.sessionId, mapped)
            SoundlyResult.Failure(mapped)
        }
    }

    override suspend fun pause(): SoundlyResult<Unit> = operationMutex.withLock {
        val current = prepared
            ?: return@withLock SoundlyResult.Failure(SoundlyError.InvalidState("pause"))
        if (mutableState.value !is RecordingState.Recording) {
            return@withLock SoundlyResult.Failure(SoundlyError.InvalidState("pause"))
        }
        return@withLock try {
            val frozenDurationMs = currentDurationMs()
            requireNotNull(engine).pause()
            accumulatedDurationMs = frozenDurationMs
            segmentStartedAtMs = null
            stopMonitoring()
            deviceMonitor.stop()
            flushPendingWaveform(frozenDurationMs)
            val bytes = current.recoveryFile.length()
            mutableState.value = RecordingState.Paused(
                current.sessionId,
                accumulatedDurationMs,
                bytes,
                markerBuffer.size
            )
            persistProgress(current, STATUS_PAUSED)
            foregroundController.refresh()
            SoundlyResult.Success(Unit)
        } catch (error: Exception) {
            recordingFailure(current, error)
        }
    }

    override suspend fun resume(): SoundlyResult<Unit> = operationMutex.withLock {
        val current = prepared
            ?: return@withLock SoundlyResult.Failure(SoundlyError.InvalidState("resume"))
        if (mutableState.value !is RecordingState.Paused &&
            mutableState.value !is RecordingState.Interrupted
        ) {
            return@withLock SoundlyResult.Failure(SoundlyError.InvalidState("resume"))
        }
        return@withLock try {
            if (!hasRecordPermission()) {
                return@withLock SoundlyResult.Failure(SoundlyError.PermissionDenied)
            }
            if (fileStore.availableBytes() < MINIMUM_FREE_BYTES) {
                return@withLock SoundlyResult.Failure(SoundlyError.InsufficientStorage)
            }
            if (!requestRecordingFocus()) {
                return@withLock SoundlyResult.Failure(SoundlyError.MicrophoneUnavailable)
            }
            requireNotNull(engine).resume()
            segmentStartedAtMs = elapsedRealtime()
            mutableState.value = current.recordingState(
                accumulatedDurationMs,
                current.recoveryFile.length(),
                markerBuffer.size
            )
            persistProgress(current, STATUS_RECORDING)
            startDeviceMonitoring()
            startMonitoring(current)
            foregroundController.refresh()
            SoundlyResult.Success(Unit)
        } catch (error: Exception) {
            recordingFailure(current, error)
        }
    }

    override suspend fun addMarker(label: String?): SoundlyResult<AudioMarker> =
        operationMutex.withLock {
            val current = prepared
                ?: return@withLock SoundlyResult.Failure(SoundlyError.InvalidState("addMarker"))
            if (mutableState.value !is RecordingState.Recording &&
                mutableState.value !is RecordingState.Paused &&
                mutableState.value !is RecordingState.Interrupted
            ) {
                return@withLock SoundlyResult.Failure(
                    SoundlyError.InvalidState("addMarker")
                )
            }
            val normalizedLabel = label?.trim()?.take(MAX_MARKER_LABEL_LENGTH)
                ?.takeIf(String::isNotBlank)
            val marker = AudioMarker(
                id = idGenerator(),
                projectId = current.projectId,
                positionMs = currentDurationMs(),
                label = normalizedLabel,
                createdAt = clock()
            )
            markerBuffer += marker
            publishMarkers()
            writeRecoveryMetadata(current)
            updateVisibleState(current)
            SoundlyResult.Success(marker)
        }

    override suspend fun deleteMarker(markerId: String): SoundlyResult<Unit> =
        operationMutex.withLock {
            val current = prepared
                ?: return@withLock SoundlyResult.Failure(
                    SoundlyError.InvalidState("deleteMarker")
                )
            if (mutableState.value !is RecordingState.Recording &&
                mutableState.value !is RecordingState.Paused &&
                mutableState.value !is RecordingState.Interrupted
            ) {
                return@withLock SoundlyResult.Failure(
                    SoundlyError.InvalidState("deleteMarker")
                )
            }
            val removed = markerBuffer.removeAll { it.id == markerId }
            if (!removed) {
                return@withLock SoundlyResult.Failure(
                    SoundlyError.InvalidRequest("markerId")
                )
            }
            publishMarkers()
            writeRecoveryMetadata(current)
            updateVisibleState(current)
            SoundlyResult.Success(Unit)
        }

    override suspend fun stopAndSave(): SoundlyResult<RecordingResult> =
        operationMutex.withLock {
            val current = prepared
                ?: return@withLock SoundlyResult.Failure(
                    SoundlyError.InvalidState("stopAndSave")
                )
            if (mutableState.value !is RecordingState.Recording &&
                mutableState.value !is RecordingState.Paused &&
                mutableState.value !is RecordingState.Interrupted
            ) {
                return@withLock SoundlyResult.Failure(
                    SoundlyError.InvalidState("stopAndSave")
                )
            }
            val durationBeforeStop = currentDurationMs()
            mutableState.value = RecordingState.Finalizing(current.sessionId)
            stopMonitoring()
            deviceMonitor.stop()
            flushPendingWaveform(durationBeforeStop)
            foregroundController.stop()
            focusController.abandon()
            return@withLock try {
                requireNotNull(engine).stop()
                engine?.release()
                engine = null
                finalizeRecording(current, durationBeforeStop, markerBuffer.toList())
            } catch (error: Exception) {
                engine?.release()
                engine = null
                markRecoveryRequired(current, error)
                val mapped = SoundlyErrorMapper.recording(error)
                mutableState.value = RecordingState.Failed(current.sessionId, mapped)
                SoundlyResult.Failure(mapped)
            }
        }

    override suspend fun discard(): SoundlyResult<Unit> = operationMutex.withLock {
        val current = prepared
        stopMonitoring()
        deviceMonitor.stop()
        foregroundController.stop()
        focusController.abandon()
        runCatching {
            if (mutableState.value is RecordingState.Recording ||
                mutableState.value is RecordingState.Paused ||
                mutableState.value is RecordingState.Interrupted
            ) {
                engine?.stop()
            }
        }
        engine?.release()
        engine = null
        prepared = null
        accumulatedDurationMs = 0L
        segmentStartedAtMs = null
        markerBuffer.clear()
        mutableMarkers.value = emptyList()
        signalAccumulator.reset()
        return@withLock try {
            if (current != null) {
                fileStore.deleteFile(current.recoveryFile)
                fileStore.deleteProject(current.projectId)
                dao.deleteRecoverySession(current.sessionId)
            }
            mutableState.value = RecordingState.Idle
            SoundlyResult.Success(Unit)
        } catch (error: Exception) {
            val mapped = SoundlyError.FileOperationFailed(error)
            mutableState.value = RecordingState.Failed(current?.sessionId, mapped)
            SoundlyResult.Failure(mapped)
        }
    }

    override suspend fun getRecoverableRecordings(): SoundlyResult<List<RecoverableRecording>> =
        withContext(dispatchers.io) {
            try {
                val activeSessionId = prepared?.sessionId
                val recoverable = dao.getRecoverySessions().mapNotNull { entity ->
                    if (entity.id == activeSessionId) return@mapNotNull null
                    val file = File(entity.tempFilePath)
                    if (!file.isFile || !fileStore.isManaged(file)) {
                        dao.deleteRecoverySession(entity.id)
                        null
                    } else {
                        RecoverableRecording(
                            sessionId = entity.id,
                            projectId = entity.projectId,
                            recordedDurationMs = entity.recordedDurationMs,
                            recordedBytes = file.length().coerceAtLeast(entity.recordedBytes),
                            updatedAt = entity.updatedAt
                        )
                    }
                }
                SoundlyResult.Success(recoverable)
            } catch (error: Exception) {
                SoundlyResult.Failure(SoundlyError.DatabaseFailed(error))
            }
        }

    override suspend fun recover(sessionId: String): SoundlyResult<RecordingResult> =
        operationMutex.withLock {
            if (isActivelyRecording()) {
                return@withLock SoundlyResult.Failure(SoundlyError.InvalidState("recover"))
            }
            val entity = try {
                dao.getRecoverySession(sessionId)
            } catch (error: Exception) {
                return@withLock SoundlyResult.Failure(SoundlyError.DatabaseFailed(error))
            } ?: return@withLock SoundlyResult.Failure(
                SoundlyError.InvalidRequest("sessionId")
            )
            val file = File(entity.tempFilePath)
            if (!file.isFile || !fileStore.isManaged(file)) {
                dao.deleteRecoverySession(sessionId)
                return@withLock SoundlyResult.Failure(
                    SoundlyError.FileNotFound(entity.tempFilePath)
                )
            }
            val saved = readRecoveryMetadata(entity.projectId)
            val format = saved?.format ?: inferRecoveryFormat(file)
            val recovered = PreparedRecording(
                sessionId = entity.id,
                projectId = entity.projectId,
                originalVariantId = saved?.originalVariantId ?: idGenerator(),
                config = RecordingConfig(
                    title = saved?.title,
                    scene = saved?.scene ?: RecordingScene.AUTO,
                    format = format
                ),
                recoveryFile = file,
                preparedAt = entity.updatedAt
            )
            mutableState.value = RecordingState.Finalizing(sessionId)
            val recoveredMarkers = saved?.markers.orEmpty()
            return@withLock finalizeRecording(
                recovered,
                entity.recordedDurationMs,
                recoveredMarkers
            )
        }

    override suspend fun loadStaticWaveform(
        projectId: String,
        variantId: String
    ): SoundlyResult<StaticWaveform> = withContext(dispatchers.io) {
        try {
            val file = fileStore.waveformFile(projectId, variantId)
            if (!file.isFile) {
                return@withContext SoundlyResult.Failure(
                    SoundlyError.FileNotFound(file.absolutePath)
                )
            }
            SoundlyResult.Success(staticWaveforms.read(projectId, variantId))
        } catch (error: IllegalArgumentException) {
            SoundlyResult.Failure(SoundlyError.InvalidRequest("waveform"))
        } catch (error: Exception) {
            SoundlyResult.Failure(SoundlyError.FileOperationFailed(error))
        }
    }

    private suspend fun finalizeRecording(
        recording: PreparedRecording,
        fallbackDurationMs: Long,
        savedMarkers: List<AudioMarker>
    ): SoundlyResult<RecordingResult> {
        val metadata = when (val result = metadataReader.read(Uri.fromFile(recording.recoveryFile))) {
            is SoundlyResult.Success -> result.value
            is SoundlyResult.Failure -> {
                markRecoveryRequired(recording)
                mutableState.value = RecordingState.Failed(recording.sessionId, result.error)
                return result
            }
        }
        val outputFormat = recording.config.format
        val destination = fileStore.originalFile(recording.projectId, outputFormat)
        var recoverySourceRetained = true
        return try {
            val finalized = recordingFileFinalizer.finalize(
                recording.recoveryFile,
                destination,
                outputFormat
            )
            recoverySourceRetained = finalized.recoverySourceRetained
            val now = clock()
            val duration = metadata.durationMs.takeIf { it > 0L } ?: fallbackDurationMs
            val staticWaveform = withContext(dispatchers.io) {
                waveformGenerator.generate(destination, duration).also { generated ->
                    staticWaveforms.write(
                        recording.projectId,
                        recording.originalVariantId,
                        generated
                    )
                }
            }
            val original = AudioVariant(
                id = recording.originalVariantId,
                projectId = recording.projectId,
                filePath = destination.absolutePath,
                presetOption = PresetOption.RAW,
                isOriginal = true,
                sampleRate = metadata.sampleRate,
                channels = metadata.channels,
                durationMs = duration,
                format = outputFormat,
                mimeType = recordingMimeType(outputFormat),
                fileSizeBytes = destination.length(),
                createdAt = recording.preparedAt,
                variantType = VariantType.ORIGINAL
            )
            val project = AudioProject(
                id = recording.projectId,
                title = normalizedTitle(recording.config.title, recording.preparedAt),
                sourceType = AudioSourceType.RECORDED,
                scene = recording.config.scene,
                original = original,
                markers = savedMarkers,
                status = ProjectStatus.AVAILABLE,
                createdAt = recording.preparedAt,
                updatedAt = now
            )
            when (val saveResult = projects.saveProject(project)) {
                is SoundlyResult.Success -> {
                    if (recoverySourceRetained) {
                        fileStore.deleteFile(recording.recoveryFile)
                    }
                    dao.deleteRecoverySession(recording.sessionId)
                    deleteRecoveryMetadata(recording.projectId)
                    prepared = null
                    accumulatedDurationMs = 0L
                    segmentStartedAtMs = null
                    markerBuffer.clear()
                    mutableMarkers.value = emptyList()
                    signalAccumulator.reset()
                    mutableState.value = RecordingState.Idle
                    SoundlyResult.Success(
                        RecordingResult(
                            sessionId = recording.sessionId,
                            projectId = recording.projectId,
                            originalVariantId = recording.originalVariantId,
                            durationMs = duration,
                            fileSizeBytes = destination.length(),
                            waveform = staticWaveform
                        )
                    )
                }

                is SoundlyResult.Failure -> {
                    staticWaveforms.delete(recording.projectId, recording.originalVariantId)
                    rollbackFinalizedRecording(
                        recording.recoveryFile,
                        destination,
                        recoverySourceRetained
                    )
                    markRecoveryRequired(recording)
                    mutableState.value = RecordingState.Failed(
                        recording.sessionId,
                        saveResult.error
                    )
                    saveResult
                }
            }
        } catch (cancellation: CancellationException) {
            runCatching {
                staticWaveforms.delete(recording.projectId, recording.originalVariantId)
            }
            runCatching {
                rollbackFinalizedRecording(
                    recording.recoveryFile,
                    destination,
                    recoverySourceRetained
                )
            }
            markRecoveryRequired(recording)
            throw cancellation
        } catch (error: Exception) {
            runCatching {
                staticWaveforms.delete(recording.projectId, recording.originalVariantId)
            }
            runCatching {
                rollbackFinalizedRecording(
                    recording.recoveryFile,
                    destination,
                    recoverySourceRetained
                )
            }
            markRecoveryRequired(recording, error)
            val mapped = if (error is AudioDecodingException) {
                SoundlyError.DecodeFailed(error.cause)
            } else {
                SoundlyError.RecordingFailed(error)
            }
            mutableState.value = RecordingState.Failed(recording.sessionId, mapped)
            SoundlyResult.Failure(mapped)
        }
    }

    private fun startMonitoring(current: PreparedRecording) {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            var lastPersistenceAt = elapsedRealtime()
            while (isActive) {
                if (mutableState.value !is RecordingState.Recording) break
                val duration = currentDurationMs()
                val bytes = current.recoveryFile.length()
                val signal = signalAccumulator.sample(
                    maxAmplitude = engine?.maxAmplitude() ?: 0,
                    positionMs = duration,
                    measuredAtMs = clock()
                )
                mutableMeter.tryEmit(signal.meter)
                signal.waveformFrame?.let(mutableWaveform::tryEmit)
                updateVisibleState(current, duration, bytes)
                val elapsed = elapsedRealtime()
                if (elapsed - lastPersistenceAt >= RECOVERY_UPDATE_INTERVAL_MS) {
                    if (runCatching { fileStore.availableBytes() }.getOrDefault(Long.MAX_VALUE) <
                        MINIMUM_FREE_BYTES
                    ) {
                        scope.launch {
                            interruptRecording(RecordingInterruptionReason.INSUFFICIENT_STORAGE)
                        }
                        break
                    }
                    persistProgress(current, statusForCurrentState())
                    lastPersistenceAt = elapsed
                }
                delay(METER_INTERVAL_MS)
            }
        }
    }

    private suspend fun stopMonitoring() {
        monitorJob?.cancelAndJoin()
        monitorJob = null
    }

    private fun flushPendingWaveform(positionMs: Long) {
        signalAccumulator.flush(positionMs)?.let(mutableWaveform::tryEmit)
    }

    private fun publishMarkers() {
        mutableMarkers.value = markerBuffer.toList()
    }

    private fun updateVisibleState(
        current: PreparedRecording,
        duration: Long = currentDurationMs(),
        bytes: Long = current.recoveryFile.length()
    ) {
        mutableState.value = when (mutableState.value) {
            is RecordingState.Recording -> current.recordingState(
                duration,
                bytes,
                markerBuffer.size
            )
            is RecordingState.Paused -> RecordingState.Paused(
                current.sessionId,
                duration,
                bytes,
                markerBuffer.size
            )

            else -> mutableState.value
        }
    }

    private fun currentDurationMs(): Long = accumulatedDurationMs +
        (segmentStartedAtMs?.let { (elapsedRealtime() - it).coerceAtLeast(0L) } ?: 0L)

    private suspend fun persistProgress(current: PreparedRecording, status: String) {
        runCatching {
            dao.updateRecoverySession(
                current.sessionId,
                currentDurationMs(),
                current.recoveryFile.length(),
                status,
                clock()
            )
        }
    }

    private suspend fun markRecoveryRequired(
        current: PreparedRecording,
        error: Exception? = null
    ) {
        runCatching {
            dao.upsertRecoverySession(
                RecoverySessionEntity(
                    id = current.sessionId,
                    projectId = current.projectId,
                    tempFilePath = current.recoveryFile.absolutePath,
                    recordedDurationMs = currentDurationMs(),
                    recordedBytes = current.recoveryFile.length(),
                    status = STATUS_RECOVERY_REQUIRED,
                    updatedAt = clock()
                )
            )
        }
        if (error != null) writeRecoveryMetadata(current)
    }

    private suspend fun recordingFailure(
        current: PreparedRecording,
        error: Exception
    ): SoundlyResult.Failure {
        accumulatedDurationMs = currentDurationMs()
        segmentStartedAtMs = null
        stopMonitoring()
        deviceMonitor.stop()
        flushPendingWaveform(accumulatedDurationMs)
        focusController.abandon()
        markRecoveryRequired(current, error)
        val mapped = SoundlyErrorMapper.recording(error)
        mutableState.value = RecordingState.Failed(current.sessionId, mapped)
        return SoundlyResult.Failure(mapped)
    }

    private fun releasePrepared(deleteFiles: Boolean) {
        val current = prepared
        engine?.release()
        deviceMonitor.stop()
        focusController.abandon()
        engine = null
        prepared = null
        markerBuffer.clear()
        mutableMarkers.value = emptyList()
        signalAccumulator.reset()
        if (deleteFiles && current != null) {
            runCatching { fileStore.deleteFile(current.recoveryFile) }
            runCatching { fileStore.deleteProject(current.projectId) }
        }
    }

    private fun isActivelyRecording(): Boolean =
        mutableState.value is RecordingState.Recording ||
            mutableState.value is RecordingState.Paused ||
            mutableState.value is RecordingState.Interrupted ||
            mutableState.value is RecordingState.Finalizing

    private fun hasRecordPermission(): Boolean = recordPermissionChecker()

    private fun requestRecordingFocus(): Boolean = focusController.request { reason ->
        scope.launch { interruptRecording(reason) }
    }

    private suspend fun interruptRecording(
        reason: RecordingInterruptionReason
    ) = operationMutex.withLock {
        val current = prepared ?: return@withLock
        if (mutableState.value !is RecordingState.Recording) return@withLock
        try {
            val frozenDurationMs = currentDurationMs()
            requireNotNull(engine).pause()
            accumulatedDurationMs = frozenDurationMs
            segmentStartedAtMs = null
            stopMonitoring()
            deviceMonitor.stop()
            flushPendingWaveform(frozenDurationMs)
            focusController.abandon()
            persistProgress(current, STATUS_INTERRUPTED)
            mutableState.value = RecordingState.Interrupted(
                current.sessionId,
                reason,
                durationMs = accumulatedDurationMs,
                recordedBytes = current.recoveryFile.length(),
                markerCount = markerBuffer.size
            )
            foregroundController.refresh()
        } catch (error: Exception) {
            recordingFailure(current, error)
        }
    }

    private fun startDeviceMonitoring() {
        deviceMonitor.start(
            activeDeviceId = { engine?.routedInputDeviceId() },
            onInputDisconnected = {
                scope.launch {
                    interruptRecording(RecordingInterruptionReason.INPUT_DEVICE_DISCONNECTED)
                }
            },
            onMicrophoneUnavailable = {
                scope.launch {
                    interruptRecording(RecordingInterruptionReason.MICROPHONE_UNAVAILABLE)
                }
            }
        )
    }

    private suspend fun handleEngineFailure(failure: RecorderEngineFailure) =
        operationMutex.withLock {
            val current = prepared ?: return@withLock
            if (mutableState.value !is RecordingState.Recording) return@withLock
            accumulatedDurationMs = currentDurationMs()
            segmentStartedAtMs = null
            stopMonitoring()
            deviceMonitor.stop()
            flushPendingWaveform(accumulatedDurationMs)
            focusController.abandon()
            engine?.release()
            engine = null
            markRecoveryRequired(current)
            val reason = when (failure) {
                RecorderEngineFailure.MICROPHONE_UNAVAILABLE ->
                    RecordingInterruptionReason.MICROPHONE_UNAVAILABLE
                RecorderEngineFailure.UNKNOWN -> RecordingInterruptionReason.UNKNOWN
            }
            mutableState.value = RecordingState.Failed(
                current.sessionId,
                SoundlyError.RecordingInterrupted(reason)
            )
            foregroundController.stop()
        }

    private fun validateConfig(config: RecordingConfig): SoundlyError? = when {
        config.format !in setOf(AudioFormat.M4A, AudioFormat.AAC) ->
            SoundlyError.UnsupportedFormat(config.format.name)

        config.sampleRate !in SUPPORTED_SAMPLE_RATES ->
            SoundlyError.InvalidRequest("sampleRate")

        config.channels !in 1..2 -> SoundlyError.InvalidRequest("channels")
        config.title != null && config.title.length > MAX_TITLE_LENGTH ->
            SoundlyError.InvalidRequest("title")

        else -> null
    }

    private fun statusForCurrentState(): String = when (mutableState.value) {
        is RecordingState.Paused -> STATUS_PAUSED
        else -> STATUS_RECORDING
    }

    private fun normalizedTitle(title: String?, startedAt: Long): String = title
        ?.trim()
        ?.take(MAX_TITLE_LENGTH)
        ?.takeIf(String::isNotBlank)
        ?: "Recording ${requireNotNull(TITLE_DATE_FORMAT.get()).format(Date(startedAt))}"

    private fun writeRecoveryMetadata(current: PreparedRecording) {
        runCatching {
            val properties = Properties().apply {
                setProperty(KEY_ORIGINAL_VARIANT_ID, current.originalVariantId)
                setProperty(KEY_TITLE, current.config.title.orEmpty())
                setProperty(KEY_SCENE, current.config.scene.name)
                setProperty(KEY_FORMAT, current.config.format.name)
                setProperty(KEY_MARKER_COUNT, markerBuffer.size.toString())
                markerBuffer.forEachIndexed { index, marker ->
                    setProperty("marker.$index.id", marker.id)
                    setProperty("marker.$index.position", marker.positionMs.toString())
                    setProperty("marker.$index.label", marker.label.orEmpty())
                    setProperty("marker.$index.created", marker.createdAt.toString())
                }
            }
            val bytes = ByteArrayOutputStream().use { output ->
                properties.store(output, null)
                output.toByteArray()
            }
            fileStore.copyAtomically(
                bytes.inputStream(),
                recoveryMetadataFile(current.projectId)
            )
        }
    }

    private fun readRecoveryMetadata(projectId: String): SavedRecoveryMetadata? = runCatching {
        val properties = Properties().apply {
            FileInputStream(recoveryMetadataFile(projectId)).use(::load)
        }
        val count = properties.getProperty(KEY_MARKER_COUNT)?.toIntOrNull() ?: 0
        val recoveredMarkers = (0 until count).mapNotNull { index ->
            val id = properties.getProperty("marker.$index.id") ?: return@mapNotNull null
            val position = properties.getProperty("marker.$index.position")
                ?.toLongOrNull() ?: return@mapNotNull null
            AudioMarker(
                id = id,
                projectId = projectId,
                positionMs = position,
                label = properties.getProperty("marker.$index.label")
                    ?.takeIf(String::isNotBlank),
                createdAt = properties.getProperty("marker.$index.created")
                    ?.toLongOrNull() ?: clock()
            )
        }
        SavedRecoveryMetadata(
            originalVariantId = properties.getProperty(KEY_ORIGINAL_VARIANT_ID),
            title = properties.getProperty(KEY_TITLE)?.takeIf(String::isNotBlank),
            scene = properties.getProperty(KEY_SCENE)
                ?.let { runCatching { enumValueOf<RecordingScene>(it) }.getOrNull() },
            format = properties.getProperty(KEY_FORMAT)
                ?.let { runCatching { enumValueOf<AudioFormat>(it) }.getOrNull() }
                ?: AudioFormat.M4A,
            markers = recoveredMarkers
        )
    }.getOrNull()

    private fun recoveryMetadataFile(projectId: String): File =
        fileStore.projectMetadataFile(projectId, RECOVERY_METADATA_FILE)

    private fun deleteRecoveryMetadata(projectId: String) {
        runCatching {
            val file = recoveryMetadataFile(projectId)
            if (file.exists()) fileStore.deleteFile(file)
        }
    }

    private fun inferRecoveryFormat(file: File): AudioFormat =
        if (file.name.contains(".aac.")) AudioFormat.AAC else AudioFormat.M4A

    private fun recordingMimeType(format: AudioFormat): String = when (format) {
        AudioFormat.M4A -> "audio/mp4"
        AudioFormat.AAC -> "audio/aac"
        else -> error("Unsupported recording format: $format")
    }

    private fun rollbackFinalizedRecording(
        recoveryFile: File,
        destination: File,
        recoverySourceRetained: Boolean
    ) {
        if (!destination.exists()) return
        if (recoverySourceRetained) {
            fileStore.deleteFile(destination)
        } else if (!recoveryFile.exists()) {
            fileStore.moveAtomically(destination, recoveryFile)
        }
    }

    private data class PreparedRecording(
        val sessionId: String,
        val projectId: String,
        val originalVariantId: String,
        val config: RecordingConfig,
        val recoveryFile: File,
        val preparedAt: Long
    ) {
        fun recordingState(durationMs: Long, bytes: Long, markerCount: Int) =
            RecordingState.Recording(sessionId, durationMs, bytes, markerCount)
    }

    private data class SavedRecoveryMetadata(
        val originalVariantId: String,
        val title: String?,
        val scene: RecordingScene?,
        val format: AudioFormat,
        val markers: List<AudioMarker>
    )

    private companion object {
        const val MINIMUM_FREE_BYTES = 16L * 1024L * 1024L
        const val METER_INTERVAL_MS = 50L
        const val RECOVERY_UPDATE_INTERVAL_MS = 1_000L
        const val MAX_TITLE_LENGTH = 120
        const val MAX_MARKER_LABEL_LENGTH = 80
        const val STATUS_RECORDING = "RECORDING"
        const val STATUS_PAUSED = "PAUSED"
        const val STATUS_INTERRUPTED = "INTERRUPTED"
        const val STATUS_RECOVERY_REQUIRED = "RECOVERY_REQUIRED"
        const val RECOVERY_METADATA_FILE = "recovery.properties"
        const val KEY_ORIGINAL_VARIANT_ID = "originalVariantId"
        const val KEY_TITLE = "title"
        const val KEY_SCENE = "scene"
        const val KEY_FORMAT = "format"
        const val KEY_MARKER_COUNT = "markerCount"
        val RECOVERY_FORMAT = AudioFormat.AAC
        val SUPPORTED_SAMPLE_RATES = setOf(8_000, 16_000, 22_050, 32_000, 44_100, 48_000)
        val TITLE_DATE_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue() = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        }
    }
}
