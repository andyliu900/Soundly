package com.ideacode.soundly.feature.project

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ideacode.soundly.common.error.AppResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class ProjectDeletionState { IDLE, RUNNING, SUCCEEDED, FAILED }

/** Keeps a confirmed deletion alive across view and activity recreation. */
internal class ProjectDeletionViewModel(private val projectId: String, private val deleteProject: suspend (String) -> AppResult<Unit>) : ViewModel() {
    private val mutableState = MutableStateFlow(ProjectDeletionState.IDLE)
    val state = mutableState.asStateFlow()
    fun delete() {
        if (mutableState.value != ProjectDeletionState.IDLE) return
        mutableState.value = ProjectDeletionState.RUNNING
        viewModelScope.launch {
            mutableState.value = when (deleteProject(projectId)) {
                is AppResult.Success -> ProjectDeletionState.SUCCEEDED
                is AppResult.Failure -> ProjectDeletionState.FAILED
            }
        }
    }
    fun acknowledgeFailure() { if (mutableState.value == ProjectDeletionState.FAILED) mutableState.value = ProjectDeletionState.IDLE }
}
