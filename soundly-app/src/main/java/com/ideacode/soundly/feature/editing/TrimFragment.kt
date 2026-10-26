package com.ideacode.soundly.feature.editing

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ideacode.soundly.R
import com.ideacode.soundly.app.SoundlyApplication
import com.ideacode.soundly.common.error.AppResult
import com.ideacode.soundly.databinding.FragmentTrimBinding
import com.ideacode.soundly_sdk.api.capability.TrimRange
import kotlinx.coroutines.launch

/** F17: simplified functional range selection; the high-fidelity waveform arrives in M8. */
class TrimFragment : Fragment(R.layout.fragment_trim) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentTrimBinding.bind(view)
        val projectId = requireArguments().getString("projectId").orEmpty()
        val variantId = requireArguments().getString("variantId").orEmpty()
        val duration = requireArguments().getLong("durationMs")
        binding.trimApply.setOnClickListener {
            val start = (binding.trimStart.value * duration).toLong()
            val end = (binding.trimEnd.value * duration).toLong()
            if (end <= start) return@setOnClickListener
            val gateway = (requireActivity().application as SoundlyApplication).appContainer.editingGateway
            viewLifecycleOwner.lifecycleScope.launch {
                val range = TrimRange(start, end)
                if (gateway.previewTrim(projectId, variantId, range) is AppResult.Failure) {
                    MaterialAlertDialogBuilder(requireContext())
                        .setMessage(R.string.error_operation_failed).setPositiveButton(android.R.string.ok, null).show()
                    return@launch
                }
                when (val result = gateway.applyTrim(projectId, variantId, range)) {
                    is AppResult.Success -> findNavController().navigate(
                        R.id.audioPreviewFragment,
                        Bundle().apply {
                            putString("projectId", result.value.projectId)
                            putString("variantId", result.value.variantId)
                        }
                    )
                    is AppResult.Failure -> MaterialAlertDialogBuilder(requireContext())
                        .setMessage(R.string.error_operation_failed).setPositiveButton(android.R.string.ok, null).show()
                }
            }
        }
    }
}
