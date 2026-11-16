package com.ideacode.soundly_sdk.importer

import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ideacode.soundly_sdk.api.model.importer.AudioImportRequest
import com.ideacode.soundly_sdk.api.model.importer.ImportOperationStatus
import com.ideacode.soundly_sdk.api.result.SoundlyResult
import com.ideacode.soundly_sdk.api.error.SoundlyError
import com.ideacode.soundly_sdk.api.capability.AudioMetadataReader
import com.ideacode.soundly_sdk.core.util.WavWriter
import com.ideacode.soundly_sdk.history.data.db.AppDatabase
import com.ideacode.soundly_sdk.project.data.entity.ImportOperationEntity
import com.ideacode.soundly_sdk.project.data.entity.toEntity
import com.ideacode.soundly_sdk.project.data.dao.AudioProjectDao
import com.ideacode.soundly_sdk.project.repository.RoomAudioProjectRepository
import com.ideacode.soundly_sdk.storage.DefaultAudioFileStore
import com.ideacode.soundly_sdk.storage.AudioFileStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RecoverableAudioImporterTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var db: AppDatabase
    private lateinit var files: DefaultAudioFileStore
    private lateinit var importer: RecoverableAudioImporter
    private val projects = mutableListOf<String>()
    private lateinit var source: File
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        files = DefaultAudioFileStore(context.filesDir, context.cacheDir)
        val reader = AndroidAudioMetadataReader(context)
        val repo = RoomAudioProjectRepository(db.audioProjectDao(), files)
        val legacy = DefaultAudioImporter(context.contentResolver, reader, repo, files)
        importer = RecoverableAudioImporter(legacy, context.contentResolver, reader, db.audioProjectDao(), files)
        source = WavWriter(context.cacheDir).write(FloatArray(44100) { .1f }, 44100, 1, "i1-source")
    }
    @After fun cleanup() { projects.forEach(files::deleteProject); source.delete(); db.close() }

    @Test fun repeatedAndConcurrentSubmissionsReturnTheSameCommittedProject() = runBlocking {
        val id = UUID.randomUUID().toString()
        val request = AudioImportRequest(Uri.fromFile(source))
        val reader = AndroidAudioMetadataReader(context)
        val repo = RoomAudioProjectRepository(db.audioProjectDao(), files)
        val secondImporter = RecoverableAudioImporter(DefaultAudioImporter(context.contentResolver, reader, repo, files), context.contentResolver, reader, db.audioProjectDao(), files)
        val results = listOf(async { importer.importAudio(id, request) }, async { secondImporter.importAudio(id, request) }).awaitAll()
        assertTrue(results.all { it is SoundlyResult.Success })
        val first = (results[0] as SoundlyResult.Success).value.project
        projects.add(first.id)
        val second = (results[1] as SoundlyResult.Success).value.project
        assertEquals(first.id, second.id)
        assertEquals(first.original.id, second.original.id)
        assertArrayEquals(source.readBytes(), File(first.original.filePath).readBytes())
        val recovered = importer.inspectImport(id) as SoundlyResult.Success
        assertEquals(first.id, (recovered.value as ImportOperationStatus.Completed).result.project.id)
        source.delete()
        assertTrue(File(first.original.filePath).isFile)
    }

    @Test fun interruptedCopyCleansOnlyItsReservedProjectAndNeverRetriesAutomatically() = runBlocking {
        val id = UUID.randomUUID().toString()
        val project = UUID.randomUUID().toString()
        val variant = UUID.randomUUID().toString()
        val dao = db.audioProjectDao()
        dao.reserveImport(ImportOperationEntity(id, project, variant, "COPYING"))
        val temporary = files.originalFile(project, com.ideacode.soundly_sdk.api.model.domain.AudioFormat.WAV)
        temporary.writeText("partial")
        val result = importer.inspectImport(id) as SoundlyResult.Success
        assertEquals(ImportOperationStatus.Interrupted, result.value)
        assertFalse(temporary.exists())
        assertTrue(source.isFile)
        assertTrue(importer.importAudio(id, AudioImportRequest(Uri.fromFile(source))) is SoundlyResult.Failure)
        assertEquals("INTERRUPTED", dao.getImport(id)?.phase)
    }

    @Test fun fileReadyButNotCommittedIsCleanedAfterReopeningImporter() = runBlocking {
        val id = UUID.randomUUID().toString()
        val project = UUID.randomUUID().toString()
        val dao = db.audioProjectDao()
        dao.reserveImport(ImportOperationEntity(id, project, UUID.randomUUID().toString(), "READY"))
        val file = files.originalFile(project, com.ideacode.soundly_sdk.api.model.domain.AudioFormat.WAV)
        file.writeBytes(source.readBytes())
        val reader = AndroidAudioMetadataReader(context)
        val repo = RoomAudioProjectRepository(dao, files)
        val restarted = RecoverableAudioImporter(DefaultAudioImporter(context.contentResolver, reader, repo, files), context.contentResolver, reader, dao, files)
        assertEquals(ImportOperationStatus.Interrupted, (restarted.inspectImport(id) as SoundlyResult.Success).value)
        assertFalse(file.exists())
        assertTrue(source.isFile)
    }

    @Test fun committedOperationWithMissingFileDoesNotPretendToBeInterrupted() = runBlocking {
        val id = UUID.randomUUID().toString()
        val imported = (importer.importAudio(id, AudioImportRequest(Uri.fromFile(source))) as SoundlyResult.Success).value
        projects.add(imported.project.id)
        File(imported.project.original.filePath).delete()
        assertTrue(importer.inspectImport(id) is SoundlyResult.Failure)
        assertEquals("COMMITTED", db.audioProjectDao().getImport(id)?.phase)
    }

    @Test fun databaseFailureNeverDeletesAnUnverifiedCopy() = runBlocking {
        val id = UUID.randomUUID().toString()
        val project = UUID.randomUUID().toString()
        projects.add(project)
        db.audioProjectDao().reserveImport(ImportOperationEntity(id, project, UUID.randomUUID().toString(), "READY"))
        val file = files.originalFile(project, com.ideacode.soundly_sdk.api.model.domain.AudioFormat.WAV)
        file.writeText("not yet verified")
        val failingDao = object : AudioProjectDao by db.audioProjectDao() {
            override suspend fun getImport(id: String): ImportOperationEntity? = throw android.database.sqlite.SQLiteException("injected")
        }
        val reader = AndroidAudioMetadataReader(context)
        val repo = RoomAudioProjectRepository(db.audioProjectDao(), files)
        val failing = RecoverableAudioImporter(DefaultAudioImporter(context.contentResolver, reader, repo, files), context.contentResolver, reader, failingDao, files)
        assertTrue(failing.inspectImport(id) is SoundlyResult.Failure)
        assertTrue("A database failure must not erase an unverified copy", file.isFile)
    }

    @Test fun revokedDocumentPermissionLeavesARecoverableInterruptedOperation() = runBlocking {
        val reader = object : AudioMetadataReader {
            override suspend fun read(source: Uri) = SoundlyResult.Failure(SoundlyError.PermissionDenied)
        }
        val repo = RoomAudioProjectRepository(db.audioProjectDao(), files)
        val legacy = DefaultAudioImporter(context.contentResolver, reader, repo, files)
        val denied = RecoverableAudioImporter(legacy, context.contentResolver, reader, db.audioProjectDao(), files)
        val id = UUID.randomUUID().toString()
        val result = denied.importAudio(id, AudioImportRequest(Uri.parse("content://unavailable/document")))
        assertEquals(SoundlyError.PermissionDenied, (result as SoundlyResult.Failure).error)
        assertEquals(ImportOperationStatus.Interrupted, (denied.inspectImport(id) as SoundlyResult.Success).value)
    }

    @Test fun insufficientSpaceIsReportedAndSourceRemainsUntouched() = runBlocking {
        val limited = object : AudioFileStore by files { override fun availableBytes() = 0L }
        val reader = AndroidAudioMetadataReader(context)
        val repo = RoomAudioProjectRepository(db.audioProjectDao(), files)
        val importer = RecoverableAudioImporter(DefaultAudioImporter(context.contentResolver, reader, repo, limited), context.contentResolver, reader, db.audioProjectDao(), limited)
        val result = importer.importAudio(UUID.randomUUID().toString(), AudioImportRequest(Uri.fromFile(source)))
        assertEquals(SoundlyError.InsufficientStorage, (result as SoundlyResult.Failure).error)
        assertTrue(source.isFile)
    }

    @Test fun providerDisplayNameSurvivesProjectRename() = runBlocking {
        val base = AndroidAudioMetadataReader(context)
        val reader = object : AudioMetadataReader {
            override suspend fun read(source: Uri) = when (val result = base.read(source)) {
                is SoundlyResult.Failure -> result
                is SoundlyResult.Success -> SoundlyResult.Success(result.value.copy(sourceDisplayName = "provider-original.wav"))
            }
        }
        val repo = RoomAudioProjectRepository(db.audioProjectDao(), files)
        val legacy = DefaultAudioImporter(context.contentResolver, reader, repo, files)
        val named = RecoverableAudioImporter(legacy, context.contentResolver, reader, db.audioProjectDao(), files)
        val result = (named.importAudio(UUID.randomUUID().toString(), AudioImportRequest(Uri.fromFile(source))) as SoundlyResult.Success).value
        projects.add(result.project.id)
        assertTrue(repo.renameProject(result.project.id, "Renamed") is SoundlyResult.Success)
        val after = (repo.getProject(result.project.id) as SoundlyResult.Success).value
        assertEquals("Renamed", after.title)
        assertEquals("provider-original.wav", after.sourceDisplayName)
    }

    @Test fun failedCommitRollsBackProjectAndKeepsExistingProjectUntouched() = runBlocking {
        val original = (importer.importAudio(UUID.randomUUID().toString(), AudioImportRequest(Uri.fromFile(source))) as SoundlyResult.Success).value.project
        projects.add(original.id)
        val id = UUID.randomUUID().toString()
        val newProjectId = UUID.randomUUID().toString()
        val dao = db.audioProjectDao()
        dao.reserveImport(ImportOperationEntity(id, newProjectId, original.original.id, "READY"))
        val partial = files.originalFile(newProjectId, com.ideacode.soundly_sdk.api.model.domain.AudioFormat.WAV)
        partial.writeBytes(source.readBytes())
        val duplicateVariant = original.original.copy(projectId = newProjectId, filePath = partial.absolutePath)
        val newProject = original.copy(id = newProjectId, original = duplicateVariant)
        assertTrue(runCatching { dao.commitImport(id, newProject.toEntity(), duplicateVariant.toEntity()) }.isFailure)
        assertNull(dao.getProject(newProjectId))
        assertEquals("READY", dao.getImport(id)?.phase)
        assertEquals(ImportOperationStatus.Interrupted, (importer.inspectImport(id) as SoundlyResult.Success).value)
        assertFalse(partial.exists())
        assertArrayEquals(source.readBytes(), File(original.original.filePath).readBytes())
    }
}
