package com.ideacode.soundly.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class SoundlyApplication : Application() {
    lateinit var appContainer: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer(this)
        AppCompatDelegate.setDefaultNightMode(appContainer.appPreferences.nightMode)
        appContainer.initializeSdk()
    }
}
