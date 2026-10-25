package com.ideacode.soundly_sdk.internal

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * SDK-owned coroutine dispatchers.
 *
 * Keeping dispatcher selection behind one dependency makes threading policy
 * consistent and allows deterministic replacements in SDK tests.
 */
internal data class SoundlyDispatchers(
    val io: CoroutineDispatcher,
    val computation: CoroutineDispatcher
) {
    companion object {
        val Default = SoundlyDispatchers(
            io = Dispatchers.IO,
            computation = Dispatchers.Default
        )
    }
}
