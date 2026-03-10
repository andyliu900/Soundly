package com.ideacode.sample_app.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.ideacode.soundly_sdk.history.repository.RoomAudioHistoryRepository

class HistoryViewModelFactory(
    private val repository: RoomAudioHistoryRepository
): ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HistoryViewModel::class.java)) {
            return HistoryViewModel(
                repository = repository
            ) as T
        }

        throw IllegalArgumentException("Unknow ViewModel class")
    }

}