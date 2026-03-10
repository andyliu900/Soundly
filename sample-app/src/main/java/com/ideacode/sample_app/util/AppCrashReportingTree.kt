package com.ideacode.sample_app.util

import android.util.Log
import com.ideacode.soundly_sdk.util.CrashReportingTree
import timber.log.Timber

class AppCrashReportingTree: CrashReportingTree() {

    companion object {
        private const val TAG = "CrashReportingTree"
    }

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