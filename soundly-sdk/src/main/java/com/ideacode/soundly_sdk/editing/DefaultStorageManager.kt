package com.ideacode.soundly_sdk.editing

import com.ideacode.soundly_sdk.api.capability.StorageManager
import com.ideacode.soundly_sdk.api.capability.StorageSnapshot
import com.ideacode.soundly_sdk.api.error.SoundlyError
import com.ideacode.soundly_sdk.api.result.SoundlyResult
import com.ideacode.soundly_sdk.project.data.dao.AudioProjectDao
import com.ideacode.soundly_sdk.history.data.dao.AudioHistoryDao
import com.ideacode.soundly_sdk.api.model.settings.SoundlySettingsRepository
import com.ideacode.soundly_sdk.storage.AudioFileStore
import com.ideacode.soundly_sdk.internal.SoundlyDispatchers
import kotlinx.coroutines.withContext

internal class DefaultStorageManager(
    private val fileStore: AudioFileStore,
    private val projectDao: AudioProjectDao,
    private val historyDao: AudioHistoryDao,
    private val settings: SoundlySettingsRepository,
    private val canDeleteAllData: () -> Boolean = { true },
    private val beforeDeleteAllData: suspend () -> Unit = {},
    private val dispatchers: SoundlyDispatchers = SoundlyDispatchers.Default
) : StorageManager {

    override suspend fun snapshot(): SoundlyResult<StorageSnapshot> = withContext(dispatchers.io) {
        try {
            val usage = fileStore.storageUsage()
            SoundlyResult.Success(
                StorageSnapshot(
                    originalsBytes = usage.originalsBytes,
                    variantsBytes = usage.variantsBytes,
                    cacheBytes = usage.cacheBytes,
                    availableBytes = fileStore.availableBytes(),
                    recoveryBytes = usage.recoveryBytes,
                    auxiliaryBytes = usage.auxiliaryBytes
                )
            )
        } catch (error: Exception) {
            SoundlyResult.Failure(SoundlyError.FileOperationFailed(error))
        }
    }

    override suspend fun clearCache(): SoundlyResult<Unit> = withContext(dispatchers.io) {
        try {
            fileStore.clearTemporaryFiles()
            SoundlyResult.Success(Unit)
        } catch (error: Exception) {
            SoundlyResult.Failure(SoundlyError.FileOperationFailed(error))
        }
    }

    override suspend fun deleteAllLocalData(): SoundlyResult<Unit> = withContext(dispatchers.io) {
        if (!canDeleteAllData()) {
            return@withContext SoundlyResult.Failure(
                SoundlyError.InvalidState("deleteAllLocalData")
            )
        }
        try {
            beforeDeleteAllData()
        } catch (error: Exception) {
            return@withContext SoundlyResult.Failure(SoundlyError.Unknown(error))
        }
        try {
            projectDao.clearAllProjectData()
            historyDao.clearAllHistory()
        } catch (error: Exception) {
            return@withContext SoundlyResult.Failure(SoundlyError.DatabaseFailed(error))
        }
        try {
            fileStore.clearAllLocalFiles()
        } catch (error: Exception) {
            return@withContext SoundlyResult.Failure(
                SoundlyError.FileOperationFailed(error)
            )
        }
        when (val reset = settings.reset()) {
            is SoundlyResult.Success -> SoundlyResult.Success(Unit)
            is SoundlyResult.Failure -> reset
        }
    }
}
