package com.ideacode.soundly_sdk.core.audio.processor

import android.net.Uri
import com.ideacode.soundly_sdk.core.audio.analyze.AudioAnalyzer
import com.ideacode.soundly_sdk.core.audio.analyze.AudioFeatures
import com.ideacode.soundly_sdk.core.audio.dsp.model.DspPreset
import com.ideacode.soundly_sdk.core.audio.dsp.pipeline.DspPipeline
import com.ideacode.soundly_sdk.core.audio.processor.state.ProcessingProcess
import com.ideacode.soundly_sdk.core.history.recorder.HistoryRecorder
import com.ideacode.soundly_sdk.core.util.WavWriter
import com.ideacode.soundly_sdk.api.model.audio.dsp.PresetOption
import com.ideacode.soundly_sdk.api.model.audio.processor.ProcessingStage
import com.ideacode.soundly_sdk.api.model.domain.AudioVariant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import kotlin.math.min
import kotlin.coroutines.coroutineContext

class DefaultAudioProcessor(
    private val analyzer: AudioAnalyzer,
    private val dspPipeline: DspPipeline,
    private val wavWriter: WavWriter,
    private val historyRecorder: HistoryRecorder? = null
): AudioProcessor {

    override suspend fun process(
        uri: Uri,
        inputPcm: FloatArray,
        sampleRate: Int,
        channels: Int,
        outputFileName: String,
        presetOption: PresetOption,
        presetBuilder: (AudioFeatures?) -> DspPreset,
        onProcess: (ProcessingProcess) -> Unit
    ): File = withContext(Dispatchers.Default) {
        try {

            val historyId = historyRecorder?.onProcessStart(
                originalPath = uri.toString(),
                sampleRate = sampleRate,
                channels = channels,
                durationMs = calculateDuration(
                    inputPcm,
                    sampleRate,
                    channels
                )
            )
            Timber.i("historyId: $historyId")

            // 1、分析音频特征 analyze
            onProcess(ProcessingProcess(
                stage = ProcessingStage.ANALYZING,
                message = "正在分析音频特征"
            ))


            val features = analyzer.analyze(inputPcm, sampleRate)
            Timber.i("feature: $features")

            ensureActive()

            // 2、生成 DSP 策略
            val preset = presetBuilder(features)
            Timber.i("preset: $preset")

            // 3、流水线配置 config
            dspPipeline.configure(preset)

            // 4、执行 DSP 处理 process
            val putputPcm = processByFrame(
                inputPcm = inputPcm,
                sampleRate = sampleRate,
                onProcess = onProcess
            )

            ensureActive()

//            if (BuildConfig.DEBUG) {
//                val processedPcmFile = FileUtils.writePcmToFile(
//                    pcm = output,
//                    sampleRate = sampleRate
//                )
//                Timber.i("processedPcmFile: ${processedPcmFile.absolutePath}")
//            }

            // 5、WAV 文件生成
            onProcess(
                ProcessingProcess(
                    stage = ProcessingStage.WRITING_WAV,
                    percent = 0,
                    message = "正在生成WAV文件"
                )
            )

            val wavFile = withContext(Dispatchers.IO) {
                wavWriter.write(
                    pcm = putputPcm,
                    sampleRate = sampleRate,
                    channels = channels,
                    fileName = outputFileName
                )
            }
            Timber.i("WAV file:${wavFile.absolutePath}")

            // 6、处理完成
            historyId?.let {
                historyRecorder.onVariantGenerated(
                    historyId = it,
                    variant = AudioVariant(
                        filePath = wavFile.absolutePath,
                        presetOption = presetOption,
                        isOriginal = false,
                        sampleRate = sampleRate,
                        channels = channels,
                        durationMs = calculateDuration(
                            putputPcm,
                            sampleRate,
                            channels
                        )
                    )
                )
            }

            onProcess(
                ProcessingProcess(
                    stage = ProcessingStage.COMPLETED,
                    percent = 100,
                    message = "处理完成",
                    wavFilePath = wavFile.absolutePath
                )
            )

            wavFile
        } catch (e: CancellationException) {
            onProcess(
                ProcessingProcess(
                    stage = ProcessingStage.CANCELLED,
                    message = "处理已取消"
                )
            )

            throw e
        } catch (e: Exception) {
            onProcess(
                ProcessingProcess(
                    stage = ProcessingStage.ERROR,
                    message = "处理异常：${e.message}"
                )
            )

            throw e
        }
    }

    /**
     * 分帧处理PCM数据
     *
     * 避免一次性大数组运算
     * 支持进度回调
     */
    private suspend fun processByFrame(
        inputPcm: FloatArray,
        sampleRate: Int,
        onProcess: (ProcessingProcess) -> Unit
    ) : FloatArray {

        val output = FloatArray(inputPcm.size)

        val frameSize = 1024
        val totalFrames = (inputPcm.size + frameSize - 1)/frameSize

        var frameIndex = 0
        var offset = 0

        onProcess(
            ProcessingProcess(
                stage = ProcessingStage.DSP_PROCESSING,
                percent = 0,
                message = "正在进行音频增强"
            )
        )

        while (offset < inputPcm.size) {
            coroutineContext.ensureActive()

            val size = min(frameSize, inputPcm.size - offset)

            val inputFrame = inputPcm.copyOfRange(offset, offset + size)
            val processedFrame = dspPipeline.process(
                inputFrame,
                sampleRate
            )

            System.arraycopy(
                processedFrame,
                0,
                output,
                offset,
                size
            )

            offset += size
            frameIndex++

            val percent = frameIndex * 100 / totalFrames

            onProcess(
                ProcessingProcess(
                    stage = ProcessingStage.DSP_PROCESSING,
                    percent = percent,
                    message = "音频增强中 $percent%"
                )
            )
        }

        return output
    }

    /**
     * 计算音频时长
     */
    private fun calculateDuration(
        pcm: FloatArray,
        sampleRate: Int,
        channels: Int
    ): Long {
        val frames = pcm.size / channels
        return frames * 1000L / sampleRate
    }
}