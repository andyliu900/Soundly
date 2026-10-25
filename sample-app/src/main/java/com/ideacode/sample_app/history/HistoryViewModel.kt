package com.ideacode.sample_app.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ideacode.soundly_sdk.api.capability.AudioPlaybackController
import com.ideacode.soundly_sdk.api.capability.AudioProjectRepository
import com.ideacode.soundly_sdk.api.model.audio.AudioSourceRef
import com.ideacode.soundly_sdk.api.model.domain.AudioProject
import com.ideacode.soundly_sdk.api.Soundly
import com.ideacode.soundly_sdk.api.result.SoundlyResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

class HistoryViewModel(
    private val repository: AudioProjectRepository,
    private val audioPlayer: AudioPlaybackController = Soundly.sdk().playback
): ViewModel() {

    private val _history = MutableStateFlow<List<AudioProject>>(emptyList())
    val history: StateFlow<List<AudioProject>> = _history

    fun loadHistory() {
        viewModelScope.launch {
            repository.observeProjects().collect { projects ->
                _history.value = projects
            }
        }
    }

    fun deleteHistory(id: String) {
        viewModelScope.launch {
            repository.deleteProject(id)
        }
    }

    fun playOriginalAudio(file: File) {
        play(AudioSourceRef.LocalFile(file))
    }

    fun playProcessedAudio(wavFile: File) {
        play(AudioSourceRef.LocalFile(wavFile))
    }

    private fun play(source: AudioSourceRef) {
        viewModelScope.launch {
            when (audioPlayer.prepare(source)) {
                is SoundlyResult.Success -> audioPlayer.play()
                is SoundlyResult.Failure -> Timber.w("Unable to prepare project audio")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        audioPlayer.release()
    }

}
