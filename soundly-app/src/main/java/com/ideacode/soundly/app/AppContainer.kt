package com.ideacode.soundly.app

import android.content.Context
import com.ideacode.soundly.common.error.AppErrorMessageMapper
import com.ideacode.soundly.data.sdk.DefaultSoundlySdkProvider
import com.ideacode.soundly.data.sdk.DefaultProjectGateway
import com.ideacode.soundly.data.sdk.DefaultRecordingGateway
import com.ideacode.soundly.data.sdk.DefaultEditingGateway
import com.ideacode.soundly.data.sdk.EditingGateway
import com.ideacode.soundly.data.sdk.ProjectGateway
import com.ideacode.soundly.data.sdk.RecordingGateway
import com.ideacode.soundly.data.sdk.SoundlySdkProvider
import com.ideacode.soundly.data.sdk.DefaultSettingsStorageGateway
import com.ideacode.soundly.data.sdk.SettingsStorageGateway

/**
 * Composition root for app-owned dependencies.
 *
 * M1 deliberately uses explicit construction instead of a DI framework. New
 * feature gateways and use cases are added here as they become real clients of
 * the Soundly SDK, keeping SDK static access out of feature code.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val soundlySdkProvider: SoundlySdkProvider = DefaultSoundlySdkProvider(appContext)
    val errorMessageMapper = AppErrorMessageMapper()
    val appPreferences = AppPreferences(appContext)
    val recordingGateway: RecordingGateway = DefaultRecordingGateway(soundlySdkProvider)
    val projectGateway: ProjectGateway = DefaultProjectGateway(soundlySdkProvider)
    val editingGateway: EditingGateway = DefaultEditingGateway(soundlySdkProvider)
    val settingsStorageGateway: SettingsStorageGateway = DefaultSettingsStorageGateway(soundlySdkProvider)

    fun initializeSdk() = soundlySdkProvider.initialize()
}
