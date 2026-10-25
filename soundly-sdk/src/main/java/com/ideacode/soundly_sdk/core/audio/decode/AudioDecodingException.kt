package com.ideacode.soundly_sdk.core.audio.decode

/** Internal boundary exception used to preserve decode failure semantics. */
internal class AudioDecodingException(
    override val cause: Throwable
) : Exception(cause)
