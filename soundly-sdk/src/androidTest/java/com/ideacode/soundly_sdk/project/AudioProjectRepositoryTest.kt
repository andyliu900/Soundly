package com.ideacode.soundly_sdk.project

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ideacode.soundly_sdk.api.model.audio.dsp.PresetOption
import com.ideacode.soundly_sdk.api.model.domain.AudioFormat
import com.ideacode.soundly_sdk.api.model.domain.AudioProject
import com.ideacode.soundly_sdk.api.model.domain.AudioMarker
import com.ideacode.soundly_sdk.api.model.domain.AudioSourceType
import com.ideacode.soundly_sdk.api.model.domain.AudioVariant
import com.ideacode.soundly_sdk.api.model.domain.VariantType
import com.ideacode.soundly_sdk.api.result.SoundlyResult
import com.ideacode.soundly_sdk.api.error.SoundlyError
import com.ideacode.soundly_sdk.history.data.db.AppDatabase
import com.ideacode.soundly_sdk.project.repository.RoomAudioProjectRepository
import com.ideacode.soundly_sdk.storage.DefaultAudioFileStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioProjectRepositoryTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var database: AppDatabase
    private lateinit var repository: RoomAudioProjectRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomAudioProjectRepository(
            dao = database.audioProjectDao(),
            fileStore = DefaultAudioFileStore(context.filesDir, context.cacheDir),
            clock = { 2_000L }
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun saveObserveRenameAndDeleteProject() = runBlocking {
        val project = project("project_repo_test")
        val originalPath = project.original.filePath

        assertTrue(repository.saveProject(project) is SoundlyResult.Success)
        assertEquals(project.id, repository.observeProjects().first().single().id)
        assertTrue(repository.renameProject(project.id, "Renamed") is SoundlyResult.Success)
        assertEquals("Renamed", repository.getProject(project.id).successValue().title)
        assertTrue(repository.searchProjects("name").first().single().title == "Renamed")

        val marker = AudioMarker(
            id = "marker_1",
            projectId = project.id,
            positionMs = 500L,
            createdAt = 1_500L
        )
        assertTrue(repository.addMarker(marker) is SoundlyResult.Success)
        assertEquals(marker.id, repository.getProject(project.id).successValue().markers.single().id)
        assertTrue(repository.deleteMarker(project.id, marker.id) is SoundlyResult.Success)

        val enhancedFile = DefaultAudioFileStore(context.filesDir, context.cacheDir)
            .variantFile(project.id, "enhanced_1", AudioFormat.WAV)
            .apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val enhanced = project.original.copy(
            id = "enhanced_1",
            filePath = enhancedFile.absolutePath,
            presetOption = PresetOption.DEFAULT,
            isOriginal = false,
            format = AudioFormat.WAV,
            variantType = VariantType.ENHANCED
        )
        assertTrue(repository.addVariant(project.id, enhanced) is SoundlyResult.Success)
        assertEquals(enhanced.id, repository.getProject(project.id).successValue().variants.single().id)
        assertEquals(
            listOf(project.original.id, enhanced.id),
            repository.getVariants(project.id).successValue().map { it.id }
        )
        assertTrue(repository.deleteVariant(project.id, enhanced.id) is SoundlyResult.Success)
        assertTrue(!enhancedFile.exists())

        assertTrue(repository.deleteProject(project.id) is SoundlyResult.Success)
        assertTrue(repository.observeProjects().first().isEmpty())
        assertTrue(!java.io.File(originalPath).exists())
    }

    @Test
    fun sourceVariantCannotBeDeletedWhileTrimDependsOnIt() = runBlocking {
        val project = project("project_lineage_test")
        assertTrue(repository.saveProject(project) is SoundlyResult.Success)
        val store = DefaultAudioFileStore(context.filesDir, context.cacheDir)
        val enhanced = project.original.copy(
            id = "lineage_enhanced",
            filePath = store.variantFile(project.id, "lineage_enhanced", AudioFormat.WAV)
                .apply { writeBytes(byteArrayOf(1, 2)) }.absolutePath,
            presetOption = PresetOption.DEFAULT,
            isOriginal = false,
            format = AudioFormat.WAV,
            variantType = VariantType.ENHANCED,
            sourceVariantId = project.original.id
        )
        val trimmed = enhanced.copy(
            id = "lineage_trimmed",
            filePath = store.variantFile(project.id, "lineage_trimmed", AudioFormat.WAV)
                .apply { writeBytes(byteArrayOf(3, 4)) }.absolutePath,
            variantType = VariantType.TRIMMED,
            sourceVariantId = enhanced.id,
            trimStartMs = 100L,
            trimEndMs = 800L,
            durationMs = 700L
        )
        assertTrue(repository.addVariant(project.id, enhanced) is SoundlyResult.Success)
        assertTrue(repository.addVariant(project.id, trimmed) is SoundlyResult.Success)

        val blocked = repository.deleteVariant(project.id, enhanced.id)

        assertTrue(blocked is SoundlyResult.Failure)
        assertTrue((blocked as SoundlyResult.Failure).error is SoundlyError.VariantInUse)
        assertTrue(repository.deleteVariant(project.id, trimmed.id) is SoundlyResult.Success)
        assertTrue(repository.deleteVariant(project.id, enhanced.id) is SoundlyResult.Success)
    }

    private fun project(id: String): AudioProject {
        val file = DefaultAudioFileStore(context.filesDir, context.cacheDir)
            .originalFile(id, AudioFormat.M4A)
            .apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val original = AudioVariant(
            id = "original_$id",
            projectId = id,
            filePath = file.absolutePath,
            presetOption = PresetOption.RAW,
            isOriginal = true,
            sampleRate = 44_100,
            channels = 1,
            durationMs = 1_000,
            format = AudioFormat.M4A,
            fileSizeBytes = file.length(),
            createdAt = 1_000,
            variantType = VariantType.ORIGINAL
        )
        return AudioProject(
            id = id,
            title = "Original name",
            sourceType = AudioSourceType.IMPORTED,
            scene = null,
            original = original,
            createdAt = 1_000,
            updatedAt = 1_000
        )
    }

    private fun <T> SoundlyResult<T>.successValue(): T =
        (this as SoundlyResult.Success).value
}
