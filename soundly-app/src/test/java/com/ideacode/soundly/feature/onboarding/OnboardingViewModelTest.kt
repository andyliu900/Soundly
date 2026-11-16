package com.ideacode.soundly.feature.onboarding

import org.junit.Assert.assertEquals
import org.junit.Test

class OnboardingViewModelTest {
    @Test fun `completion is persisted and navigation emitted only once`() {
        var writes = 0
        val model = OnboardingViewModel { writes++ }
        model.dispatch(OnboardingAction.Continue)
        model.dispatch(OnboardingAction.Continue)
        assertEquals(1, writes)
        assertEquals(OnboardingEffect.OpenHome, model.effects.value.single().value)
    }
}
