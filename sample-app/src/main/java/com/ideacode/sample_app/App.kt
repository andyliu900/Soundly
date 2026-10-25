package com.ideacode.sample_app

import android.app.Application
import android.util.Log
import com.ideacode.sample_app.util.AppCrashReportingTree
import com.ideacode.sample_app.util.AppModuleTagTree
import com.ideacode.soundly_sdk.api.Soundly
import com.ideacode.soundly_sdk.api.SoundlyConfig
import com.ideacode.soundly_sdk.api.result.SoundlyResult
import timber.log.Timber

class App : Application() {

    private companion object {
        const val TAG = "SampleApp"
    }

    override fun onCreate() {
        super.onCreate()

        when (
            val result = Soundly.initialize(
                context = this,
                config = SoundlyConfig(enableDebugLogging = false)
            )
        ) {
            is SoundlyResult.Success -> Unit
            is SoundlyResult.Failure -> {
                throw IllegalStateException(
                    "Soundly initialization failed: ${result.error.code}"
                )
            }
        }

        initLogUtils()
    }

    fun initLogUtils() {
        Log.i(TAG,  "App LogUtils init, BuildConfig.DEBUG: ${BuildConfig.DEBUG}")

        if (BuildConfig.DEBUG) {
            Timber.plant(AppModuleTagTree())
        } else {
            Timber.plant(AppCrashReportingTree())
        }
    }

}
