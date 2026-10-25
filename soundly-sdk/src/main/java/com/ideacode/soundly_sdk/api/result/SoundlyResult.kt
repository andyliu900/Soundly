package com.ideacode.soundly_sdk.api.result

import com.ideacode.soundly_sdk.api.error.SoundlyError

/**
 * Stable result type exposed by Soundly public APIs.
 *
 * UI text must not be stored here. Applications should map [SoundlyError] to
 * localized resources at the presentation layer.
 */
sealed interface SoundlyResult<out T> {

    data class Success<T>(
        val value: T
    ) : SoundlyResult<T>

    data class Failure(
        val error: SoundlyError
    ) : SoundlyResult<Nothing>
}

inline fun <T, R> SoundlyResult<T>.fold(
    onSuccess: (T) -> R,
    onFailure: (SoundlyError) -> R
): R = when (this) {
    is SoundlyResult.Success -> onSuccess(value)
    is SoundlyResult.Failure -> onFailure(error)
}

fun <T> SoundlyResult<T>.getOrNull(): T? =
    (this as? SoundlyResult.Success)?.value

