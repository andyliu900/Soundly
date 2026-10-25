package com.ideacode.soundly_sdk.editing

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ideacode.soundly_sdk.api.model.audio.dsp.PresetOption
import com.ideacode.soundly_sdk.api.model.domain.AudioFormat
import com.ideacode.soundly_sdk.api.model.domain.AudioProject
import com.ideacode.soundly_sdk.api.model.domain.AudioSourceType
import com.ideacode.soundly_sdk.api.model.domain.AudioVariant
import com.ideacode.soundly_sdk.api.model.domain.RecordingScene
import com.ideacode.soundly_sdk.api.model.domain.VariantType
import com.ideacode.soundly_sdk.api.model.settings.SoundlySettings
import com.ideacode.soundly_sdk.api.result.SoundlyResult
import com.ideacode.soundly_sdk.history.data.db.AppDatabase
import com.ideacode.soundly_sdk.history.data.entity.AudioHistoryEntity
import com.ideacode.soundly_sdk.project.data.entity.RecoverySessionEntity
import com.ideacode.soundly_sdk.project.repository.RoomAudioProjectRepository
import com.ideacode.soundly_sdk.settings.SharedPreferencesSoundlySettingsRepository
import com.ideacode.soundly_sdk.storage.DefaultAudioFileStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StorageManagerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences = context.getSharedPreferences(
        "soundly-storage-manager-test",
        Context.MODE_PRIVATE
    )
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        context.filesDir.resolve("soundly").deleteRecursively()
        preferences.edit().clear().commit()
    }

    @After
    fun tearDown() {
        database.close()
        context.filesDir.resolve("soundly").deleteRecursively()
        preferences.edit().clear().commit()
    }

    @Test
    fun deleteAllLocalDataClearsProjectsRecoveryHistoryFilesAndSettings() = runBlocking {
        val store = DefaultAudioFileStore(context.filesDir, context.cacheDir)
        val repository = RoomAudioProjectRepository(database.audioProjectDao(), store)
        val settings = SharedPreferencesSoundlySettingsRepository(context, preferences)
        val originalFile = store.originalFile("delete_project", AudioFormat.M4A)
            .apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val original = AudioVariant(
            id = "delete_original",
            projectId = "delete_project",
            filePath = originalFile.absolutePath,
            presetOption = PresetOption.RAW,
            isOriginal = true,
            sampleRate = 44_100,
            channels = 1,
            durationMs = 1_000L,
            format = AudioFormat.M4A,
            fileSizeBytes = originalFile.length(),
            createdAt = 1L,
            variantType = VariantType.ORIGINAL
        )
        repository.saveProject(
            AudioProject(
                id = "delete_project",
                title = "Delete me",
                sourceType = AudioSourceType.RECORDED,
                scene = RecordingScene.AUTO,
                original = original,
                createdAt = 1L,
                updatedAt = 1L
            )
        )
        database.audioProjectDao().upsertRecoverySession(
            RecoverySessionEntity(
                id = "delete_session",
                projectId = "recovery_project",
                tempFilePath = store.recoveryFile("delete_session", AudioFormat.M4A)
                    .apply { writeBytes(byteArrayOf(4)) }.absolutePath,
                recordedDurationMs = 100L,
                recordedBytes = 1L,
                status = "RECOVERY_REQUIRED",
                updatedAt = 1L
            )
        )
        database.audioHistoryDao().insertHistory(
            AudioHistoryEntity(
                id = "legacy_history",
                originalPath = "/external/user-owned.m4a",
                sampleRate = 44_100,
                channels = 1,
                durationMs = 1_000L,
                createAt = 1L
            )
        )
        val abandonedEnhancement = store.createTempFile(".wav")
            .apply { writeBytes(byteArrayOf(5, 6)) }
        val sharedCopy = store.shareCacheFile("delete_share", "delete.wav")
            .apply { writeBytes(byteArrayOf(7, 8)) }
        settings.update(SoundlySettings(defaultScene = RecordingScene.PODCAST))
        val manager = DefaultStorageManager(
            fileStore = store,
            projectDao = database.audioProjectDao(),
            historyDao = database.audioHistoryDao(),
            settings = settings
        )

        val result = manager.deleteAllLocalData()

        assertTrue(result is SoundlyResult.Success)
        assertTrue(repository.observeProjects().first().isEmpty())
        assertTrue(database.audioProjectDao().getRecoverySessions().isEmpty())
        assertTrue(database.audioHistoryDao().getAllHistory().isEmpty())
        assertFalse(originalFile.exists())
        assertFalse(abandonedEnhancement.exists())
        assertFalse(sharedCopy.exists())
        assertEquals(SoundlySettings(), (settings.get() as SoundlyResult.Success).value)
    }

    @Test
    fun snapshotClassifiesUsageAndClearCachePreservesProjectFiles() = runBlocking {
        val store = DefaultAudioFileStore(
            context.filesDir,
            context.cacheDir,
            availableBytesProvider = { 987_654L }
        )
        val original = store.originalFile("usage_project", AudioFormat.WAV)
            .apply { writeBytes(ByteArray(11)) }
        store.variantFile("usage_project", "usage_variant", AudioFormat.WAV)
            .writeBytes(ByteArray(7))
        store.waveformFile("usage_project", "usage_variant")
            .writeBytes(ByteArray(2))
        store.projectMetadataFile("usage_project")
            .writeBytes(ByteArray(4))
        store.recoveryFile("usage_session", AudioFormat.M4A)
            .writeBytes(ByteArray(3))
        val cached = store.shareCacheFile("usage_share", "usage.wav")
            .apply { writeBytes(ByteArray(5)) }
        val exportCached = store.exportCacheFile("processed.wav")
            .apply { writeBytes(ByteArray(4)) }
        val streamingTemp = store.createTempFile(".wav")
            .apply { writeBytes(ByteArray(6)) }
        val manager = DefaultStorageManager(
            fileStore = store,
            projectDao = database.audioProjectDao(),
            historyDao = database.audioHistoryDao(),
            settings = SharedPreferencesSoundlySettingsRepository(context, preferences)
        )

        val result = manager.snapshot()

        assertTrue(result is SoundlyResult.Success)
        val snapshot = (result as SoundlyResult.Success).value
        assertEquals(11L, snapshot.originalsBytes)
        assertEquals(7L, snapshot.variantsBytes)
        assertEquals(15L, snapshot.cacheBytes)
        assertEquals(3L, snapshot.recoveryBytes)
        assertEquals(6L, snapshot.auxiliaryBytes)
        assertEquals(987_654L, snapshot.availableBytes)

        assertTrue(manager.clearCache() is SoundlyResult.Success)
        assertFalse(cached.exists())
        assertFalse(exportCached.exists())
        assertFalse(streamingTemp.exists())
        assertTrue(original.exists())
        assertEquals(0L, (manager.snapshot() as SoundlyResult.Success).value.cacheBytes)
    }
}
