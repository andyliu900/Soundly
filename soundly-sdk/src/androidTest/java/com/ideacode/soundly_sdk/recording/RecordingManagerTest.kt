package com.ideacode.soundly_sdk.recording

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ideacode.soundly_sdk.api.capability.AudioMetadataReader
import com.ideacode.soundly_sdk.api.error.SoundlyError
import com.ideacode.soundly_sdk.api.model.domain.AudioFormat
import com.ideacode.soundly_sdk.api.model.domain.AudioSourceType
import com.ideacode.soundly_sdk.api.model.domain.RecordingScene
import com.ideacode.soundly_sdk.api.model.importer.AudioMetadata
import com.ideacode.soundly_sdk.api.model.recording.RecordingConfig
import com.ideacode.soundly_sdk.api.model.recording.RecordingInterruptionReason
import com.ideacode.soundly_sdk.api.model.recording.RecordingState
import com.ideacode.soundly_sdk.api.model.recording.StaticWaveform
import com.ideacode.soundly_sdk.api.model.settings.RecordingNamingStrategy
import com.ideacode.soundly_sdk.api.model.settings.SoundlySettings
import com.ideacode.soundly_sdk.api.result.SoundlyResult
import com.ideacode.soundly_sdk.history.data.db.AppDatabase
import com.ideacode.soundly_sdk.project.repository.RoomAudioProjectRepository
import com.ideacode.soundly_sdk.project.data.entity.RecoverySessionEntity
import com.ideacode.soundly_sdk.settings.SharedPreferencesSoundlySettingsRepository
import com.ideacode.soundly_sdk.storage.DefaultAudioFileStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RecordingManagerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        context.filesDir.resolve("soundly").deleteRecursively()
    }

    @After
    fun tearDown() {
        database.close()
        context.filesDir.resolve("soundly").deleteRecursively()
        context.getSharedPreferences("soundly-recording-naming-test", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun recordingCreatesProjectOriginalAndMarker() = runBlocking {
        val store = DefaultAudioFileStore(context.filesDir, context.cacheDir)
        val repository = RoomAudioProjectRepository(database.audioProjectDao(), store)
        var elapsed = 1_000L
        val focus = FakeFocusController()
        val recorderEngine = FakeRecorderEngine()
        val ids = ArrayDeque(
            listOf("session_1", "project_1", "original_1", "marker_1", "marker_2")
        )
        val deviceMonitor = FakeDeviceMonitor()
        val manager = AndroidRecordingManager(
            context = context,
            projects = repository,
            metadataReader = FixedMetadataReader(),
            fileStore = store,
            dao = database.audioProjectDao(),
            engineFactory = { recorderEngine },
            recordPermissionChecker = { true },
            focusController = focus,
            deviceMonitor = deviceMonitor,
            waveformGenerator = FakeStaticWaveformGenerator(),
            recordingFileFinalizer = FakeRecordingFileFinalizer(store),
            clock = { 10_000L + elapsed },
            elapsedRealtime = { elapsed },
            idGenerator = { ids.removeFirst() }
        )

        assertTrue(manager.prepare(
            RecordingConfig(
                title = "Test recording",
                scene = RecordingScene.MEETING,
                enableBackgroundNotification = false
            )
        ) is SoundlyResult.Success)
        assertEquals(AudioFormat.AAC, recorderEngine.preparedFormat)
        assertTrue(recorderEngine.outputFile.name.endsWith(".aac.partial"))
        assertTrue(manager.state.value is RecordingState.Ready)
        assertTrue(manager.start() is SoundlyResult.Success)
        elapsed = 2_500L
        val markerResult = manager.addMarker("Decision")
        assertTrue(markerResult is SoundlyResult.Success)
        assertEquals(1_500L, (markerResult as SoundlyResult.Success).value.positionMs)
        val removableMarker = manager.addMarker("Temporary") as SoundlyResult.Success
        assertEquals(2, manager.markers.value.size)
        assertTrue(manager.deleteMarker(removableMarker.value.id) is SoundlyResult.Success)
        assertEquals(listOf("marker_1"), manager.markers.value.map { it.id })
        assertTrue(manager.pause() is SoundlyResult.Success)
        assertTrue(manager.state.value is RecordingState.Paused)
        elapsed = 9_000L
        assertEquals(1_500L, (manager.state.value as RecordingState.Paused).durationMs)
        assertTrue(manager.resume() is SoundlyResult.Success)
        elapsed = 9_500L
        focus.interrupt(RecordingInterruptionReason.PHONE_CALL)
        val interrupted = withTimeout(2_000L) {
            manager.state.filterIsInstance<RecordingState.Interrupted>().first()
        }
        assertEquals(2_000L, interrupted.durationMs)
        assertEquals(RecordingInterruptionReason.PHONE_CALL, interrupted.reason)
        assertTrue(manager.resume() is SoundlyResult.Success)
        elapsed = 10_000L

        val saved = manager.stopAndSave()

        assertTrue("Unexpected result: $saved", saved is SoundlyResult.Success)
        val result = (saved as SoundlyResult.Success).value
        val project = repository.getProject(result.projectId)
        assertTrue(project is SoundlyResult.Success)
        val value = (project as SoundlyResult.Success).value
        assertEquals("Test recording", value.title)
        assertEquals(AudioSourceType.RECORDED, value.sourceType)
        assertEquals(RecordingScene.MEETING, value.scene)
        assertEquals(1, value.markers.size)
        assertTrue(File(value.original.filePath).isFile)
        assertEquals(listOf(0.2f, 0.8f), result.waveform.amplitudes)
        val persistedWaveform = manager.loadStaticWaveform(
            result.projectId,
            result.originalVariantId
        )
        assertEquals(result.waveform, (persistedWaveform as SoundlyResult.Success).value)
        assertTrue(manager.markers.value.isEmpty())
        assertEquals(RecordingState.Idle, manager.state.value)
    }

    @Test
    fun twoHourBackgroundRecordingKeepsLongDurationAndForegroundLifecycle() = runBlocking {
        val store = DefaultAudioFileStore(context.filesDir, context.cacheDir)
        val repository = RoomAudioProjectRepository(database.audioProjectDao(), store)
        var elapsed = 1_000L
        val foreground = FakeForegroundController()
        val ids = ArrayDeque(
            listOf("session_long", "project_long", "original_long")
        )
        val manager = AndroidRecordingManager(
            context = context,
            projects = repository,
            metadataReader = FixedMetadataReader(),
            fileStore = store,
            dao = database.audioProjectDao(),
            engineFactory = { FakeRecorderEngine() },
            recordPermissionChecker = { true },
            focusController = FakeFocusController(),
            deviceMonitor = FakeDeviceMonitor(),
            waveformGenerator = FakeStaticWaveformGenerator(),
            recordingFileFinalizer = FakeRecordingFileFinalizer(store),
            foregroundController = foreground,
            elapsedRealtime = { elapsed },
            idGenerator = { ids.removeFirst() }
        )

        assertTrue(
            manager.prepare(
                RecordingConfig(enableBackgroundNotification = true)
            ) is SoundlyResult.Success
        )
        assertTrue(manager.start() is SoundlyResult.Success)
        assertEquals(1, foreground.startCount)

        elapsed += TWO_HOURS_MS
        assertTrue(manager.pause() is SoundlyResult.Success)

        val paused = manager.state.value as RecordingState.Paused
        assertEquals(TWO_HOURS_MS, paused.durationMs)
        assertEquals(1, foreground.refreshCount)
        assertTrue(manager.discard() is SoundlyResult.Success)
        assertEquals(1, foreground.stopCount)
    }

    @Test
    fun audioFocusLossFreezesRecordingWithTypedReason() = runBlocking {
        val store = DefaultAudioFileStore(context.filesDir, context.cacheDir)
        val repository = RoomAudioProjectRepository(database.audioProjectDao(), store)
        val focus = FakeFocusController()
        val ids = ArrayDeque(listOf("session_focus", "project_focus", "original_focus"))
        val manager = AndroidRecordingManager(
            context = context,
            projects = repository,
            metadataReader = FixedMetadataReader(),
            fileStore = store,
            dao = database.audioProjectDao(),
            engineFactory = { FakeRecorderEngine() },
            recordPermissionChecker = { true },
            focusController = focus,
            deviceMonitor = FakeDeviceMonitor(),
            waveformGenerator = FakeStaticWaveformGenerator(),
            recordingFileFinalizer = FakeRecordingFileFinalizer(store),
            foregroundController = FakeForegroundController(),
            idGenerator = { ids.removeFirst() }
        )
        assertTrue(
            manager.prepare(
                RecordingConfig(enableBackgroundNotification = false)
            ) is SoundlyResult.Success
        )
        assertTrue(manager.start() is SoundlyResult.Success)

        focus.interrupt(RecordingInterruptionReason.AUDIO_FOCUS_LOST)
        val interrupted = withTimeout(2_000L) {
            manager.state.filterIsInstance<RecordingState.Interrupted>().first()
        }

        assertEquals(RecordingInterruptionReason.AUDIO_FOCUS_LOST, interrupted.reason)
        assertTrue(manager.discard() is SoundlyResult.Success)
    }

    @Test
    fun recoverableSessionIsFinalizedIntoProject() = runBlocking {
        val store = DefaultAudioFileStore(context.filesDir, context.cacheDir)
        val repository = RoomAudioProjectRepository(database.audioProjectDao(), store)
        val recoveryFile = store.recoveryFile("session_recovery", AudioFormat.M4A)
            .apply { writeBytes(ByteArray(64)) }
        database.audioProjectDao().upsertRecoverySession(
            RecoverySessionEntity(
                id = "session_recovery",
                projectId = "project_recovery",
                tempFilePath = recoveryFile.absolutePath,
                recordedDurationMs = 3_000L,
                recordedBytes = recoveryFile.length(),
                status = "RECOVERY_REQUIRED",
                updatedAt = 100L
            )
        )
        val manager = AndroidRecordingManager(
            context = context,
            projects = repository,
            metadataReader = FixedMetadataReader(),
            fileStore = store,
            dao = database.audioProjectDao(),
            engineFactory = { FakeRecorderEngine() },
            recordPermissionChecker = { true },
            focusController = FakeFocusController(),
            deviceMonitor = FakeDeviceMonitor(),
            waveformGenerator = FakeStaticWaveformGenerator(),
            recordingFileFinalizer = FakeRecordingFileFinalizer(store),
            idGenerator = { "recovered_original" }
        )

        val pending = manager.getRecoverableRecordings()
        assertTrue(pending is SoundlyResult.Success)
        assertEquals(1, (pending as SoundlyResult.Success).value.size)

        val recovered = manager.recover("session_recovery")

        assertTrue("Unexpected result: $recovered", recovered is SoundlyResult.Success)
        val project = repository.getProject("project_recovery")
        assertTrue(project is SoundlyResult.Success)
        assertTrue(File((project as SoundlyResult.Success).value.original.filePath).isFile)
        assertEquals(
            0,
            (manager.getRecoverableRecordings() as SoundlyResult.Success).value.size
        )
    }

    @Test
    fun recoveryRestoresMarkersPersistedBeforeProcessLoss() = runBlocking {
        val store = DefaultAudioFileStore(context.filesDir, context.cacheDir)
        val repository = RoomAudioProjectRepository(database.audioProjectDao(), store)
        val ids = ArrayDeque(
            listOf("session_markers", "project_markers", "original_markers", "marker_saved")
        )
        val firstManager = AndroidRecordingManager(
            context = context,
            projects = repository,
            metadataReader = FixedMetadataReader(),
            fileStore = store,
            dao = database.audioProjectDao(),
            engineFactory = { FakeRecorderEngine() },
            recordPermissionChecker = { true },
            focusController = FakeFocusController(),
            deviceMonitor = FakeDeviceMonitor(),
            waveformGenerator = FakeStaticWaveformGenerator(),
            recordingFileFinalizer = FakeRecordingFileFinalizer(store),
            idGenerator = { ids.removeFirst() }
        )
        assertTrue(firstManager.prepare(
            RecordingConfig(title = "Recover markers", enableBackgroundNotification = false)
        ) is SoundlyResult.Success)
        assertTrue(firstManager.start() is SoundlyResult.Success)
        assertTrue(firstManager.addMarker("Important") is SoundlyResult.Success)
        assertTrue(firstManager.pause() is SoundlyResult.Success)

        val recoveredManager = AndroidRecordingManager(
            context = context,
            projects = repository,
            metadataReader = FixedMetadataReader(),
            fileStore = store,
            dao = database.audioProjectDao(),
            engineFactory = { FakeRecorderEngine() },
            recordPermissionChecker = { true },
            focusController = FakeFocusController(),
            deviceMonitor = FakeDeviceMonitor(),
            waveformGenerator = FakeStaticWaveformGenerator(),
            recordingFileFinalizer = FakeRecordingFileFinalizer(store),
            idGenerator = { "unused_recovery_id" }
        )

        val recovered = recoveredManager.recover("session_markers")

        assertTrue(recovered is SoundlyResult.Success)
        val project = repository.getProject("project_markers") as SoundlyResult.Success
        assertEquals(listOf("marker_saved"), project.value.markers.map { it.id })
        assertEquals(listOf("Important"), project.value.markers.map { it.label })
    }

    @Test
    fun prepareUsingDefaultsAppliesPersistedNamingStrategy() {
        runBlocking {
            val store = DefaultAudioFileStore(context.filesDir, context.cacheDir)
            val repository = RoomAudioProjectRepository(database.audioProjectDao(), store)
            val preferences = context.getSharedPreferences(
                "soundly-recording-naming-test",
                Context.MODE_PRIVATE
            ).apply { edit().clear().commit() }
            val settings = SharedPreferencesSoundlySettingsRepository(context, preferences)
            assertTrue(
                settings.update(
                    SoundlySettings(
                        recordInBackground = false,
                        namingStrategy = RecordingNamingStrategy.TIMESTAMP
                    )
                ) is SoundlyResult.Success
            )
            val ids = ArrayDeque(
                listOf("session_defaults", "project_defaults", "original_defaults")
            )
            val manager = AndroidRecordingManager(
                context = context,
                projects = repository,
                metadataReader = FixedMetadataReader(),
                fileStore = store,
                dao = database.audioProjectDao(),
                settings = settings,
                engineFactory = { FakeRecorderEngine() },
                recordPermissionChecker = { true },
                focusController = FakeFocusController(),
                deviceMonitor = FakeDeviceMonitor(),
                waveformGenerator = FakeStaticWaveformGenerator(),
                recordingFileFinalizer = FakeRecordingFileFinalizer(store),
                clock = { 123_456L },
                elapsedRealtime = { 1_000L },
                idGenerator = { ids.removeFirst() }
            )

            assertTrue(manager.prepareUsingDefaults(null) is SoundlyResult.Success)
            assertTrue(manager.start() is SoundlyResult.Success)
            val saved = manager.stopAndSave()

            assertTrue("Unexpected result: $saved", saved is SoundlyResult.Success)
            val project = repository.getProject("project_defaults")
            assertTrue(project is SoundlyResult.Success)
            assertEquals(
                "Recording 123456",
                (project as SoundlyResult.Success).value.title
            )
        }
    }

    @Test
    fun inputDeviceDisconnectFreezesRecordingWithTypedReason() = runBlocking {
        val store = DefaultAudioFileStore(context.filesDir, context.cacheDir)
        val repository = RoomAudioProjectRepository(database.audioProjectDao(), store)
        val deviceMonitor = FakeDeviceMonitor()
        val ids = ArrayDeque(listOf("session_device", "project_device", "original_device"))
        val manager = AndroidRecordingManager(
            context = context,
            projects = repository,
            metadataReader = FixedMetadataReader(),
            fileStore = store,
            dao = database.audioProjectDao(),
            engineFactory = { FakeRecorderEngine() },
            recordPermissionChecker = { true },
            focusController = FakeFocusController(),
            deviceMonitor = deviceMonitor,
            waveformGenerator = FakeStaticWaveformGenerator(),
            recordingFileFinalizer = FakeRecordingFileFinalizer(store),
            idGenerator = { ids.removeFirst() }
        )
        assertTrue(manager.prepare(RecordingConfig(enableBackgroundNotification = false)) is
            SoundlyResult.Success)
        assertTrue(manager.start() is SoundlyResult.Success)

        deviceMonitor.disconnectActiveInput()
        val interrupted = withTimeout(2_000L) {
            manager.state.filterIsInstance<RecordingState.Interrupted>().first()
        }

        assertEquals(RecordingInterruptionReason.INPUT_DEVICE_DISCONNECTED, interrupted.reason)
        assertTrue(manager.discard() is SoundlyResult.Success)
    }

    @Test
    fun microphoneSilencingFreezesRecordingWithTypedReason() = runBlocking {
        val store = DefaultAudioFileStore(context.filesDir, context.cacheDir)
        val repository = RoomAudioProjectRepository(database.audioProjectDao(), store)
        val deviceMonitor = FakeDeviceMonitor()
        val ids = ArrayDeque(listOf("session_silenced", "project_silenced", "original_silenced"))
        val manager = AndroidRecordingManager(
            context = context,
            projects = repository,
            metadataReader = FixedMetadataReader(),
            fileStore = store,
            dao = database.audioProjectDao(),
            engineFactory = { FakeRecorderEngine() },
            recordPermissionChecker = { true },
            focusController = FakeFocusController(),
            deviceMonitor = deviceMonitor,
            waveformGenerator = FakeStaticWaveformGenerator(),
            recordingFileFinalizer = FakeRecordingFileFinalizer(store),
            idGenerator = { ids.removeFirst() }
        )
        assertTrue(manager.prepare(RecordingConfig(enableBackgroundNotification = false)) is
            SoundlyResult.Success)
        assertTrue(manager.start() is SoundlyResult.Success)

        deviceMonitor.silenceMicrophone()
        val interrupted = withTimeout(2_000L) {
            manager.state.filterIsInstance<RecordingState.Interrupted>().first()
        }

        assertEquals(RecordingInterruptionReason.MICROPHONE_UNAVAILABLE, interrupted.reason)
        assertTrue(manager.discard() is SoundlyResult.Success)
    }

    @Test
    fun lowStorageDuringRecordingInterruptsAndPersistsRecoveryProgress() = runBlocking {
        var availableBytes = Long.MAX_VALUE
        val store = DefaultAudioFileStore(
            context.filesDir,
            context.cacheDir,
            availableBytesProvider = { availableBytes }
        )
        val repository = RoomAudioProjectRepository(database.audioProjectDao(), store)
        val ids = ArrayDeque(listOf("session_storage", "project_storage", "original_storage"))
        val manager = AndroidRecordingManager(
            context = context,
            projects = repository,
            metadataReader = FixedMetadataReader(),
            fileStore = store,
            dao = database.audioProjectDao(),
            engineFactory = { FakeRecorderEngine() },
            recordPermissionChecker = { true },
            focusController = FakeFocusController(),
            deviceMonitor = FakeDeviceMonitor(),
            waveformGenerator = FakeStaticWaveformGenerator(),
            recordingFileFinalizer = FakeRecordingFileFinalizer(store),
            idGenerator = { ids.removeFirst() }
        )
        assertTrue(manager.prepare(RecordingConfig(enableBackgroundNotification = false)) is
            SoundlyResult.Success)
        assertTrue(manager.start() is SoundlyResult.Success)

        availableBytes = 0L
        val interrupted = withTimeout(3_000L) {
            manager.state.filterIsInstance<RecordingState.Interrupted>().first()
        }

        assertEquals(RecordingInterruptionReason.INSUFFICIENT_STORAGE, interrupted.reason)
        assertEquals(
            "INTERRUPTED",
            database.audioProjectDao().getRecoverySession("session_storage")?.status
        )
        val resume = manager.resume()
        assertEquals(
            SoundlyError.InsufficientStorage,
            (resume as SoundlyResult.Failure).error
        )
        availableBytes = Long.MAX_VALUE
        assertTrue(manager.discard() is SoundlyResult.Success)
    }

    @Test
    fun occupiedMicrophoneReturnsTypedError() = runBlocking {
        val store = DefaultAudioFileStore(context.filesDir, context.cacheDir)
        val repository = RoomAudioProjectRepository(database.audioProjectDao(), store)
        val ids = ArrayDeque(listOf("session_busy", "project_busy", "original_busy"))
        val manager = AndroidRecordingManager(
            context = context,
            projects = repository,
            metadataReader = FixedMetadataReader(),
            fileStore = store,
            dao = database.audioProjectDao(),
            engineFactory = { FailingStartRecorderEngine() },
            recordPermissionChecker = { true },
            focusController = FakeFocusController(),
            deviceMonitor = FakeDeviceMonitor(),
            waveformGenerator = FakeStaticWaveformGenerator(),
            recordingFileFinalizer = FakeRecordingFileFinalizer(store),
            idGenerator = { ids.removeFirst() }
        )
        assertTrue(manager.prepare(RecordingConfig(enableBackgroundNotification = false)) is
            SoundlyResult.Success)

        val started = manager.start()

        assertEquals(
            SoundlyError.MicrophoneUnavailable,
            (started as SoundlyResult.Failure).error
        )
        assertTrue(manager.state.value is RecordingState.Failed)
        assertTrue(manager.discard() is SoundlyResult.Success)
    }

    @Test
    fun savedRecordingKeepsRequestedContainerWhenExtractorMimeIsAmbiguous() = runBlocking {
        val store = DefaultAudioFileStore(context.filesDir, context.cacheDir)
        val repository = RoomAudioProjectRepository(database.audioProjectDao(), store)
        val ids = ArrayDeque(listOf("session_aac", "project_aac", "original_aac"))
        val manager = AndroidRecordingManager(
            context = context,
            projects = repository,
            metadataReader = FixedMetadataReader(),
            fileStore = store,
            dao = database.audioProjectDao(),
            engineFactory = { FakeRecorderEngine() },
            recordPermissionChecker = { true },
            focusController = FakeFocusController(),
            deviceMonitor = FakeDeviceMonitor(),
            waveformGenerator = FakeStaticWaveformGenerator(),
            recordingFileFinalizer = FakeRecordingFileFinalizer(store),
            idGenerator = { ids.removeFirst() }
        )
        assertTrue(
            manager.prepare(
                RecordingConfig(
                    format = AudioFormat.AAC,
                    enableBackgroundNotification = false
                )
            ) is SoundlyResult.Success
        )
        assertTrue(manager.start() is SoundlyResult.Success)

        val saved = manager.stopAndSave()

        assertTrue(saved is SoundlyResult.Success)
        val project = repository.getProject("project_aac") as SoundlyResult.Success
        assertEquals(AudioFormat.AAC, project.value.original.format)
        assertEquals("audio/aac", project.value.original.mimeType)
        assertTrue(project.value.original.filePath.endsWith(".aac"))
    }

    private open class FakeRecorderEngine : RecorderEngine {
        lateinit var outputFile: File
            private set
        var preparedFormat: AudioFormat? = null
            private set

        override fun prepare(config: RecordingConfig, outputFile: File) {
            this.outputFile = outputFile
            preparedFormat = config.format
            outputFile.parentFile?.mkdirs()
        }

        override fun start() {
            outputFile.writeBytes(ByteArray(64))
        }
        override fun pause() = Unit
        override fun resume() = Unit

        override fun stop() {
            outputFile.writeBytes(ByteArray(128) { it.toByte() })
        }

        override fun release() = Unit
        override fun maxAmplitude(): Int = 16_384

        override fun routedInputDeviceId(): Int = 42
    }

    private class FailingStartRecorderEngine : FakeRecorderEngine() {
        override fun start() {
            throw MicrophoneUnavailableException()
        }
    }

    private class FakeFocusController : RecordingFocusController {
        private var onInterrupted: ((RecordingInterruptionReason) -> Unit)? = null

        override fun request(
            onInterrupted: (RecordingInterruptionReason) -> Unit
        ): Boolean {
            this.onInterrupted = onInterrupted
            return true
        }

        override fun abandon() = Unit

        fun interrupt(reason: RecordingInterruptionReason) {
            requireNotNull(onInterrupted).invoke(reason)
        }
    }

    private class FakeDeviceMonitor : RecordingDeviceMonitor {
        private var activeDeviceId: (() -> Int?)? = null
        private var onInputDisconnected: (() -> Unit)? = null
        private var onMicrophoneUnavailable: (() -> Unit)? = null

        override fun start(
            activeDeviceId: () -> Int?,
            onInputDisconnected: () -> Unit,
            onMicrophoneUnavailable: () -> Unit
        ) {
            this.activeDeviceId = activeDeviceId
            this.onInputDisconnected = onInputDisconnected
            this.onMicrophoneUnavailable = onMicrophoneUnavailable
        }

        override fun stop() {
            activeDeviceId = null
            onInputDisconnected = null
            onMicrophoneUnavailable = null
        }

        fun disconnectActiveInput() {
            checkNotNull(activeDeviceId?.invoke())
            requireNotNull(onInputDisconnected).invoke()
        }

        fun silenceMicrophone() {
            requireNotNull(onMicrophoneUnavailable).invoke()
        }
    }

    private class FakeForegroundController : RecordingForegroundController {
        var startCount = 0
            private set
        var refreshCount = 0
            private set
        var stopCount = 0
            private set

        override fun start() {
            startCount++
        }

        override fun refresh() {
            refreshCount++
        }

        override fun stop() {
            stopCount++
        }
    }

    private class FakeStaticWaveformGenerator : StaticWaveformGenerator {
        override suspend fun generate(source: File, durationMs: Long): StaticWaveform =
            StaticWaveform(
                durationMs = durationMs,
                sampleIntervalMs = 50,
                amplitudes = listOf(0.2f, 0.8f)
            )
    }

    private class FakeRecordingFileFinalizer(
        private val store: DefaultAudioFileStore
    ) : RecordingFileFinalizer {
        override fun finalize(
            recoveryFile: File,
            destination: File,
            outputFormat: AudioFormat
        ): FinalizedRecordingFile = FinalizedRecordingFile(
            file = store.moveAtomically(recoveryFile, destination),
            recoverySourceRetained = false
        )
    }

    private class FixedMetadataReader : AudioMetadataReader {
        override suspend fun read(source: Uri): SoundlyResult<AudioMetadata> =
            SoundlyResult.Success(
                AudioMetadata(
                    displayName = "recording.m4a",
                    format = AudioFormat.M4A,
                    mimeType = "audio/mp4",
                    durationMs = 2_000L,
                    fileSizeBytes = 128L,
                    sampleRate = 44_100,
                    channels = 1,
                    bitRate = 128_000
                )
            )
    }

    private companion object {
        const val TWO_HOURS_MS = 2L * 60L * 60L * 1_000L
    }
}
