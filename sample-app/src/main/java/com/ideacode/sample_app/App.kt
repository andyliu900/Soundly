package com.ideacode.sample_app

import android.app.Application
import android.util.Log
import com.ideacode.sample_app.util.AppCrashReportingTree
import com.ideacode.sample_app.util.AppModuleTagTree
import com.ideacode.soundly_sdk.BuildConfig
import com.ideacode.soundly_sdk.Soundly
import com.ideacode.soundly_sdk.Soundly.TAG
import timber.log.Timber

class App : Application() {

    override fun onCreate() {
        super.onCreate()

        Soundly.init(this)

        initLogUtils()
    }

    fun initLogUtils() {
        Log.i(TAG,  "App LogUtils init, BuildConfig.DEBUG: ${com.ideacode.soundly_sdk.BuildConfig.DEBUG}")

        if (BuildConfig.DEBUG) {
            Timber.plant(AppModuleTagTree())
        } else {
            Timber.plant(AppCrashReportingTree())
        }
    }

}