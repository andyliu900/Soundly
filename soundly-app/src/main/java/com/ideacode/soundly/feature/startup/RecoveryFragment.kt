package com.ideacode.soundly.feature.startup

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.ideacode.soundly.R
import com.ideacode.soundly.app.SoundlyApplication
import com.ideacode.soundly.common.error.AppResult
import com.ideacode.soundly.databinding.FragmentRecoveryBinding
import kotlinx.coroutines.launch

/** F14 functional recovery surface. High fidelity can later replace it with a Bottom Sheet. */
class RecoveryFragment : Fragment(R.layout.fragment_recovery) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentRecoveryBinding.bind(view)
        val sessionId = requireArguments().getString("sessionId").orEmpty()
        val gateway = (requireActivity().application as SoundlyApplication).appContainer.recordingGateway
        binding.recoverySave.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                when (val result = gateway.recover(sessionId)) {
                    is AppResult.Success -> findNavController().navigate(
                        R.id.audioPreviewFragment,
                        Bundle().apply {
                            putString("projectId", result.value.projectId)
                            putString("variantId", result.value.originalVariantId)
                        }
                    )
                    is AppResult.Failure -> findNavController().navigate(R.id.homeFragment)
                }
            }
        }
        binding.recoveryDiscard.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                gateway.discard()
                findNavController().navigate(R.id.homeFragment)
            }
        }
    }
}
