package com.ideacode.soundly_core.audio.io

import android.net.Uri
import com.ideacode.soundly_core.audio.model.PcmData

interface AudioLoader {

    fun load(uri: Uri): PcmData

}
