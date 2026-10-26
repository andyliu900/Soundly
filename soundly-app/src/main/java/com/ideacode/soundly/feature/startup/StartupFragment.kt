package com.ideacode.soundly.feature.startup

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.ideacode.soundly.R
import com.ideacode.soundly.app.SoundlyApplication
import com.ideacode.soundly.common.error.AppResult
import com.ideacode.soundly.databinding.FragmentStartupBinding
import com.ideacode.soundly_sdk.api.model.recording.RecordingState
import kotlinx.coroutines.launch

/** F01: checks app-owned onboarding state and SDK-owned recovery state. */
class StartupFragment : Fragment(R.layout.fragment_startup) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentStartupBinding.bind(view)
        val container = (requireActivity().application as SoundlyApplication).appContainer
        viewLifecycleOwner.lifecycleScope.launch {
            when (container.soundlySdkProvider.get()) {
                is AppResult.Failure -> binding.startupMessage.setText(R.string.foundation_sdk_failed)
                is AppResult.Success -> {
                    val recovery = container.recordingGateway.recoverable()
                    val destination = when {
                        recovery is AppResult.Success && recovery.value.isNotEmpty() -> R.id.recoveryFragment
                        container.recordingGateway.state.value is RecordingState.Recording ||
                            container.recordingGateway.state.value is RecordingState.Paused ||
                            container.recordingGateway.state.value is RecordingState.Interrupted -> R.id.recordingFragment
                        container.appPreferences.hasCompletedOnboarding -> R.id.homeFragment
                        else -> R.id.onboardingFragment
                    }
                    if (destination == R.id.recoveryFragment) {
                        findNavController().navigate(destination, Bundle().apply {
                            putString("sessionId", (recovery as AppResult.Success).value.first().sessionId)
                        })
                    } else {
                        findNavController().navigate(destination)
                    }
                }
            }
        }
    }
}
