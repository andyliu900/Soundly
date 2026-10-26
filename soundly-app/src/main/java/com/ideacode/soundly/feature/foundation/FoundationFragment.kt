package com.ideacode.soundly.feature.foundation

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ideacode.soundly.R
import com.ideacode.soundly.app.SoundlyApplication
import com.ideacode.soundly.databinding.FragmentFoundationBinding
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** M1 bootstrap surface; M2 replaces it with the F01 startup flow. */
class FoundationFragment : Fragment(R.layout.fragment_foundation) {
    private var _binding: FragmentFoundationBinding? = null
    private val binding get() = requireNotNull(_binding)

    private val appContainer
        get() = (requireActivity().application as SoundlyApplication).appContainer

    private val viewModel: FoundationViewModel by viewModels {
        FoundationViewModel.factory(appContainer.soundlySdkProvider)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentFoundationBinding.bind(view)
        binding.foundationRetry.setOnClickListener { viewModel.retry() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect(::render)
            }
        }
    }

    private fun render(state: FoundationUiState) = with(binding) {
        foundationProgress.isVisible = state.isLoading
        foundationRetry.isVisible = state.error != null
        foundationMessage.text = when {
            state.isReady -> getString(R.string.foundation_sdk_ready)
            state.error != null -> getString(appContainer.errorMessageMapper.messageRes(state.error))
            else -> ""
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
