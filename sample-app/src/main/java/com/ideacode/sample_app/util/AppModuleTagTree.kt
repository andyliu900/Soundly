package com.ideacode.sample_app.util

import com.ideacode.soundly_sdk.util.ModuleTagTree
import timber.log.Timber

class AppModuleTagTree: ModuleTagTree() {

    override fun createStackElementTag(element: StackTraceElement): String? {
        super.createStackElementTag(element)

        val className = element.className

        return when  {

            /*  sample_app  */
            className.contains("sample_app.home") -> "SAMPLE_APP.HOME"
            className.contains("sample_app.history") -> "SAMPLE_APP.HISTORY"

            else -> return super.createStackElementTag(element)
        }
    }

}