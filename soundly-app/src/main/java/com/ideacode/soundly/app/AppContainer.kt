package com.ideacode.soundly.app

import android.content.Context
import com.ideacode.soundly.common.error.AppErrorMessageMapper
import com.ideacode.soundly.data.sdk.DefaultSoundlySdkProvider
import com.ideacode.soundly.data.sdk.SoundlySdkProvider

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

    fun initializeSdk() = soundlySdkProvider.initialize()
}
