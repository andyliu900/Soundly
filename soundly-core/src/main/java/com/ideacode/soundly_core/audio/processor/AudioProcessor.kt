package com.ideacode.soundly_core.audio.processor

import android.net.Uri
import com.ideacode.soundly_core.audio.analyze.AudioFeatures
import com.ideacode.soundly_core.audio.dsp.model.DspPreset
import com.ideacode.soundly_core.audio.processor.state.ProcessingProcess
import com.ideacode.soundly_model.audio.dsp.PresetOption
import java.io.File

interface AudioProcessor {

    /**
     * 音频处理（DSP Pipeline）
     */
    suspend fun process(
        uri: Uri,
        inputPcm: FloatArray,
        sampleRate: Int,
        channels: Int,
        outputFileName: String,
        presetOption: PresetOption,
        presetBuilder: (AudioFeatures?) -> DspPreset,
        onProcess: (ProcessingProcess) -> Unit
    ): File

}