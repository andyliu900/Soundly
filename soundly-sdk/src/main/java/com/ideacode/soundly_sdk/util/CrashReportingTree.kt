package com.ideacode.soundly_sdk.util

import android.annotation.SuppressLint
import android.util.Log
import timber.log.Timber

internal open class CrashReportingTree : Timber.Tree() {

    companion object {
        private const val TAG = "CrashReportingTree"
    }

    @SuppressLint("LogNotTimber")
    override fun log(
        priority: Int,
        tag: String?,
        message: String,
        t: Throwable?
    ) {
        if (priority == Log.ERROR && t != null) {
            Log.e(TAG, "crash message: ${t.message}")
            t.printStackTrace()
        }
    }
}
