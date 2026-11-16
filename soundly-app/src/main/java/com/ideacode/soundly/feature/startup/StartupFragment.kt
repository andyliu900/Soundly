package com.ideacode.soundly.feature.startup

import android.os.Bundle
import android.content.res.Configuration
import android.view.View
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.ideacode.soundly.R
import com.ideacode.soundly.app.SoundlyApplication
import com.ideacode.soundly.databinding.FragmentStartupBinding
import kotlinx.coroutines.launch

/** F01: show the startup design, then route according to the real app state. */
class StartupFragment : Fragment(R.layout.fragment_startup) {
    private val viewModel: StartupViewModel by viewModels {
        val container = (requireActivity().application as SoundlyApplication).appContainer
        StartupViewModel.factory(container)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentStartupBinding.bind(view)
        val window = requireActivity().window
        val bars = WindowCompat.getInsetsController(window, window.decorView)
        val lightBars = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES
        bars.isAppearanceLightStatusBars = lightBars
        bars.isAppearanceLightNavigationBars = lightBars
        binding.startupRetry.setOnClickListener { viewModel.dispatch(StartupAction.Retry) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    val error = state as? StartupUiState.Error
                    binding.startupMessage.isVisible = error != null
                    binding.startupRetry.isVisible = error != null
                    if (error != null) {
                        val mapper = (requireActivity().application as SoundlyApplication)
                            .appContainer.errorMessageMapper
                        binding.startupMessage.setText(mapper.messageRes(error.error))
                    }
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.effects.collect { effects ->
                    val effect = effects.firstOrNull() ?: return@collect
                    if (effect.value is StartupEffect.Navigate && navigate(effect.value.destination)) {
                        viewModel.acknowledgeEffect(effect.id)
                    }
                }
            }
        }
    }

    private fun navigate(destination: StartupDestination): Boolean {
        val navController = findNavController()
        if (navController.currentDestination?.id != R.id.startupFragment) return false
        val (id, args) = when (destination) {
            StartupDestination.Onboarding -> R.id.onboardingFragment to null
            StartupDestination.Home -> R.id.homeFragment to null
            StartupDestination.Recording -> R.id.recordingFragment to null
            is StartupDestination.Preview -> R.id.audioPreviewFragment to Bundle().apply {
                putString("projectId", destination.result.projectId)
                putString("variantId", destination.result.originalVariantId)
            }
            is StartupDestination.Recovery -> R.id.recoveryFragment to Bundle().apply {
                putString("sessionId", destination.sessionId)
            }
        }
        val options = NavOptions.Builder()
            .setPopUpTo(R.id.startupFragment, true)
            .setLaunchSingleTop(true)
            .build()
        navController.navigate(id, args, options)
        if (destination is StartupDestination.Preview) {
            (requireActivity().application as SoundlyApplication).appContainer.recordingGateway.acknowledgeSaved(destination.result.sessionId)
        }
        return true
    }

    // MainActivity applies system bar appearance for the destination that follows startup.
}
