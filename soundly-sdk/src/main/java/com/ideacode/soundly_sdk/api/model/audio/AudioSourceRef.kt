package com.ideacode.soundly_sdk.api.model.audio

import android.net.Uri
import java.io.File

/** Android audio source accepted by public processing APIs. */
sealed interface AudioSourceRef {
    data class ContentUri(val uri: Uri) : AudioSourceRef
    data class LocalFile(val file: File) : AudioSourceRef
}
