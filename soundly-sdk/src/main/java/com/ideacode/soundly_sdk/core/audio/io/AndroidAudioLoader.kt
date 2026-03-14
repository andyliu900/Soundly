package com.ideacode.soundly_sdk.core.audio.io

import android.net.Uri
import com.ideacode.soundly_sdk.core.audio.decode.MediaCodecDecoder
import com.ideacode.soundly_sdk.core.audio.model.PcmData
import timber.log.Timber

class AndroidAudioLoader(
    private val decoder: MediaCodecDecoder,
): AudioLoader {

    override fun load(uri: Uri): PcmData {
        Timber.i("uri: $uri")
        val pcmData = decodeToPcm(uri)
        Timber.i("Loaded audio sampleRate=${pcmData.sampleRate}, pcmSize=${pcmData.pcm.size}")

        return pcmData
    }

    private fun decodeToPcm(uri: Uri): PcmData {
        return decoder.decode(uri)
    }

}