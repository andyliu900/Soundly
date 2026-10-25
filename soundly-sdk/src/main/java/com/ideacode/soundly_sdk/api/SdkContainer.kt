package com.ideacode.soundly_sdk.api

import android.content.Context
import com.ideacode.soundly_sdk.core.ai.decision.DefaultPresetDecisionEngine
import com.ideacode.soundly_sdk.core.ai.decision.PresetDecisionEngine
import com.ideacode.soundly_sdk.core.audio.analyze.DefaultAudioAnalyzer
import com.ideacode.soundly_sdk.core.audio.decode.MediaCodecDecoder
import com.ideacode.soundly_sdk.core.audio.dsp.pipeline.DefaultDspPipeline
import com.ideacode.soundly_sdk.core.audio.io.AndroidAudioLoader
import com.ideacode.soundly_sdk.core.audio.processor.AudioProcessor
import com.ideacode.soundly_sdk.core.audio.processor.DefaultAudioProcessor
import com.ideacode.soundly_sdk.core.history.recorder.HistoryRecorder
import com.ideacode.soundly_sdk.core.util.WavWriter
import com.ideacode.soundly_sdk.history.data.db.AppDatabase
import com.ideacode.soundly_sdk.history.recoder.RoomHistoryRecoder
import com.ideacode.soundly_sdk.history.repository.RoomAudioHistoryRepository
import com.ideacode.soundly_sdk.processor.AudioProcessingService
import com.ideacode.soundly_sdk.processor.createAudioProcessingService
import com.ideacode.soundly_sdk.importer.AndroidAudioMetadataReader
import com.ideacode.soundly_sdk.importer.DefaultAudioImporter
import com.ideacode.soundly_sdk.project.repository.RoomAudioProjectRepository
import com.ideacode.soundly_sdk.storage.DefaultAudioFileStore
import com.ideacode.soundly_sdk.recording.AndroidRecordingManager
import com.ideacode.soundly_sdk.playback.AndroidAudioPlaybackController
import com.ideacode.soundly_sdk.editing.AndroidAudioExporter
import com.ideacode.soundly_sdk.editing.AndroidAudioTrimmer
import com.ideacode.soundly_sdk.editing.DefaultStorageManager
import com.ideacode.soundly_sdk.editing.ProjectAudioEnhancer
import com.ideacode.soundly_sdk.editing.AndroidStreamingEnhancementProcessor
import java.io.File
import com.ideacode.soundly_sdk.api.model.recording.RecordingState
import com.ideacode.soundly_sdk.settings.SharedPreferencesSoundlySettingsRepository
import com.ideacode.soundly_sdk.internal.SoundlyDispatchers

/**
 * SDK 依赖容器
 */
private class SdkContainer(
    private val dispatchers: SoundlyDispatchers = SoundlyDispatchers.Default
) {

    private lateinit var appContext: Context
    private lateinit var database: AppDatabase

    fun init(context: Context) {
        appContext = context
        database = AppDatabase.Companion.get(context)
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
            historyRecorder = historyRecorder,
            dispatchers = dispatchers
        )
    }

    /**
     * sdk SdkAudioProcessor
     *
     * SDK 维度的 Processor 入口
     */
    val sdkAudioProcessor: AudioProcessingService by lazy {
        createAudioProcessingService(
            // Kept as factory compatibility parameters; the active path is fully streaming.
            audioLoader = null,
            audioProcessor = null,
            streamingProcessor = streamingEnhancementProcessor,
            fileStore = audioFileStore,
            contentResolver = appContext.contentResolver,
            importer = audioImporter,
            enhancer = projectAudioEnhancer,
            projects = projectRepository,
            dispatchers = dispatchers
        )
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

    internal val audioFileStore by lazy {
        DefaultAudioFileStore(
            filesDirectory = appContext.filesDir,
            cacheDirectory = appContext.cacheDir
        )
    }

    internal val projectRepository by lazy {
        RoomAudioProjectRepository(
            dao = database.audioProjectDao(),
            fileStore = audioFileStore
        )
    }

    internal val audioMetadataReader by lazy {
        AndroidAudioMetadataReader(appContext, dispatchers)
    }

    internal val audioImporter by lazy {
        DefaultAudioImporter(
            contentResolver = appContext.contentResolver,
            metadataReader = audioMetadataReader,
            projects = projectRepository,
            fileStore = audioFileStore,
            dispatchers = dispatchers
        )
    }

    internal val recordingManager by lazy {
        AndroidRecordingManager(
            context = appContext,
            projects = projectRepository,
            metadataReader = audioMetadataReader,
            fileStore = audioFileStore,
            dao = database.audioProjectDao(),
            settings = settingsRepository,
            dispatchers = dispatchers
        )
    }

    internal val playbackController by lazy {
        AndroidAudioPlaybackController(appContext, dispatchers)
    }

    internal val audioTrimmer by lazy {
        AndroidAudioTrimmer(
            projects = projectRepository,
            metadataReader = audioMetadataReader,
            fileStore = audioFileStore,
            dispatchers = dispatchers
        )
    }

    internal val audioExporter by lazy {
        AndroidAudioExporter(
            context = appContext,
            projects = projectRepository,
            fileStore = audioFileStore,
            settings = settingsRepository,
            dispatchers = dispatchers
        )
    }

    internal val storageManager by lazy {
        DefaultStorageManager(
            fileStore = audioFileStore,
            projectDao = database.audioProjectDao(),
            historyDao = database.audioHistoryDao(),
            settings = settingsRepository,
            canDeleteAllData = {
                recordingManager.state.value is RecordingState.Idle ||
                    recordingManager.state.value is RecordingState.Ready ||
                    recordingManager.state.value is RecordingState.Failed
            },
            beforeDeleteAllData = {
                playbackController.release()
                if (recordingManager.state.value !is RecordingState.Idle) {
                    recordingManager.discard()
                }
            },
            dispatchers = dispatchers
        )
    }

    private val streamingEnhancementProcessor by lazy {
        AndroidStreamingEnhancementProcessor(dispatchers = dispatchers)
    }

    internal val projectAudioEnhancer by lazy {
        ProjectAudioEnhancer(
            processor = streamingEnhancementProcessor,
            projects = projectRepository,
            metadataReader = audioMetadataReader,
            fileStore = audioFileStore,
            dispatchers = dispatchers
        )
    }

    internal val settingsRepository by lazy {
        SharedPreferencesSoundlySettingsRepository(
            context = appContext,
            dispatchers = dispatchers
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

@JvmSynthetic
internal fun createSoundlySdk(
    context: Context,
    dispatchers: SoundlyDispatchers = SoundlyDispatchers.Default
): SoundlySdk {
    val container = SdkContainer(dispatchers).apply {
        init(context.applicationContext)
    }
    return DefaultSoundlySdk(container)
}

private class DefaultSoundlySdk(
    container: SdkContainer
) : SoundlySdk {
    override val processor = container.sdkAudioProcessor
    override val projects = container.projectRepository
    override val importer = container.audioImporter
    override val metadata = container.audioMetadataReader
    override val recorder = container.recordingManager
    override val playback = container.playbackController
    override val trimmer = container.audioTrimmer
    override val exporter = container.audioExporter
    override val storage = container.storageManager
    override val enhancer = container.projectAudioEnhancer
    override val settings = container.settingsRepository
}
