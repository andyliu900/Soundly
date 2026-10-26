package com.ideacode.soundly.feature.onboarding

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.ideacode.soundly.R
import com.ideacode.soundly.app.SoundlyApplication
import com.ideacode.soundly.databinding.FragmentOnboardingBinding

/** F02. Onboarding completion belongs to the App, not the SDK. */
class OnboardingFragment : Fragment(R.layout.fragment_onboarding) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentOnboardingBinding.bind(view)
        binding.onboardingContinue.setOnClickListener {
            (requireActivity().application as SoundlyApplication)
                .appContainer.appPreferences.hasCompletedOnboarding = true
            findNavController().navigate(R.id.homeFragment)
        }
    }
}
