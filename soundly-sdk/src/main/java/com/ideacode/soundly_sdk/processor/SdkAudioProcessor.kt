package com.ideacode.soundly_sdk.processor

import android.net.Uri
import com.ideacode.soundly_core.audio.processor.state.ProcessingProcess
import com.ideacode.soundly_model.audio.dsp.PresetOption
import com.ideacode.soundly_sdk.Soundly
import com.ideacode.soundly_sdk.preset.PresetFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SdkAudioProcessor()  {

    /**
     * 音频处理核心逻辑
     */
    suspend fun process(
        uri: Uri,
        presetOption: PresetOption,
        outputFileName: String,
        onProcess: (ProcessingProcess) -> Unit
    ) {

        //1、加载音频
        val audio = withContext(Dispatchers.IO) {
            val audioLoader = Soundly.container().audioLoader
            audioLoader.load(uri)
        }

        //2、开始处理
        val wavFile = withContext(Dispatchers.IO) {
            val audioProcessor = Soundly.container().audioProcessor
            audioProcessor.process(
                uri = uri,
                inputPcm = audio.pcm,
                sampleRate = audio.sampleRate,
                channels = audio.channels,
                outputFileName = outputFileName,
                presetOption = presetOption,
                presetBuilder = { feature ->
                    PresetFactory.build(presetOption, feature)
                },
                onProcess = onProcess
            )
        }
    }

}