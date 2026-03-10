package com.ideacode.soundly_sdk.container

import android.content.Context
import android.util.Log
import com.ideacode.soundly_core.ai.decision.DefaultPresetDecisionEngine
import com.ideacode.soundly_core.ai.decision.PresetDecisionEngine
import com.ideacode.soundly_core.audio.analyze.DefaultAudioAnalyzer
import com.ideacode.soundly_core.audio.decode.MediaCodecDecoder
import com.ideacode.soundly_core.audio.dsp.pipeline.DefaultDspPipeline
import com.ideacode.soundly_core.audio.io.AndroidAudioLoader
import com.ideacode.soundly_core.audio.processor.AudioProcessor
import com.ideacode.soundly_core.audio.processor.DefaultAudioProcessor
import com.ideacode.soundly_core.history.recorder.HistoryRecorder
import com.ideacode.soundly_core.media.player.AudioPlayer
import com.ideacode.soundly_sdk.player.SdkAudioPlayer
import com.ideacode.soundly_core.util.WavWriter
import com.ideacode.soundly_sdk.history.data.db.AppDatabase
import com.ideacode.soundly_sdk.history.recoder.RoomHistoryRecoder
import com.ideacode.soundly_sdk.history.repository.RoomAudioHistoryRepository
import com.ideacode.soundly_sdk.processor.SdkAudioProcessor
import java.io.File

/**
 * SDK 依赖容器
 */
class SdkContainer() {

    companion object {
        const val TAG = "SdkContainer"
    }

    private lateinit var appContext: Context
    private lateinit var database: AppDatabase

    fun init(context: Context) {
        Log.i(TAG, "SdkContainer init")

        appContext = context
        database = AppDatabase.get(context)
    }

    /* ================
     * Audio 基础能力
     * =============== */

    /**
     * 音频解码器
     * 作用：音频 -> PCM
     */
    private val decoder by lazy {
        MediaCodecDecoder(appContext)
    }

    /**
     * 音频解析器
     * 作用： PCM -> AudioFeatures
     */
    private val audioAnalyzer by lazy {
        DefaultAudioAnalyzer()
    }

    /**
     * DSP Pipline   PCM -> PCM(EQ/Compressor/Denoise)
     */
    private val dspPipline by lazy {
        DefaultDspPipeline()
    }

    /**
     * AndroidAudioLoader
     * 解码：将 Uri -> PCM
     */
    val audioLoader: AndroidAudioLoader by lazy {
        AndroidAudioLoader(
            decoder = decoder
        )
    }

    /**
     * core DefaultAudioProcessor
     * 1、分析
     * 2、DSP处理
     */
    val audioProcessor: AudioProcessor by lazy {
        DefaultAudioProcessor(
            analyzer = audioAnalyzer,
            dspPipeline = dspPipline,
            wavWriter = wavWriter,
            historyRecorder = historyRecorder
        )
    }

    /**
     * sdk SdkAudioProcessor
     *
     * SDK 维度的 Processor 入口
     */
    val sdkAudioProcessor: SdkAudioProcessor by lazy {
        SdkAudioProcessor()
    }

    /**
     * WAV 文件生成工具类
     */
    val wavWriter: WavWriter by lazy {
        val audioDir = File(appContext.filesDir, "audio").apply {
            if (!exists()) mkdirs()
        }

        WavWriter(audioDir)
    }

    /**
     * 播放器
     */
    val audioPlayer: SdkAudioPlayer by lazy {
        SdkAudioPlayer(
            context = appContext
        )
    }

    /**
     * history repo
     */
    val historyRepository: RoomAudioHistoryRepository by lazy {
        RoomAudioHistoryRepository(
            dao = database.audioHistoryDao()
        )
    }

    /**
     * history recorder
     */
    val historyRecorder: HistoryRecorder by lazy {
        RoomHistoryRecoder(
            repository = historyRepository
        )
    }

    /* ================
     * AI 决策能力
     * =============== */

    val presetDecisionEngine: PresetDecisionEngine by lazy {
        DefaultPresetDecisionEngine()
    }

    /* ================
     * 云端服务
     * =============== */
//    val cloudService: CloudService by lazy {
//        CloudService()
//    }

}