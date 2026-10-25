package com.ideacode.soundly_sdk.api

import android.content.Context
import com.ideacode.soundly_sdk.BuildConfig
import com.ideacode.soundly_sdk.api.error.SoundlyError
import com.ideacode.soundly_sdk.api.exception.SoundlySdkException
import com.ideacode.soundly_sdk.api.result.SoundlyResult
import com.ideacode.soundly_sdk.util.CrashReportingTree
import com.ideacode.soundly_sdk.util.ModuleTagTree
import timber.log.Timber

/**
 * SDK 统一入口
 */
object Soundly {

    private const val TAG = "Soundly"

    private val initializationLock = Any()

    @Volatile
    private var sdkInstance: SoundlySdk? = null

    /**
     * 初始化 SDK
     */
    @Deprecated(
        message = "Use initialize(context) and handle SoundlyResult",
        replaceWith = ReplaceWith("Soundly.initialize(context)")
    )
    fun init(context: Context) {
        when (val result = initialize(context)) {
            is SoundlyResult.Success -> Unit
            is SoundlyResult.Failure -> throw SoundlySdkException(result.error)
        }
    }

    /**
     * Idempotently initializes the SDK with an application context.
     */
    fun initialize(
        context: Context,
        config: SoundlyConfig = SoundlyConfig()
    ): SoundlyResult<Unit> = synchronized(initializationLock) {
        if (sdkInstance != null) {
            return@synchronized SoundlyResult.Success(Unit)
        }

        try {
            sdkInstance = createSoundlySdk(context.applicationContext)

            if (config.enableDebugLogging) {
                initLogUtils()
            }

            SoundlyResult.Success(Unit)
        } catch (error: Exception) {
            sdkInstance = null
            SoundlyResult.Failure(SoundlyError.InitializationFailed(error))
        }
    }

    fun isInitialized(): Boolean = sdkInstance != null

    /** Returns a typed failure instead of throwing when initialization is missing. */
    fun sdkResult(): SoundlyResult<SoundlySdk> = sdkInstance
        ?.let { SoundlyResult.Success(it) }
        ?: SoundlyResult.Failure(SoundlyError.NotInitialized)

    /**
     * Convenience accessor for code paths that are guaranteed to run after
     * [initialize]. Prefer [sdkResult] when initialization is not guaranteed.
     */
    fun sdk(): SoundlySdk = sdkInstance
        ?: throw SoundlySdkException(SoundlyError.NotInitialized)

    /**
     * 日志工具配置
     */
    private fun initLogUtils() {
        if (BuildConfig.DEBUG) {
            if (Timber.forest().none { it is ModuleTagTree }) {
                Timber.Forest.plant(ModuleTagTree())
            }
        } else {
            if (Timber.forest().none { it is CrashReportingTree }) {
                Timber.Forest.plant(CrashReportingTree())
            }
        }
    }

}
