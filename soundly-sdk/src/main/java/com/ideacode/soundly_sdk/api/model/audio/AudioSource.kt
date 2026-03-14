package com.ideacode.soundly_sdk.api.model.audio

import android.net.Uri
import java.io.File

open class AudioSource {

    data class Original(
        val uri: Uri
    ): AudioSource()

    data class Processed(
        val wavFile: File
    ): AudioSource()

}
