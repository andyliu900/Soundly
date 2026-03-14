package com.ideacode.sample_app.history

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ideacode.soundly_sdk.api.model.audio.AudioSource
import com.ideacode.soundly_sdk.api.model.domain.AudioHistory
import com.ideacode.soundly_sdk.api.Soundly
import com.ideacode.soundly_sdk.history.repository.RoomAudioHistoryRepository
import com.ideacode.soundly_sdk.player.SdkAudioPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

class HistoryViewModel(
    private val repository: RoomAudioHistoryRepository
): ViewModel() {

    private val audioPlayer: SdkAudioPlayer = Soundly.container().audioPlayer

    private val _history = MutableStateFlow<List<AudioHistory>>(emptyList())
    val history: StateFlow<List<AudioHistory>> = _history

    fun loadHistory() {
        viewModelScope.launch {
            _history.value = repository.loadAll()
        }
    }

    fun deleteHistory(id: String) {
        viewModelScope.launch {
            // 删除本地文件
            val audioVariantList = repository.getVariantList(id)
            if (audioVariantList.isNotEmpty()) {
                val audioVariant = audioVariantList.get(0)
                val localFilePath = audioVariant.filePath
                Timber.i("deleteHistory: $localFilePath")

                val file = File(localFilePath)
                if (file.exists()) {
                    val flag = file.delete()
                    Timber.i("deleteHistoryLocalFile: $flag")
                }
            }

            repository.delete(id)
            loadHistory()


        }
    }

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

    override fun onCleared() {
        super.onCleared()
        audioPlayer.release()
    }

}