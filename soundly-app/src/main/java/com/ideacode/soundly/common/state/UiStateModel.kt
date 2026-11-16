package com.ideacode.soundly.common.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Marker interfaces keep each feature's state, actions and effects independent. */
interface UiState
interface UiAction
interface UiEffect

data class PendingEffect<out E : UiEffect>(val id: Long, val value: E)

/**
 * Effects remain available while the UI is stopped or recreated. A consumer acknowledges an
 * effect only after handling it; the queue belongs to the ViewModel, not the process or disk.
 */
class UiEffectQueue<E : UiEffect> {
    private var nextId = 0L
    private val mutablePending = MutableStateFlow<List<PendingEffect<E>>>(emptyList())
    val pending: StateFlow<List<PendingEffect<E>>> = mutablePending.asStateFlow()

    @Synchronized
    fun post(effect: E) {
        val entry = PendingEffect(++nextId, effect)
        mutablePending.value = mutablePending.value + entry
    }

    @Synchronized
    fun acknowledge(id: Long) {
        mutablePending.value = mutablePending.value.filterNot { it.id == id }
    }
}

abstract class StateActionViewModel<S : UiState, A : UiAction, E : UiEffect>(initial: S) : ViewModel() {
    private val mutableState = MutableStateFlow(initial)
    val uiState: StateFlow<S> = mutableState.asStateFlow()
    private val effectQueue = UiEffectQueue<E>()
    val effects: StateFlow<List<PendingEffect<E>>> = effectQueue.pending

    protected fun updateState(transform: (S) -> S) { mutableState.update(transform) }
    protected fun postEffect(effect: E) { effectQueue.post(effect) }
    fun acknowledgeEffect(id: Long) = effectQueue.acknowledge(id)
    abstract fun dispatch(action: A)
}

/** Explicit factory usable by a feature without a DI framework or unchecked dependency lookup. */
class SoundlyViewModelFactory<T : ViewModel>(
    private val type: Class<T>,
    private val create: () -> T
) : ViewModelProvider.Factory {
    override fun <V : ViewModel> create(modelClass: Class<V>): V {
        require(modelClass == type) { "Unexpected ViewModel class: ${modelClass.name}" }
        return modelClass.cast(create())!!
    }
}
