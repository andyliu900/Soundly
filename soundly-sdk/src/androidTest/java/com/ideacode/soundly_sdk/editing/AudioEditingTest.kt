package com.ideacode.soundly_sdk.editing

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ideacode.soundly_sdk.api.model.audio.dsp.PresetOption
import com.ideacode.soundly_sdk.api.model.audio.AudioSourceRef
import com.ideacode.soundly_sdk.api.model.audio.processor.ProcessingProgress
import com.ideacode.soundly_sdk.api.model.audio.processor.ProcessingRequest
import com.ideacode.soundly_sdk.api.model.enhancement.EnhancementStage
import com.ideacode.soundly_sdk.api.model.enhancement.EnhancementRequest
import com.ideacode.soundly_sdk.api.model.enhancement.EnhancementTaskState
import com.ideacode.soundly_sdk.api.model.domain.AudioFormat
import com.ideacode.soundly_sdk.api.model.domain.AudioMarker
import com.ideacode.soundly_sdk.api.model.domain.AudioProject
import com.ideacode.soundly_sdk.api.model.domain.AudioSourceType
import com.ideacode.soundly_sdk.api.model.domain.AudioVariant
import com.ideacode.soundly_sdk.api.model.domain.ProjectStatus
import com.ideacode.soundly_sdk.api.model.domain.VariantType
import com.ideacode.soundly_sdk.api.capability.TrimRange
import com.ideacode.soundly_sdk.api.result.SoundlyResult
import com.ideacode.soundly_sdk.core.util.WavWriter
import com.ideacode.soundly_sdk.history.data.db.AppDatabase
import com.ideacode.soundly_sdk.importer.AndroidAudioMetadataReader
import com.ideacode.soundly_sdk.importer.DefaultAudioImporter
import com.ideacode.soundly_sdk.project.repository.RoomAudioProjectRepository
import com.ideacode.soundly_sdk.processor.createAudioProcessingService
import com.ideacode.soundly_sdk.storage.DefaultAudioFileStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.awaitCancellation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioEditingTest {

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
    }

    @Test
    fun wavTrimCreatesProjectVariant() = runBlocking {
        val store = DefaultAudioFileStore(context.filesDir, context.cacheDir)
        val repository = RoomAudioProjectRepository(database.audioProjectDao(), store)
        val source = store.originalFile("trim_project", AudioFormat.WAV)
        val generated = WavWriter(context.cacheDir).write(
            pcm = FloatArray(44_100) { 0.1f },
            sampleRate = 44_100,
            channels = 1,
            fileName = "trim-source"
        )
        generated.copyTo(source, overwrite = true)
        generated.delete()
        val original = AudioVariant(
            id = "trim_original",
            projectId = "trim_project",
            filePath = source.absolutePath,
            presetOption = PresetOption.RAW,
            isOriginal = true,
            sampleRate = 44_100,
            channels = 1,
            durationMs = 1_000L,
            format = AudioFormat.WAV,
            mimeType = "audio/wav",
            fileSizeBytes = source.length(),
            createdAt = 1L,
            variantType = VariantType.ORIGINAL
        )
        repository.saveProject(
            AudioProject(
                id = "trim_project",
                title = "Trim test",
                sourceType = AudioSourceType.IMPORTED,
                scene = null,
                original = original,
                markers = listOf(
                    AudioMarker("marker_before", "trim_project", 100L, createdAt = 1L),
                    AudioMarker("marker_start", "trim_project", 250L, createdAt = 1L),
                    AudioMarker("marker_middle", "trim_project", 500L, createdAt = 1L),
                    AudioMarker("marker_end", "trim_project", 750L, createdAt = 1L)
                ),
                status = ProjectStatus.AVAILABLE,
                createdAt = 1L,
                updatedAt = 1L
            )
        )
        val trimmer = AndroidAudioTrimmer(
            projects = repository,
            metadataReader = AndroidAudioMetadataReader(context),
            fileStore = store,
            idGenerator = { "trim_variant" },
            clock = { 2L }
        )

        val preview = trimmer.previewTrim(
            "trim_project",
            "trim_original",
            TrimRange(250L, 750L)
        )
        assertTrue("Unexpected preview: $preview", preview is SoundlyResult.Success)
        val previewValue = (preview as SoundlyResult.Success).value
        assertEquals(500L, previewValue.durationMs)
        assertEquals(listOf(0L, 250L), previewValue.markers.map { it.trimmedPositionMs })
        assertEquals(
            listOf("marker_start", "marker_middle"),
            previewValue.markers.map { it.marker.id }
        )

        val result = trimmer.applyTrim(
            "trim_project",
            "trim_original",
            TrimRange(250L, 750L)
        )

        assertTrue("Unexpected result: $result", result is SoundlyResult.Success)
        assertEquals(
            listOf(0L, 250L),
            (result as SoundlyResult.Success).value.markers.map { it.trimmedPositionMs }
        )
        val project = repository.getProject("trim_project") as SoundlyResult.Success
        assertEquals(1, project.value.variants.size)
        val trimmed = project.value.variants.single()
        assertEquals(VariantType.TRIMMED, trimmed.variantType)
        assertEquals("trim_original", trimmed.sourceVariantId)
        assertEquals(250L, trimmed.trimStartMs)
        assertEquals(750L, trimmed.trimEndMs)
        assertTrue(trimmed.durationMs in 490L..510L)

        val derivedPreview = trimmer.previewTrim(
            "trim_project",
            "trim_variant",
            TrimRange(100L, 400L)
        )
        assertTrue("Unexpected derived preview: $derivedPreview", derivedPreview is SoundlyResult.Success)
        val derivedMarkers = (derivedPreview as SoundlyResult.Success).value.markers
        assertEquals(listOf("marker_middle"), derivedMarkers.map { it.marker.id })
        assertEquals(listOf(150L), derivedMarkers.map { it.trimmedPositionMs })

        val invalidRange = trimmer.previewTrim(
            "trim_project",
            "trim_variant",
            TrimRange(0L, 600L)
        )
        assertTrue(invalidRange is SoundlyResult.Failure)
    }

    @Test
    fun enhancementOutputIsAddedToProject() = runBlocking {
        val store = DefaultAudioFileStore(context.filesDir, context.cacheDir)
        val repository = RoomAudioProjectRepository(database.audioProjectDao(), store)
        val source = createWav("enhance_project", "enhance-source")
        val original = createProject(repository, source, "enhance_project", "enhance_original")
        val processedFile = WavWriter(context.cacheDir).write(
            pcm = FloatArray(44_100) { 0.08f },
            sampleRate = 44_100,
            channels = 1,
            fileName = "enhance-result"
        )
        val fakeProcessor = object : StreamingEnhancementProcessor {
            override suspend fun process(
                source: java.io.File,
                destination: java.io.File,
                preset: PresetOption,
                onProgress: (StreamingEnhancementProgress) -> Unit
            ): StreamingEnhancementResult {
                processedFile.copyTo(destination, overwrite = true)
                return StreamingEnhancementResult(
                    sampleRate = 44_100,
                    channels = 1,
                    durationMs = 1_000L,
                    pcmFrames = 44_100L
                )
            }
        }
        val enhancer = ProjectAudioEnhancer(
            processor = fakeProcessor,
            projects = repository,
            metadataReader = AndroidAudioMetadataReader(context),
            fileStore = store,
            taskIdGenerator = { "enhancement_task" },
            variantIdGenerator = { "enhanced_variant" },
            clock = { 3L }
        )

        val result = enhancer.enhance(
            projectId = "enhance_project",
            sourceVariantId = original.id,
            preset = PresetOption.DEFAULT
        )

        assertTrue("Unexpected result: $result", result is SoundlyResult.Success)
        val project = repository.getProject("enhance_project") as SoundlyResult.Success
        assertEquals(1, project.value.variants.size)
        assertEquals(VariantType.ENHANCED, project.value.variants.single().variantType)
        assertEquals(PresetOption.DEFAULT, project.value.variants.single().presetOption)
        assertEquals(original.id, project.value.variants.single().sourceVariantId)
        assertTrue(java.io.File(project.value.variants.single().filePath).isFile)
    }

    @Test
    fun multipleStreamingEnhancementsRemainIndependentVersions() = runBlocking {
        val store = DefaultAudioFileStore(context.filesDir, context.cacheDir)
        val repository = RoomAudioProjectRepository(database.audioProjectDao(), store)
        val source = createWav("multi_variant_project", "multi-variant-source")
        val original = createProject(
            repository,
            source,
            "multi_variant_project",
            "multi_variant_original"
        )
        val taskIds = ArrayDeque(listOf("task_default", "task_voice"))
        val variantIds = ArrayDeque(listOf("variant_default", "variant_voice"))
        var timestamp = 10L
        val enhancer = ProjectAudioEnhancer(
            processor = AndroidStreamingEnhancementProcessor(),
            projects = repository,
            metadataReader = AndroidAudioMetadataReader(context),
            fileStore = store,
            taskIdGenerator = { taskIds.removeFirst() },
            variantIdGenerator = { variantIds.removeFirst() },
            clock = { timestamp++ }
        )

        val first = enhancer.enhance(
            "multi_variant_project",
            original.id,
            PresetOption.DEFAULT
        )
        val second = enhancer.enhance(
            "multi_variant_project",
            original.id,
            PresetOption.VOICE_STABLE
        )

        assertTrue("Unexpected first enhancement: $first", first is SoundlyResult.Success)
        assertTrue("Unexpected second enhancement: $second", second is SoundlyResult.Success)
        val versions = repository.getVariants("multi_variant_project").successValue()
        assertEquals(3, versions.size)
        assertEquals(original.id, versions.first().id)
        assertEquals(
            setOf(PresetOption.DEFAULT, PresetOption.VOICE_STABLE),
            versions.drop(1).map { it.presetOption }.toSet()
        )
        val firstVariant = versions.first { it.id == "variant_default" }
        val secondVariant = versions.first { it.id == "variant_voice" }
        assertTrue(java.io.File(firstVariant.filePath).isFile)
        assertTrue(java.io.File(secondVariant.filePath).isFile)

        assertTrue(
            repository.deleteVariant("multi_variant_project", firstVariant.id) is
                SoundlyResult.Success
        )
        assertTrue(!java.io.File(firstVariant.filePath).exists())
        assertTrue(java.io.File(secondVariant.filePath).isFile)
        assertEquals(
            listOf(original.id, secondVariant.id),
            repository.getVariants("multi_variant_project").successValue().map { it.id }
        )
    }

    @Test
    fun streamingEnhancedWavCanBeTrimmedWithLineageAndBoundaryMarkers() = runBlocking {
        val store = DefaultAudioFileStore(context.filesDir, context.cacheDir)
        val repository = RoomAudioProjectRepository(database.audioProjectDao(), store)
        val source = createWav("enhanced_trim_project", "enhanced-trim-source")
        val original = createProject(
            repository,
            source,
            "enhanced_trim_project",
            "enhanced_trim_original"
        )
        repository.addMarker(
            AudioMarker("enhanced_trim_start", original.projectId, 200L, createdAt = 1L)
        )
        repository.addMarker(
            AudioMarker("enhanced_trim_inside", original.projectId, 500L, createdAt = 2L)
        )
        repository.addMarker(
            AudioMarker("enhanced_trim_end", original.projectId, 800L, createdAt = 3L)
        )
        val enhancer = ProjectAudioEnhancer(
            processor = AndroidStreamingEnhancementProcessor(),
            projects = repository,
            metadataReader = AndroidAudioMetadataReader(context),
            fileStore = store,
            taskIdGenerator = { "enhanced_trim_task" },
            variantIdGenerator = { "enhanced_trim_variant" },
            clock = { 10L }
        )
        val enhanced = enhancer.enhance(
            original.projectId,
            original.id,
            PresetOption.DEFAULT
        ) as SoundlyResult.Success
        val trimmer = AndroidAudioTrimmer(
            projects = repository,
            metadataReader = AndroidAudioMetadataReader(context),
            fileStore = store,
            idGenerator = { "trimmed_enhancement" },
            clock = { 20L }
        )

        val result = trimmer.applyTrim(
            original.projectId,
            enhanced.value.variantId,
            TrimRange(200L, 800L)
        )

        assertTrue("Unexpected trim result: $result", result is SoundlyResult.Success)
        val trimmed = repository.getVariants(original.projectId).successValue()
            .first { it.id == "trimmed_enhancement" }
        assertEquals(VariantType.TRIMMED, trimmed.variantType)
        assertEquals(enhanced.value.variantId, trimmed.sourceVariantId)
        assertEquals(AudioFormat.WAV, trimmed.format)
        assertEquals(44_100, trimmed.sampleRate)
        assertEquals(1, trimmed.channels)
        assertTrue(trimmed.durationMs in 590L..610L)
        assertEquals(
            listOf("enhanced_trim_start", "enhanced_trim_inside"),
            (result as SoundlyResult.Success).value.markers.map { it.marker.id }
        )
        assertEquals(
            listOf(0L, 300L),
            result.value.markers.map { it.trimmedPositionMs }
        )
        assertTrue(java.io.File(trimmed.filePath).length() > 44L)
    }

    @Test
    fun twoPassStreamingProcessorWritesPlayableWavWithMonotonicProgress() = runBlocking {
        val source = WavWriter(context.cacheDir).write(
            pcm = FloatArray(44_100 * 2) { index ->
                if (index % 200 < 100) 0.12f else -0.12f
            },
            sampleRate = 44_100,
            channels = 1,
            fileName = "streaming-enhancement-source"
        )
        val destination = java.io.File(context.cacheDir, "streaming-enhancement-output.wav")
        val progress = mutableListOf<StreamingEnhancementProgress>()
        try {
            val result = AndroidStreamingEnhancementProcessor().process(
                source = source,
                destination = destination,
                preset = PresetOption.DEFAULT,
                onProgress = progress::add
            )

            assertEquals(44_100, result.sampleRate)
            assertEquals(1, result.channels)
            assertTrue(result.durationMs in 1_990L..2_010L)
            assertTrue(destination.isFile && destination.length() > 44L)
            assertEquals("RIFF", destination.inputStream().use { input ->
                String(ByteArray(4).also { input.read(it) }, Charsets.US_ASCII)
            })
            assertTrue(progress.zipWithNext().all { (left, right) ->
                left.percent <= right.percent
            })
            assertEquals(
                listOf(
                    EnhancementStage.LOADING,
                    EnhancementStage.ANALYZING,
                    EnhancementStage.RECOMMENDING,
                    EnhancementStage.PROCESSING,
                    EnhancementStage.WRITING
                ),
                progress.map { it.stage }.distinct()
            )
        } finally {
            source.delete()
            destination.delete()
        }
    }

    @Test
    fun compatibilityProcessorUsesStreamingPathForContentUri() = runBlocking {
        val store = DefaultAudioFileStore(context.filesDir, context.cacheDir)
        val source = WavWriter(context.cacheDir).write(
            pcm = FloatArray(44_100) { 0.1f },
            sampleRate = 44_100,
            channels = 1,
            fileName = "compat-streaming-source"
        )
        val progress = mutableListOf<ProcessingProgress>()
        val processor = createAudioProcessingService(
            audioLoader = null,
            audioProcessor = null,
            streamingProcessor = AndroidStreamingEnhancementProcessor(),
            fileStore = store,
            contentResolver = context.contentResolver
        )
        try {
            val result = processor.process(
                ProcessingRequest(
                    source = AudioSourceRef.ContentUri(Uri.fromFile(source)),
                    presetOption = PresetOption.RAW,
                    outputFileName = "compat-streaming-output"
                ),
                progress::add
            )

            assertTrue(result is SoundlyResult.Success)
            val output = java.io.File((result as SoundlyResult.Success).value.outputFilePath)
            assertTrue(output.isFile && output.length() > 44L)
            assertTrue(progress.zipWithNext().all { (left, right) ->
                left.percent <= right.percent
            })
            assertEquals(100, progress.last().percent)
        } finally {
            source.delete()
            store.clearTemporaryFiles()
        }
    }

    @Test
    fun compatibilityProcessorPersistsProjectAndEnhancedVariant() = runBlocking {
        val store = DefaultAudioFileStore(context.filesDir, context.cacheDir)
        val repository = RoomAudioProjectRepository(database.audioProjectDao(), store)
        val metadataReader = AndroidAudioMetadataReader(context)
        val importer = DefaultAudioImporter(
            contentResolver = context.contentResolver,
            metadataReader = metadataReader,
            projects = repository,
            fileStore = store
        )
        val enhancer = ProjectAudioEnhancer(
            processor = AndroidStreamingEnhancementProcessor(),
            projects = repository,
            metadataReader = metadataReader,
            fileStore = store
        )
        val source = WavWriter(context.cacheDir).write(
            pcm = FloatArray(44_100) { 0.1f },
            sampleRate = 44_100,
            channels = 1,
            fileName = "compat-persisted-source"
        )
        val progress = mutableListOf<ProcessingProgress>()
        val processor = createAudioProcessingService(
            audioLoader = null,
            audioProcessor = null,
            streamingProcessor = AndroidStreamingEnhancementProcessor(),
            fileStore = store,
            contentResolver = context.contentResolver,
            importer = importer,
            enhancer = enhancer,
            projects = repository
        )
        try {
            val result = processor.process(
                ProcessingRequest(
                    source = AudioSourceRef.ContentUri(Uri.fromFile(source)),
                    presetOption = PresetOption.DEFAULT,
                    outputFileName = "Persisted processing"
                ),
                progress::add
            )

            assertTrue("Unexpected result: $result", result is SoundlyResult.Success)
            val processed = (result as SoundlyResult.Success).value
            val projectId = requireNotNull(processed.historyId)
            val project = repository.getProject(projectId).successValue()
            assertEquals("Persisted processing", project.title)
            assertTrue(java.io.File(project.original.filePath).isFile)
            assertEquals(1, project.variants.size)
            val enhanced = project.variants.single()
            assertEquals(VariantType.ENHANCED, enhanced.variantType)
            assertEquals(PresetOption.DEFAULT, enhanced.presetOption)
            assertEquals(project.original.id, enhanced.sourceVariantId)
            assertEquals(enhanced.filePath, processed.outputFilePath)
            assertTrue(java.io.File(enhanced.filePath).isFile)
            assertTrue(!enhanced.filePath.contains("soundly-export-cache"))
            assertTrue(progress.zipWithNext().all { (left, right) ->
                left.percent <= right.percent
            })
            assertEquals(100, progress.last().percent)
        } finally {
            source.delete()
        }
    }

    @Test
    fun compatibilityRawProcessingPersistsOnlyOriginalVersion() = runBlocking {
        val store = DefaultAudioFileStore(context.filesDir, context.cacheDir)
        val repository = RoomAudioProjectRepository(database.audioProjectDao(), store)
        val metadataReader = AndroidAudioMetadataReader(context)
        val importer = DefaultAudioImporter(
            contentResolver = context.contentResolver,
            metadataReader = metadataReader,
            projects = repository,
            fileStore = store
        )
        val enhancer = ProjectAudioEnhancer(
            processor = AndroidStreamingEnhancementProcessor(),
            projects = repository,
            metadataReader = metadataReader,
            fileStore = store
        )
        val source = WavWriter(context.cacheDir).write(
            pcm = FloatArray(4_410) { 0.05f },
            sampleRate = 44_100,
            channels = 1,
            fileName = "compat-raw-source"
        )
        val processor = createAudioProcessingService(
            audioLoader = null,
            audioProcessor = null,
            streamingProcessor = AndroidStreamingEnhancementProcessor(),
            fileStore = store,
            contentResolver = context.contentResolver,
            importer = importer,
            enhancer = enhancer,
            projects = repository
        )
        try {
            val result = processor.process(
                ProcessingRequest(
                    source = AudioSourceRef.LocalFile(source),
                    presetOption = PresetOption.RAW,
                    outputFileName = "Persisted raw"
                )
            )

            assertTrue("Unexpected result: $result", result is SoundlyResult.Success)
            val processed = (result as SoundlyResult.Success).value
            val project = repository.getProject(requireNotNull(processed.historyId)).successValue()
            assertTrue(project.variants.isEmpty())
            assertEquals(project.original.filePath, processed.outputFilePath)
            assertEquals(PresetOption.RAW, processed.presetOption)
        } finally {
            source.delete()
        }
    }

    @Test
    fun compatibilityProcessorRollsBackImportedProjectWhenEnhancementFails() = runBlocking {
        val store = DefaultAudioFileStore(context.filesDir, context.cacheDir)
        val repository = RoomAudioProjectRepository(database.audioProjectDao(), store)
        val metadataReader = AndroidAudioMetadataReader(context)
        val importer = DefaultAudioImporter(
            contentResolver = context.contentResolver,
            metadataReader = metadataReader,
            projects = repository,
            fileStore = store
        )
        val failingStreamingProcessor = object : StreamingEnhancementProcessor {
            override suspend fun process(
                source: java.io.File,
                destination: java.io.File,
                preset: PresetOption,
                onProgress: (StreamingEnhancementProgress) -> Unit
            ): StreamingEnhancementResult = throw java.io.IOException("expected failure")
        }
        val enhancer = ProjectAudioEnhancer(
            processor = failingStreamingProcessor,
            projects = repository,
            metadataReader = metadataReader,
            fileStore = store
        )
        val source = WavWriter(context.cacheDir).write(
            pcm = FloatArray(4_410) { 0.05f },
            sampleRate = 44_100,
            channels = 1,
            fileName = "compat-failure-source"
        )
        val processor = createAudioProcessingService(
            audioLoader = null,
            audioProcessor = null,
            streamingProcessor = failingStreamingProcessor,
            fileStore = store,
            contentResolver = context.contentResolver,
            importer = importer,
            enhancer = enhancer,
            projects = repository
        )
        try {
            val result = processor.process(
                ProcessingRequest(
                    source = AudioSourceRef.LocalFile(source),
                    presetOption = PresetOption.DEFAULT,
                    outputFileName = "Failed processing"
                )
            )

            assertTrue(result is SoundlyResult.Failure)
            assertTrue(repository.observeProjects().first().isEmpty())
        } finally {
            source.delete()
        }
    }

    @Test
    fun enhancementTaskCanBeCancelledAndCleansTemporaryFile() = runBlocking {
        val store = DefaultAudioFileStore(context.filesDir, context.cacheDir)
        val repository = RoomAudioProjectRepository(database.audioProjectDao(), store)
        val source = createWav("cancel_project", "cancel-source")
        val original = createProject(repository, source, "cancel_project", "cancel_original")
        val blockingProcessor = object : StreamingEnhancementProcessor {
            override suspend fun process(
                source: java.io.File,
                destination: java.io.File,
                preset: PresetOption,
                onProgress: (StreamingEnhancementProgress) -> Unit
            ): StreamingEnhancementResult {
                onProgress(StreamingEnhancementProgress(EnhancementStage.ANALYZING, 10))
                awaitCancellation()
            }
        }
        val enhancer = ProjectAudioEnhancer(
            processor = blockingProcessor,
            projects = repository,
            metadataReader = AndroidAudioMetadataReader(context),
            fileStore = store,
            taskIdGenerator = { "cancel_task" },
            variantIdGenerator = { "cancel_variant" }
        )
        val started = enhancer.enhance(
            EnhancementRequest("cancel_project", original.id, PresetOption.DEFAULT)
        ) as SoundlyResult.Success

        val cancelResult = enhancer.cancel(started.value.taskId)

        assertTrue(
            "Unexpected cancel result: $cancelResult; state=${enhancer.tasks.value[started.value.taskId]}",
            cancelResult is SoundlyResult.Success
        )
        assertTrue(enhancer.tasks.value[started.value.taskId] is EnhancementTaskState.Cancelled)
        assertTrue(enhancer.await(started.value.taskId) is SoundlyResult.Failure)
        assertEquals(0L, store.storageUsage().cacheBytes)
        val project = repository.getProject("cancel_project") as SoundlyResult.Success
        assertTrue(project.value.variants.isEmpty())
    }

    @Test
    fun retryCreatesNewTaskAndVariantAfterRetryableFailure() = runBlocking {
        val store = DefaultAudioFileStore(context.filesDir, context.cacheDir)
        val repository = RoomAudioProjectRepository(database.audioProjectDao(), store)
        val source = createWav("retry_project", "retry-source")
        val original = createProject(repository, source, "retry_project", "retry_original")
        val processedFile = WavWriter(context.cacheDir).write(
            pcm = FloatArray(44_100) { 0.05f },
            sampleRate = 44_100,
            channels = 1,
            fileName = "retry-result"
        )
        var attempts = 0
        val processor = object : StreamingEnhancementProcessor {
            override suspend fun process(
                source: java.io.File,
                destination: java.io.File,
                preset: PresetOption,
                onProgress: (StreamingEnhancementProgress) -> Unit
            ): StreamingEnhancementResult {
                attempts++
                if (attempts == 1) throw java.io.IOException("simulated")
                processedFile.copyTo(destination, overwrite = true)
                return StreamingEnhancementResult(44_100, 1, 1_000L, 44_100L)
            }
        }
        val taskIds = ArrayDeque(listOf("failed_task", "retry_task"))
        val variantIds = ArrayDeque(listOf("failed_variant", "retry_variant"))
        val enhancer = ProjectAudioEnhancer(
            processor = processor,
            projects = repository,
            metadataReader = AndroidAudioMetadataReader(context),
            fileStore = store,
            taskIdGenerator = { taskIds.removeFirst() },
            variantIdGenerator = { variantIds.removeFirst() }
        )
        val first = enhancer.enhance(
            EnhancementRequest("retry_project", original.id, PresetOption.DEFAULT)
        ) as SoundlyResult.Success
        assertTrue(enhancer.await(first.value.taskId) is SoundlyResult.Failure)

        val retried = enhancer.retry(first.value.taskId) as SoundlyResult.Success
        val result = enhancer.await(retried.value.taskId) as SoundlyResult.Success

        assertEquals("failed_task", retried.value.retryOfTaskId)
        assertEquals("retry_task", result.value.taskId)
        assertEquals("retry_variant", result.value.variantId)
        assertTrue(enhancer.retry(result.value.taskId) is SoundlyResult.Failure)
        val project = repository.getProject("retry_project") as SoundlyResult.Success
        assertEquals(listOf("retry_variant"), project.value.variants.map { it.id })
        processedFile.delete()
        Unit
    }

    private fun createWav(projectId: String, fileName: String): java.io.File {
        val store = DefaultAudioFileStore(context.filesDir, context.cacheDir)
        val destination = store.originalFile(projectId, AudioFormat.WAV)
        val generated = WavWriter(context.cacheDir).write(
            pcm = FloatArray(44_100) { 0.1f },
            sampleRate = 44_100,
            channels = 1,
            fileName = fileName
        )
        generated.copyTo(destination, overwrite = true)
        generated.delete()
        return destination
    }

    private suspend fun createProject(
        repository: RoomAudioProjectRepository,
        source: java.io.File,
        projectId: String,
        variantId: String
    ): AudioVariant {
        val original = AudioVariant(
            id = variantId,
            projectId = projectId,
            filePath = source.absolutePath,
            presetOption = PresetOption.RAW,
            isOriginal = true,
            sampleRate = 44_100,
            channels = 1,
            durationMs = 1_000L,
            format = AudioFormat.WAV,
            mimeType = "audio/wav",
            fileSizeBytes = source.length(),
            createdAt = 1L,
            variantType = VariantType.ORIGINAL
        )
        repository.saveProject(
            AudioProject(
                id = projectId,
                title = "Editing test",
                sourceType = AudioSourceType.IMPORTED,
                scene = null,
                original = original,
                status = ProjectStatus.AVAILABLE,
                createdAt = 1L,
                updatedAt = 1L
            )
        )
        return original
    }

    private fun <T> SoundlyResult<T>.successValue(): T =
        (this as SoundlyResult.Success).value
}
