package com.ideacode.soundly_sdk.core.audio.io

import android.net.Uri
import com.ideacode.soundly_sdk.core.audio.model.PcmData

internal interface AudioLoader {

    fun load(uri: Uri): PcmData

}
