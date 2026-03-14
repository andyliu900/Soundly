package com.ideacode.sample_app.home

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ideacode.soundly_sdk.api.model.audio.AudioSource
import com.ideacode.soundly_sdk.api.model.audio.dsp.PresetOption
import com.ideacode.soundly_sdk.api.model.audio.processor.ProcessingStage
import com.ideacode.soundly_sdk.api.Soundly
import com.ideacode.soundly_sdk.player.SdkAudioPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

class HomeViewModel : ViewModel() {

    private val audioPlayer: SdkAudioPlayer = Soundly.container().audioPlayer


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

                Soundly.container().sdkAudioProcessor.process(
                    uri = uri,
                    presetOption = _presetOption.value,
                    outputFileName = "enhanced_${_presetOption.value}_${System.currentTimeMillis()}"
                ) { process ->
                    _uiState.value = _uiState.value.copy(
                        stage = process.stage,
                        process = process.percent,
                        message = process.message,
                        wavFilePath = process.wavFilePath
                    )
                }
            }  catch (e: CancellationException) {
                _uiState.value = HomeUiState(
                    isProcessing = false,
                    stage = ProcessingStage.CANCELLED,
                    message = "处理已取消, ${e.message}"
                )

                e.printStackTrace()
            } catch (e: Exception) {
                _uiState.value = HomeUiState(
                    isProcessing = false,
                    stage = ProcessingStage.ERROR,
                    message = "处理失败：${e.message}"
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
        audioPlayer.prepare(
            source = AudioSource.Original(uri),
            onPrepared = {
                audioPlayer.play()
            },
            onCompletion = null,
            onError = null
        )
    }

    fun playProcessedAudio(wavFile: File) {
        audioPlayer.prepare(
            source = AudioSource.Processed(wavFile),
            onPrepared = {
                audioPlayer.play()
            },
            onCompletion = null,
            onError = null
        )
    }

    fun audioPause() {
        audioPlayer.pause()
    }

    override fun onCleared() {
        audioPlayer.release()
    }

}