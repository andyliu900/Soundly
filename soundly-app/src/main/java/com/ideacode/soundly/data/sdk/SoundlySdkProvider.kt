package com.ideacode.soundly.data.sdk

import android.content.Context
import com.ideacode.soundly.common.error.AppError
import com.ideacode.soundly.common.error.AppResult
import com.ideacode.soundly_sdk.api.Soundly
import com.ideacode.soundly_sdk.api.SoundlyConfig
import com.ideacode.soundly_sdk.api.SoundlySdk
import com.ideacode.soundly_sdk.api.model.audio.AudioSourceRef
import com.ideacode.soundly_sdk.api.model.domain.AudioVariant
import com.ideacode.soundly_sdk.api.error.SoundlyError
import com.ideacode.soundly_sdk.api.result.SoundlyResult

/** The only M1 boundary allowed to access Soundly's static entry point. */
interface SoundlySdkProvider {
    fun initialize(): AppResult<Unit>
    fun get(): AppResult<SoundlySdk>
}

class DefaultSoundlySdkProvider(
    private val appContext: Context
) : SoundlySdkProvider {
    override fun initialize(): AppResult<Unit> = Soundly.initialize(
        context = appContext,
        config = SoundlyConfig(enableDebugLogging = false)
    ).toAppResult()

    override fun get(): AppResult<SoundlySdk> = Soundly.sdkResult().toAppResult()
}

internal fun <T> SoundlyResult<T>.toAppResult(): AppResult<T> = when (this) {
    is SoundlyResult.Success -> AppResult.Success(value)
    is SoundlyResult.Failure -> AppResult.Failure(error.toAppError())
}

private fun SoundlyError.toAppError(): AppError = AppError(
    code = code,
    retryable = retryable
)

internal fun SoundlySdkProvider.requireSdk(): SoundlySdk = when (val result = get()) {
    is AppResult.Success -> result.value
    is AppResult.Failure -> error("Soundly SDK unavailable: ${result.error.code}")
}

internal fun AudioVariant.toSourceRef(): AudioSourceRef =
    AudioSourceRef.LocalFile(java.io.File(filePath))

internal inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Success -> AppResult.Success(transform(value))
    is AppResult.Failure -> this
}
