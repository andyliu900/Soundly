package com.ideacode.soundly.common.error

/** App-owned error contract. It contains no platform exception text. */
data class AppError(
    val code: String,
    val retryable: Boolean
)

sealed interface AppResult<out T> {
    data class Success<T>(val value: T) : AppResult<T>
    data class Failure(val error: AppError) : AppResult<Nothing>
}
