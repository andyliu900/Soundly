package com.ideacode.soundly.app

import android.app.Application

class SoundlyApplication : Application() {
    lateinit var appContainer: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer(this)
        appContainer.initializeSdk()
    }
}
