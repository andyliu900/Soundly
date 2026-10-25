package com.ideacode.soundly_sdk.api.exception

import com.ideacode.soundly_sdk.api.error.SoundlyError

class SoundlySdkException(
    val error: SoundlyError
) : IllegalStateException(error.code)
