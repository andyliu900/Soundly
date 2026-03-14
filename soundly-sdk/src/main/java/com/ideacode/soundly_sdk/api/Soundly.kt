package com.ideacode.soundly_sdk.api

import android.content.Context
import android.util.Log
import com.ideacode.soundly_sdk.BuildConfig
import com.ideacode.soundly_sdk.api.SdkContainer
import com.ideacode.soundly_sdk.util.CrashReportingTree
import com.ideacode.soundly_sdk.util.ModuleTagTree
import timber.log.Timber

/**
 * SDK 统一入口
 */
object Soundly {

    const val TAG = "Soundly"

    private lateinit var container: SdkContainer


    /**
     * 初始化 SDK
     */
    fun init(context: Context) {
        Log.i(TAG,  "Soundly init")

        container = SdkContainer()
        container.init(context)

//        initLogUtils()
    }

    /**
     * 获取容器
     */
    fun container(): SdkContainer = container

    /**
     * 日志工具配置
     */
    private fun initLogUtils() {
        Log.i(TAG,  "LogUtils init, BuildConfig.DEBUG: ${BuildConfig.DEBUG}")

        if (BuildConfig.DEBUG) {
            Timber.Forest.plant(ModuleTagTree())
        } else {
            Timber.Forest.plant(CrashReportingTree())
        }
    }

}