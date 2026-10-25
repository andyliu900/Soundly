package com.ideacode.soundly_sdk.importer

import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ideacode.soundly_sdk.api.model.domain.AudioFormat
import com.ideacode.soundly_sdk.api.model.domain.AudioSourceType
import com.ideacode.soundly_sdk.api.model.importer.AudioImportRequest
import com.ideacode.soundly_sdk.api.result.SoundlyResult
import com.ideacode.soundly_sdk.core.util.WavWriter
import com.ideacode.soundly_sdk.history.data.db.AppDatabase
import com.ideacode.soundly_sdk.project.repository.RoomAudioProjectRepository
import com.ideacode.soundly_sdk.storage.DefaultAudioFileStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioImporterTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
        context.cacheDir.resolve("import-source.wav").delete()
        context.filesDir.resolve("soundly/projects/import_project").deleteRecursively()
    }

    @Test
    fun wavImportCopiesFileReadsMetadataAndCreatesProject() = runBlocking {
        val source = WavWriter(context.cacheDir).write(
            pcm = FloatArray(44_100) { 0.1f },
            sampleRate = 44_100,
            channels = 1,
            fileName = "import-source"
        )
        val store = DefaultAudioFileStore(context.filesDir, context.cacheDir)
        val repository = RoomAudioProjectRepository(database.audioProjectDao(), store)
        val ids = ArrayDeque(listOf("import_project", "import_original"))
        val importer = DefaultAudioImporter(
            contentResolver = context.contentResolver,
            metadataReader = AndroidAudioMetadataReader(context),
            projects = repository,
            fileStore = store,
            idGenerator = { ids.removeFirst() },
            clock = { 1_000L }
        )

        val result = importer.importAudio(
            AudioImportRequest(source = Uri.fromFile(source), title = "My recording.wav")
        )

        assertTrue("Unexpected import result: $result", result is SoundlyResult.Success)
        val imported = (result as SoundlyResult.Success).value.project
        assertEquals("My recording", imported.title)
        assertEquals(AudioSourceType.IMPORTED, imported.sourceType)
        assertEquals(AudioFormat.WAV, imported.original.format)
        assertEquals(44_100, imported.original.sampleRate)
        assertTrue(imported.original.durationMs in 990L..1_010L)
        assertTrue(imported.original.fileSizeBytes > 0L)
        assertTrue(java.io.File(imported.original.filePath).exists())
    }
}
