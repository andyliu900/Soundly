package com.ideacode.soundly.feature.onboarding

import com.ideacode.soundly.common.state.StateActionViewModel
import com.ideacode.soundly.common.state.UiAction
import com.ideacode.soundly.common.state.UiEffect
import com.ideacode.soundly.common.state.UiState

internal data class OnboardingUiState(val completed: Boolean = false) : UiState
internal sealed interface OnboardingAction : UiAction { data object Continue : OnboardingAction }
internal sealed interface OnboardingEffect : UiEffect { data object OpenHome : OnboardingEffect }

internal class OnboardingViewModel(private val completeOnboarding: () -> Unit) :
    StateActionViewModel<OnboardingUiState, OnboardingAction, OnboardingEffect>(OnboardingUiState()) {
    override fun dispatch(action: OnboardingAction) {
        when (action) {
            OnboardingAction.Continue -> {
                if (uiState.value.completed) return
                completeOnboarding()
                updateState { it.copy(completed = true) }
                postEffect(OnboardingEffect.OpenHome)
            }
        }
    }
}
