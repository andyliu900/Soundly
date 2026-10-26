package com.ideacode.soundly.data.sdk

import com.ideacode.soundly.common.error.AppResult
import com.ideacode.soundly_sdk.api.capability.StorageSnapshot
import com.ideacode.soundly_sdk.api.model.settings.SoundlySettings
import kotlinx.coroutines.flow.Flow

/** SDK boundary for persisted audio settings and storage/privacy operations. */
interface SettingsStorageGateway {
    fun observeSettings(): Flow<SoundlySettings>
    suspend fun updateSettings(settings: SoundlySettings): AppResult<Unit>
    suspend fun storageSnapshot(): AppResult<StorageSnapshot>
    suspend fun clearCache(): AppResult<Unit>
    suspend fun deleteAllLocalData(): AppResult<Unit>
}

class DefaultSettingsStorageGateway(
    private val provider: SoundlySdkProvider
) : SettingsStorageGateway {
    private val sdk get() = provider.requireSdk()

    override fun observeSettings(): Flow<SoundlySettings> = sdk.settings.observe()
    override suspend fun updateSettings(settings: SoundlySettings): AppResult<Unit> =
        sdk.settings.update(settings).toAppResult()
    override suspend fun storageSnapshot(): AppResult<StorageSnapshot> = sdk.storage.snapshot().toAppResult()
    override suspend fun clearCache(): AppResult<Unit> = sdk.storage.clearCache().toAppResult()
    override suspend fun deleteAllLocalData(): AppResult<Unit> = sdk.storage.deleteAllLocalData().toAppResult()
}
