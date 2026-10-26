package com.ideacode.soundly.feature.enhancement

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ideacode.soundly.R
import com.ideacode.soundly.app.SoundlyApplication
import com.ideacode.soundly.databinding.FragmentEnhancementProgressBinding
import com.ideacode.soundly_sdk.api.model.enhancement.EnhancementTaskState
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** F19/F20/F21: observes SDK task state instead of polling progress. */
class EnhancementProgressFragment : Fragment(R.layout.fragment_enhancement_progress) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentEnhancementProgressBinding.bind(view)
        val taskId = requireArguments().getString("taskId").orEmpty()
        val gateway = (requireActivity().application as SoundlyApplication).appContainer.editingGateway
        binding.enhancementCancel.setOnClickListener { confirmCancel(taskId) }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                gateway.observeEnhancement(taskId).collect { state ->
                    when (state) {
                        is EnhancementTaskState.Running -> {
                            binding.enhancementProgress.isIndeterminate = false
                            binding.enhancementProgress.progress = state.progressPercent
                            binding.enhancementStage.text = getString(
                                R.string.enhancement_progress, state.progressPercent
                            )
                        }
                        is EnhancementTaskState.Completed -> findNavController().navigate(
                            R.id.enhancementResultFragment,
                            Bundle().apply {
                                putString("projectId", state.result.projectId)
                                putString("originalVariantId", state.task.sourceVariantId)
                                putString("enhancedVariantId", state.result.variantId)
                            }
                        )
                        is EnhancementTaskState.Failed -> showFailure(taskId, state.retryable)
                        is EnhancementTaskState.Cancelled -> findNavController().popBackStack()
                        null -> Unit
                    }
                }
            }
        }
    }

    private fun confirmCancel(taskId: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.enhancement_cancel_title)
            .setMessage(R.string.enhancement_cancel_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.enhancement_cancel) { _, _ ->
                val gateway = (requireActivity().application as SoundlyApplication).appContainer.editingGateway
                viewLifecycleOwner.lifecycleScope.launch { gateway.cancelEnhancement(taskId) }
            }
            .show()
    }

    private fun showFailure(taskId: String, retryable: Boolean) {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.error_operation_failed)
            .setNegativeButton(R.string.cancel) { _, _ -> findNavController().popBackStack() }
            .setPositiveButton(if (retryable) R.string.enhancement_retry else R.string.foundation_retry) { _, _ ->
                if (retryable) {
                    val gateway = (requireActivity().application as SoundlyApplication).appContainer.editingGateway
                    viewLifecycleOwner.lifecycleScope.launch {
                        val retry = gateway.retryEnhancement(taskId)
                        if (retry is com.ideacode.soundly.common.error.AppResult.Success) {
                            findNavController().navigate(
                                R.id.enhancementProgressFragment,
                                Bundle().apply { putString("taskId", retry.value.taskId) }
                            )
                        }
                    }
                }
            }
            .show()
    }
}
