package com.ideacode.sample_app.home

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ideacode.soundly_sdk.api.model.audio.AudioSourceRef
import com.ideacode.soundly_sdk.api.model.audio.dsp.PresetOption
import com.ideacode.soundly_sdk.api.model.audio.processor.ProcessingRequest
import com.ideacode.soundly_sdk.api.model.audio.processor.ProcessingStage
import com.ideacode.soundly_sdk.api.Soundly
import com.ideacode.soundly_sdk.api.result.SoundlyResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

class HomeViewModel : ViewModel() {

    private val audioPlayer = Soundly.sdk().playback


    /**
     * 当前选中的处理策略 （UI控制）
     */
    private val _presetOption = MutableStateFlow(PresetOption.DEFAULT)
    val presetOption: StateFlow<PresetOption> = _presetOption

    /**
     * 处理进度 / 状态
     */
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var processingJob: Job? = null

    /**
     * 用户切换策略
     */
    fun setPresetOption(option: PresetOption) {
        _presetOption.value = option
        Timber.i("setPresetOption = ${option.title}")
    }

    /**
     * 用户点击【选择音频文件】
     */
    fun onAudioSelected(uri: Uri) {

        processingJob?.cancel()

        processingJob = viewModelScope.launch {
            try {
                _uiState.value = HomeUiState(
                    isProcessing = true,
                    stage = ProcessingStage.LOADING,
                    message = "正在加载音频"
                )

                val result = Soundly.sdk().processor.process(
                    request = ProcessingRequest(
                        source = AudioSourceRef.ContentUri(uri),
                        presetOption = _presetOption.value,
                        outputFileName = "enhanced_${_presetOption.value}_${System.currentTimeMillis()}"
                    )
                ) { progress ->
                    _uiState.value = _uiState.value.copy(
                        stage = progress.stage,
                        process = progress.percent,
                        message = progress.stage.toDisplayMessage()
                    )
                }

                _uiState.value = when (result) {
                    is SoundlyResult.Success -> _uiState.value.copy(
                        isProcessing = false,
                        stage = ProcessingStage.COMPLETED,
                        process = 100,
                        message = "处理完成",
                        wavFilePath = result.value.outputFilePath
                    )

                    is SoundlyResult.Failure -> HomeUiState(
                        isProcessing = false,
                        stage = ProcessingStage.ERROR,
                        message = "处理失败：${result.error.code}"
                    )
                }
            }  catch (e: CancellationException) {
                _uiState.value = HomeUiState(
                    isProcessing = false,
                    stage = ProcessingStage.CANCELLED,
                    message = "处理已取消"
                )

                e.printStackTrace()
            } catch (e: Exception) {
                _uiState.value = HomeUiState(
                    isProcessing = false,
                    stage = ProcessingStage.ERROR,
                    message = "处理失败"
                )

                e.printStackTrace()
            }
        }
    }

    // 处理过程中取消任务
    fun cancelProcessing() {
        processingJob?.cancel()
    }

    // 播放音频逻辑
    fun playOriginalAudio(uri: Uri) {
        prepareAndPlay(AudioSourceRef.ContentUri(uri))
    }

    fun playProcessedAudio(wavFile: File) {
        prepareAndPlay(AudioSourceRef.LocalFile(wavFile))
    }

    fun audioPause() {
        audioPlayer.pause()
    }

    private fun prepareAndPlay(source: AudioSourceRef) {
        viewModelScope.launch {
            if (audioPlayer.prepare(source) is SoundlyResult.Success) {
                audioPlayer.play()
            }
        }
    }

    override fun onCleared() {
        audioPlayer.release()
    }

    private fun ProcessingStage.toDisplayMessage(): String = when (this) {
        ProcessingStage.IDLE -> "等待处理"
        ProcessingStage.LOADING -> "正在加载音频"
        ProcessingStage.ANALYZING -> "正在分析音频"
        ProcessingStage.DSP_PROCESSING -> "正在增强音频"
        ProcessingStage.WRITING_WAV -> "正在生成文件"
        ProcessingStage.COMPLETED -> "处理完成"
        ProcessingStage.CANCELLED -> "处理已取消"
        ProcessingStage.ERROR -> "处理失败"
    }

}
