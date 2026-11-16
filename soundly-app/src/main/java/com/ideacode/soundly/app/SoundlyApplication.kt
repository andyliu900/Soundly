package com.ideacode.soundly.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.ideacode.soundly.feature.recording.BackgroundRecordingGuard

class SoundlyApplication : Application() {
    lateinit var appContainer: AppContainer
        private set
    internal lateinit var backgroundRecordingGuard: BackgroundRecordingGuard
        private set

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer(this)
        backgroundRecordingGuard = BackgroundRecordingGuard(appContainer.recordingGateway)
        registerActivityLifecycleCallbacks(backgroundRecordingGuard)
        AppCompatDelegate.setDefaultNightMode(appContainer.appPreferences.nightMode)
        appContainer.initializeSdk()
    }
}
