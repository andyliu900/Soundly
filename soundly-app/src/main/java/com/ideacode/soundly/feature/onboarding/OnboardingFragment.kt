package com.ideacode.soundly.feature.onboarding

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.ideacode.soundly.R
import com.ideacode.soundly.app.SoundlyApplication
import com.ideacode.soundly.common.state.SoundlyViewModelFactory
import com.ideacode.soundly.databinding.FragmentOnboardingBinding
import kotlinx.coroutines.launch

class OnboardingFragment : Fragment(R.layout.fragment_onboarding) {
    private val viewModel: OnboardingViewModel by viewModels {
        val preferences = (requireActivity().application as SoundlyApplication).appContainer.appPreferences
        SoundlyViewModelFactory(OnboardingViewModel::class.java) {
            OnboardingViewModel { preferences.hasCompletedOnboarding = true }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentOnboardingBinding.bind(view)
        binding.onboardingRecord.featureIcon.setImageResource(R.drawable.ic_microphone)
        binding.onboardingRecord.featureTitle.setText(R.string.onboarding_record_title)
        binding.onboardingRecord.featureBody.setText(R.string.onboarding_record_body)
        binding.onboardingEnhance.featureIcon.setImageResource(R.drawable.ic_soundly_wave)
        binding.onboardingEnhance.featureTitle.setText(R.string.onboarding_enhance_title)
        binding.onboardingEnhance.featureBody.setText(R.string.onboarding_enhance_body)
        binding.onboardingPrivacy.featureIcon.setImageResource(R.drawable.ic_shield)
        binding.onboardingPrivacy.featureTitle.setText(R.string.onboarding_privacy_title)
        binding.onboardingPrivacy.featureBody.setText(R.string.onboarding_privacy_body)
        binding.onboardingContinue.setOnClickListener { viewModel.dispatch(OnboardingAction.Continue) }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { binding.onboardingContinue.isEnabled = !it.completed }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.effects.collect { effects ->
                    val effect = effects.firstOrNull() ?: return@collect
                    val nav = findNavController()
                    if (effect.value == OnboardingEffect.OpenHome && nav.currentDestination?.id == R.id.onboardingFragment) {
                        nav.navigate(R.id.homeFragment, null, NavOptions.Builder()
                            .setPopUpTo(R.id.onboardingFragment, true).setLaunchSingleTop(true).build())
                        viewModel.acknowledgeEffect(effect.id)
                    }
                }
            }
        }
    }
}
