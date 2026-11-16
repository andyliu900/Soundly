package com.ideacode.soundly_sdk.importer

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ideacode.soundly_sdk.api.model.domain.AudioFormat
import com.ideacode.soundly_sdk.api.model.importer.ImportOperationStatus
import com.ideacode.soundly_sdk.api.model.importer.AudioImportRequest
import com.ideacode.soundly_sdk.api.result.SoundlyResult
import com.ideacode.soundly_sdk.history.data.db.AppDatabase
import com.ideacode.soundly_sdk.project.data.entity.ImportOperationEntity
import com.ideacode.soundly_sdk.project.repository.RoomAudioProjectRepository
import com.ideacode.soundly_sdk.storage.DefaultAudioFileStore
import kotlinx.coroutines.runBlocking
import android.net.Uri
import com.ideacode.soundly_sdk.core.util.WavWriter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import com.ideacode.soundly_sdk.storage.AudioFileStore
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import org.junit.Assume.assumeTrue
import androidx.test.platform.app.InstrumentationRegistry

/** Run the two methods in separate instrumentation invocations with a force-stop between them. */
@RunWith(AndroidJUnit4::class)
class ImportRestartInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val operation = "afc67a39-2519-49ef-9657-6eadb644facc"
    private val project = "i1_process_fixture"

    /** Start alone, wait for i6-copy-active, then force-stop this test package. */
    @Test fun beginRealCopyUntilForceStopped() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("i6_force_stop") == "true")
        context.deleteDatabase("i6-copy-test.db")
        val db = Room.databaseBuilder(context, AppDatabase::class.java, "i6-copy-test.db").build()
        val files = DefaultAudioFileStore(context.filesDir, context.cacheDir)
        val marker = File(requireNotNull(context.getExternalFilesDir(null)), "i6-copy-active")
        marker.delete()
        val slow = object : AudioFileStore by files {
            override fun copyAtomically(input: InputStream, destination: File): File {
                val wrapped = object : FilterInputStream(input) {
                    var reads = 0
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        if (++reads == 2) { marker.writeText("copying"); Thread.sleep(300_000) }
                        return super.read(buffer, offset, length)
                    }
                }
                return files.copyAtomically(wrapped, destination)
            }
        }
        val source = WavWriter(context.cacheDir).write(FloatArray(44100 * 3) { .1f }, 44100, 1, "i6-copy-source")
        val reader = AndroidAudioMetadataReader(context)
        val repo = RoomAudioProjectRepository(db.audioProjectDao(), files)
        val importer = RecoverableAudioImporter(DefaultAudioImporter(context.contentResolver, reader, repo, slow), context.contentResolver, reader, db.audioProjectDao(), slow)
        importer.importAudio(operation, AudioImportRequest(Uri.fromFile(source)))
        fail("Test must be force-stopped during the blocked copy")
    }

    @Test fun recoverAfterRealCopyForceStop() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("i6_force_stop") == "true")
        val db = Room.databaseBuilder(context, AppDatabase::class.java, "i6-copy-test.db").build()
        val files = DefaultAudioFileStore(context.filesDir, context.cacheDir)
        val marker = File(requireNotNull(context.getExternalFilesDir(null)), "i6-copy-active")
        try {
            assertTrue(marker.isFile)
            val row = requireNotNull(db.audioProjectDao().getImport(operation))
            assertEquals("COPYING", row.phase)
            val directory = File(files.rootDirectory, "projects/${row.projectId}")
            assertTrue(directory.exists())
            val reader = AndroidAudioMetadataReader(context)
            val repo = RoomAudioProjectRepository(db.audioProjectDao(), files)
            val importer = RecoverableAudioImporter(DefaultAudioImporter(context.contentResolver, reader, repo, files), context.contentResolver, reader, db.audioProjectDao(), files)
            assertEquals(ImportOperationStatus.Interrupted, (importer.inspectImport(operation) as SoundlyResult.Success).value)
            assertFalse(directory.exists())
            assertNull(db.audioProjectDao().getProject(row.projectId))
            assertEquals("INTERRUPTED", db.audioProjectDao().getImport(operation)?.phase)
        } finally {
            marker.delete(); File(context.cacheDir, "i6-copy-source.wav").delete(); db.close(); context.deleteDatabase("i6-copy-test.db")
        }
    }

    @Test fun prepareInterruptedFileReadyState() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("i6_restart_fixture") == "true")
        context.deleteDatabase("i1-process-test.db")
        val db = Room.databaseBuilder(context, AppDatabase::class.java, "i1-process-test.db").build()
        val files = DefaultAudioFileStore(context.filesDir, context.cacheDir)
        files.deleteProject(project)
        try {
            db.audioProjectDao().reserveImport(ImportOperationEntity(operation, project, "i1_process_variant", "READY"))
            files.originalFile(project, AudioFormat.WAV).writeText("interrupted temporary file")
        } finally { db.close() }
    }

    @Test fun recoverInterruptedFileReadyState() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("i6_restart_fixture") == "true")
        val db = Room.databaseBuilder(context, AppDatabase::class.java, "i1-process-test.db").build()
        val files = DefaultAudioFileStore(context.filesDir, context.cacheDir)
        try {
            val reader = AndroidAudioMetadataReader(context)
            val repo = RoomAudioProjectRepository(db.audioProjectDao(), files)
            val importer = RecoverableAudioImporter(DefaultAudioImporter(context.contentResolver, reader, repo, files), context.contentResolver, reader, db.audioProjectDao(), files)
            val status = importer.inspectImport(operation) as SoundlyResult.Success
            assertEquals(ImportOperationStatus.Interrupted, status.value)
            assertEquals("INTERRUPTED", db.audioProjectDao().getImport(operation)?.phase)
            assertFalse(context.filesDir.resolve("soundly/projects/$project").exists())
        } finally { files.deleteProject(project); db.close(); context.deleteDatabase("i1-process-test.db") }
    }

    @Test fun prepareCommittedState() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("i6_restart_fixture") == "true")
        context.deleteDatabase("i1-committed-test.db")
        val db = Room.databaseBuilder(context, AppDatabase::class.java, "i1-committed-test.db").build()
        val files = DefaultAudioFileStore(context.filesDir, context.cacheDir)
        val source = WavWriter(context.cacheDir).write(FloatArray(44100) { .1f }, 44100, 1, "i1-committed-source")
        try {
            val reader = AndroidAudioMetadataReader(context)
            val repo = RoomAudioProjectRepository(db.audioProjectDao(), files)
            val importer = RecoverableAudioImporter(DefaultAudioImporter(context.contentResolver, reader, repo, files), context.contentResolver, reader, db.audioProjectDao(), files)
            assertTrue(importer.importAudio(operation, AudioImportRequest(Uri.fromFile(source))) is SoundlyResult.Success)
        } finally { source.delete(); db.close() }
    }

    @Test fun recoverCommittedState() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("i6_restart_fixture") == "true")
        val db = Room.databaseBuilder(context, AppDatabase::class.java, "i1-committed-test.db").build()
        val files = DefaultAudioFileStore(context.filesDir, context.cacheDir)
        try {
            val reader = AndroidAudioMetadataReader(context)
            val repo = RoomAudioProjectRepository(db.audioProjectDao(), files)
            val importer = RecoverableAudioImporter(DefaultAudioImporter(context.contentResolver, reader, repo, files), context.contentResolver, reader, db.audioProjectDao(), files)
            val status = (importer.inspectImport(operation) as SoundlyResult.Success).value as ImportOperationStatus.Completed
            assertTrue(java.io.File(status.result.project.original.filePath).isFile)
            assertTrue(repo.deleteProject(status.result.project.id) is SoundlyResult.Success)
        } finally { db.close(); context.deleteDatabase("i1-committed-test.db") }
    }
}
