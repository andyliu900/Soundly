package com.ideacode.soundly_sdk.util

import timber.log.Timber

open class ModuleTagTree: Timber.DebugTree() {

    override fun createStackElementTag(element: StackTraceElement): String? {
        val className = element.className

        return when  {

            /*  soundly_core  */
            className.contains("soundly_core.ai") -> "SOUNDLY_CORE.AI"
            className.contains("soundly_core.audio") -> "SOUNDLY_CORE.AUDIO"
            className.contains("soundly_core.history") -> "SOUNDLY_CORE.HISTORY"
            className.contains("soundly_core.media") -> "SOUNDLY_CORE.MEDIA"
            className.contains("soundly_core.ui") -> "SOUNDLY_CORE.UI"
            className.contains("soundly_core.util") -> "SOUNDLY_CORE.UTIL"

            /*  soundly_sdk  */
            className.contains("soundly_sdk.cloud") -> "SOUNDLY_SDK.CLOUD"
            className.contains("soundly_sdk.container") -> "SOUNDLY_SDK.CONTAINER"
            className.contains("soundly_sdk.history") -> "SOUNDLY_SDK.HISTORY"
            className.contains("soundly_sdk.player") -> "SOUNDLY_SDK.PLAYER"
            className.contains("soundly_sdk.util") -> "SOUNDLY_SDK.UTIL"

            else -> return super.createStackElementTag(element)
        }
    }

}