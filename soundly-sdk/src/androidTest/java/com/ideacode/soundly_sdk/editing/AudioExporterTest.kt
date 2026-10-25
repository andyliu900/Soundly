package com.ideacode.soundly_sdk.editing

import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ideacode.soundly_sdk.api.error.SoundlyError
import com.ideacode.soundly_sdk.api.model.audio.dsp.PresetOption
import com.ideacode.soundly_sdk.api.model.domain.AudioFormat
import com.ideacode.soundly_sdk.api.model.domain.AudioProject
import com.ideacode.soundly_sdk.api.model.domain.AudioSourceType
import com.ideacode.soundly_sdk.api.model.domain.AudioVariant
import com.ideacode.soundly_sdk.api.model.domain.VariantType
import com.ideacode.soundly_sdk.api.model.export.ExportDestination
import com.ideacode.soundly_sdk.api.model.export.ExportRequest
import com.ideacode.soundly_sdk.api.model.export.ExportQuality
import com.ideacode.soundly_sdk.api.model.export.ExportStage
import com.ideacode.soundly_sdk.api.model.export.FileConflictStrategy
import com.ideacode.soundly_sdk.api.model.export.ShareRequest
import com.ideacode.soundly_sdk.api.result.SoundlyResult
import com.ideacode.soundly_sdk.history.data.db.AppDatabase
import com.ideacode.soundly_sdk.project.repository.RoomAudioProjectRepository
import com.ideacode.soundly_sdk.storage.DefaultAudioFileStore
import com.ideacode.soundly_sdk.settings.SharedPreferencesSoundlySettingsRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class AudioExporterTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: AppDatabase
    private lateinit var fileStore: DefaultAudioFileStore
    private lateinit var repository: RoomAudioProjectRepository
    private lateinit var exporter: AndroidAudioExporter
    private lateinit var settingsRepository: SharedPreferencesSoundlySettingsRepository
    private val preferences = context.getSharedPreferences(
        "audio-exporter-test-settings",
        Context.MODE_PRIVATE
    )
    private val exportedUris = mutableListOf<Uri>()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        fileStore = DefaultAudioFileStore(context.filesDir, context.cacheDir)
        repository = RoomAudioProjectRepository(database.audioProjectDao(), fileStore)
        preferences.edit().clear().commit()
        settingsRepository = SharedPreferencesSoundlySettingsRepository(context, preferences)
        exporter = AndroidAudioExporter(
            context,
            repository,
            fileStore,
            settingsRepository
        )
        fileStore.clearAllLocalFiles()
    }

    @After
    fun tearDown() {
        exportedUris.forEach { uri -> runCatching { context.contentResolver.delete(uri, null, null) } }
        fileStore.clearAllLocalFiles()
        preferences.edit().clear().commit()
        database.close()
    }

    @Test
    fun prepareShareCreatesReadableContentUriAndGrantIntent() = runBlocking {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val project = createProject("share_project", "中文会议", bytes)

        val result = exporter.prepareShare(
            ShareRequest(project.id, project.original.id)
        )

        assertTrue("Unexpected result: $result", result is SoundlyResult.Success)
        val share = (result as SoundlyResult.Success).value
        assertEquals("中文会议.wav", share.displayName)
        assertEquals("audio/wav", share.mimeType)
        assertEquals(Intent.ACTION_SEND, share.sendIntent.action)
        assertEquals("audio/wav", share.sendIntent.type)
        assertTrue(
            share.sendIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
        )
        assertNotNull(share.sendIntent.clipData)
        val uri = Uri.parse(share.contentUri)
        val copied = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        assertArrayEquals(bytes, copied)
    }

    @Test
    fun createDocumentIntentKeepsUnicodeNameAndCorrectMimeType() {
        val result = exporter.createDocumentIntent("访谈记录", AudioFormat.M4A)

        assertTrue(result is SoundlyResult.Success)
        val intent = (result as SoundlyResult.Success).value
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
        assertEquals("audio/mp4", intent.type)
        assertEquals("访谈记录.m4a", intent.getStringExtra(Intent.EXTRA_TITLE))
        assertTrue(intent.categories?.contains(Intent.CATEGORY_OPENABLE) == true)
    }

    @Test
    fun downloadsExportPublishesReadableMediaStoreItem() = runBlocking {
        val bytes = byteArrayOf(10, 20, 30, 40)
        val project = createProject("export_project", "Export", bytes)
        val displayName = "soundly-test-${System.nanoTime()}"
        val progress = mutableListOf<Pair<ExportStage, Int>>()

        val result = exporter.export(
            ExportRequest(
                projectId = project.id,
                variantId = project.original.id,
                displayName = displayName,
                format = AudioFormat.WAV,
                destination = ExportDestination.Downloads,
                conflictStrategy = FileConflictStrategy.FAIL
            )
        ) { update ->
            progress += update.stage to update.percent
        }

        assertTrue("Unexpected result: $result", result is SoundlyResult.Success)
        val exported = (result as SoundlyResult.Success).value
        val uri = Uri.parse(exported.destinationUri).also(exportedUris::add)
        assertEquals("$displayName.wav", exported.displayName)
        assertEquals(ExportQuality.PRESERVE_SOURCE, exported.quality)
        val copied = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        assertArrayEquals(bytes, copied)
        assertEquals(ExportStage.PREPARING to 0, progress.first())
        assertTrue(progress.any { it.first == ExportStage.WRITING })
        assertEquals(ExportStage.COMPLETED to 100, progress.last())
    }

    @Test
    fun exportUsingDefaultsUsesPersistedFormatAndProjectTitle() = runBlocking {
        val project = createProject(
            "defaults_export_project",
            "默认导出-${System.nanoTime()}",
            byteArrayOf(7, 8, 9)
        )

        val result = exporter.exportUsingDefaults(
            projectId = project.id,
            variantId = project.original.id,
            destination = ExportDestination.Downloads,
            conflictStrategy = FileConflictStrategy.FAIL
        )

        assertTrue("Unexpected result: $result", result is SoundlyResult.Success)
        val exported = (result as SoundlyResult.Success).value
        exportedUris += Uri.parse(exported.destinationUri)
        assertEquals("${project.title}.wav", exported.displayName)
        assertEquals(AudioFormat.WAV, exported.format)
    }

    @Test
    fun compressedM4aIsStreamDecodedAndExportedAsWav() = runBlocking {
        val project = createM4aProject("m4a_to_wav_project")
        val progress = mutableListOf<ExportStage>()
        val displayName = "soundly-wav-${System.nanoTime()}"

        val result = exporter.export(
            ExportRequest(
                projectId = project.id,
                variantId = project.original.id,
                displayName = displayName,
                format = AudioFormat.WAV,
                destination = ExportDestination.Downloads,
                conflictStrategy = FileConflictStrategy.FAIL
            )
        ) { update -> progress += update.stage }

        assertTrue("Unexpected result: $result", result is SoundlyResult.Success)
        val exported = (result as SoundlyResult.Success).value
        val uri = Uri.parse(exported.destinationUri).also(exportedUris::add)
        val wavHeader = context.contentResolver.openInputStream(uri)?.use { input ->
            input.readBytes().also { assertTrue(it.size > 44) }.copyOfRange(0, 12)
        }
        assertEquals("RIFF", wavHeader?.copyOfRange(0, 4)?.toString(Charsets.US_ASCII))
        assertEquals("WAVE", wavHeader?.copyOfRange(8, 12)?.toString(Charsets.US_ASCII))
        assertEquals(ExportQuality.PCM_16_BIT, exported.quality)
        assertTrue(progress.contains(ExportStage.ENCODING))
        assertEquals(ExportStage.COMPLETED, progress.last())
    }

    @Test
    fun mediaStoreRenameAndReplaceStrategiesPreserveExpectedContent() = runBlocking {
        val firstProject = createProject("conflict_first", "First", byteArrayOf(1, 1, 1))
        val secondProject = createProject("conflict_second", "Second", byteArrayOf(2, 2, 2))
        val displayName = "soundly-conflict-${System.nanoTime()}"

        val first = exportDownloads(firstProject, displayName, FileConflictStrategy.FAIL)
        val renamed = exportDownloads(firstProject, displayName, FileConflictStrategy.RENAME)
        val replaced = exportDownloads(secondProject, displayName, FileConflictStrategy.REPLACE)

        val firstValue = (first as SoundlyResult.Success).value
        val renamedValue = (renamed as SoundlyResult.Success).value
        val replacedValue = (replaced as SoundlyResult.Success).value
        exportedUris += Uri.parse(firstValue.destinationUri)
        exportedUris += Uri.parse(renamedValue.destinationUri)
        exportedUris += Uri.parse(replacedValue.destinationUri)
        assertEquals("$displayName (1).wav", renamedValue.displayName)
        assertEquals("$displayName.wav", replacedValue.displayName)
        val replacedBytes = context.contentResolver
            .openInputStream(Uri.parse(replacedValue.destinationUri))
            ?.use { it.readBytes() }
        assertArrayEquals(byteArrayOf(2, 2, 2), replacedBytes)
    }

    @Test
    fun exportRejectsPretendTranscoding() = runBlocking {
        val project = createProject("format_project", "Format", byteArrayOf(1, 2, 3))

        val result = exporter.export(
            ExportRequest(
                projectId = project.id,
                variantId = project.original.id,
                displayName = "converted",
                format = AudioFormat.MP3,
                destination = ExportDestination.Downloads
            )
        )

        assertTrue(result is SoundlyResult.Failure)
        assertTrue((result as SoundlyResult.Failure).error is SoundlyError.UnsupportedFormat)
    }

    @Test
    fun preserveSourceRejectsFormatChangeInsteadOfSilentlyChangingQuality() = runBlocking {
        val project = createM4aProject("preserve_mismatch_project")

        val result = exporter.export(
            ExportRequest(
                projectId = project.id,
                variantId = project.original.id,
                displayName = "preserve-mismatch",
                format = AudioFormat.WAV,
                destination = ExportDestination.Downloads,
                quality = ExportQuality.PRESERVE_SOURCE
            )
        )

        assertTrue(result is SoundlyResult.Failure)
        val error = (result as SoundlyResult.Failure).error
        assertTrue(error is SoundlyError.InvalidRequest)
        assertEquals("quality", (error as SoundlyError.InvalidRequest).field)
    }

    @Test
    fun explicitPcm16QualityCopiesStreamingWavAndReportsActualPolicy() = runBlocking {
        val projectId = "pcm16_variant_project"
        val generated = com.ideacode.soundly_sdk.core.util.WavWriter(context.cacheDir).write(
            pcm = FloatArray(TEST_SAMPLE_RATE / 5) { 0.05f },
            sampleRate = TEST_SAMPLE_RATE,
            channels = 1,
            fileName = "pcm16-streaming-variant"
        )
        val source = fileStore.originalFile(projectId, AudioFormat.WAV)
        generated.copyTo(source, overwrite = true)
        generated.delete()
        val project = createProjectFromFile(projectId, "PCM16", source)
        val displayName = "soundly-pcm16-${System.nanoTime()}"

        val result = exporter.export(
            ExportRequest(
                projectId = project.id,
                variantId = project.original.id,
                displayName = displayName,
                format = AudioFormat.WAV,
                destination = ExportDestination.Downloads,
                conflictStrategy = FileConflictStrategy.FAIL,
                quality = ExportQuality.PCM_16_BIT
            )
        )

        assertTrue("Unexpected result: $result", result is SoundlyResult.Success)
        val exported = (result as SoundlyResult.Success).value
        exportedUris += Uri.parse(exported.destinationUri)
        assertEquals(ExportQuality.PCM_16_BIT, exported.quality)
        assertEquals(source.length(), exported.fileSizeBytes)
    }

    private suspend fun createProject(
        projectId: String,
        title: String,
        bytes: ByteArray
    ): AudioProject {
        val file = fileStore.originalFile(projectId, AudioFormat.WAV).apply {
            writeBytes(bytes)
        }
        val original = AudioVariant(
            id = "original_$projectId",
            projectId = projectId,
            filePath = file.absolutePath,
            presetOption = PresetOption.RAW,
            isOriginal = true,
            sampleRate = 44_100,
            channels = 1,
            durationMs = 1_000L,
            format = AudioFormat.WAV,
            mimeType = "audio/wav",
            fileSizeBytes = file.length(),
            createdAt = 1L,
            variantType = VariantType.ORIGINAL
        )
        val project = AudioProject(
            id = projectId,
            title = title,
            sourceType = AudioSourceType.RECORDED,
            scene = null,
            original = original,
            createdAt = 1L,
            updatedAt = 1L
        )
        assertTrue(repository.saveProject(project) is SoundlyResult.Success)
        return project
    }

    private suspend fun createM4aProject(projectId: String): AudioProject {
        val file = fileStore.originalFile(projectId, AudioFormat.M4A)
        writeTestM4a(file)
        val original = AudioVariant(
            id = "original_$projectId",
            projectId = projectId,
            filePath = file.absolutePath,
            presetOption = PresetOption.RAW,
            isOriginal = true,
            sampleRate = TEST_SAMPLE_RATE,
            channels = 1,
            durationMs = TEST_DURATION_MS,
            format = AudioFormat.M4A,
            mimeType = MediaFormat.MIMETYPE_AUDIO_AAC,
            fileSizeBytes = file.length(),
            createdAt = 1L,
            variantType = VariantType.ORIGINAL
        )
        val project = AudioProject(
            id = projectId,
            title = "M4A recording",
            sourceType = AudioSourceType.RECORDED,
            scene = null,
            original = original,
            createdAt = 1L,
            updatedAt = 1L
        )
        assertTrue(repository.saveProject(project) is SoundlyResult.Success)
        return project
    }

    private suspend fun createProjectFromFile(
        projectId: String,
        title: String,
        file: File
    ): AudioProject {
        val original = AudioVariant(
            id = "original_$projectId",
            projectId = projectId,
            filePath = file.absolutePath,
            presetOption = PresetOption.RAW,
            isOriginal = true,
            sampleRate = TEST_SAMPLE_RATE,
            channels = 1,
            durationMs = TEST_DURATION_MS,
            format = AudioFormat.WAV,
            mimeType = "audio/wav",
            fileSizeBytes = file.length(),
            createdAt = 1L,
            variantType = VariantType.ORIGINAL
        )
        val project = AudioProject(
            id = projectId,
            title = title,
            sourceType = AudioSourceType.RECORDED,
            scene = null,
            original = original,
            createdAt = 1L,
            updatedAt = 1L
        )
        assertTrue(repository.saveProject(project) is SoundlyResult.Success)
        return project
    }

    private fun writeTestM4a(destination: File) {
        destination.parentFile?.mkdirs()
        destination.delete()
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            TEST_SAMPLE_RATE,
            1
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8_192)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(
            destination.absolutePath,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        )
        var muxerStarted = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            val totalSamples = (TEST_SAMPLE_RATE * TEST_DURATION_MS / 1_000L).toInt()
            var submittedSamples = 0
            var inputEnded = false
            var outputEnded = false
            var trackIndex = -1
            while (!outputEnded) {
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(10_000L)
                    if (inputIndex >= 0) {
                        val input = requireNotNull(codec.getInputBuffer(inputIndex)).apply {
                            clear()
                            order(ByteOrder.LITTLE_ENDIAN)
                        }
                        if (submittedSamples >= totalSamples) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                submittedSamples * 1_000_000L / TEST_SAMPLE_RATE,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputEnded = true
                        } else {
                            val samples = minOf(input.remaining() / 2, totalSamples - submittedSamples)
                            repeat(samples) { input.putShort(0) }
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                samples * 2,
                                submittedSamples * 1_000_000L / TEST_SAMPLE_RATE,
                                0
                            )
                            submittedSamples += samples
                        }
                    }
                }
                when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000L)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }

                    else -> if (outputIndex >= 0) {
                        val output = requireNotNull(codec.getOutputBuffer(outputIndex))
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            info.size = 0
                        }
                        if (info.size > 0) {
                            check(muxerStarted)
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            muxer.writeSampleData(trackIndex, output, info)
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            if (muxerStarted) runCatching { muxer.stop() }
            muxer.release()
        }
        assertTrue(destination.length() > 0L)
    }

    private suspend fun exportDownloads(
        project: AudioProject,
        displayName: String,
        strategy: FileConflictStrategy
    ) = exporter.export(
        ExportRequest(
            projectId = project.id,
            variantId = project.original.id,
            displayName = displayName,
            format = AudioFormat.WAV,
            destination = ExportDestination.Downloads,
            conflictStrategy = strategy
        )
    ).also { result ->
        assertTrue("Unexpected result: $result", result is SoundlyResult.Success)
    }

    private companion object {
        const val TEST_SAMPLE_RATE = 44_100
        const val TEST_DURATION_MS = 200L
    }
}
