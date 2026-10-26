package com.ideacode.soundly.feature.recording

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ideacode.soundly.R
import com.ideacode.soundly.app.SoundlyApplication
import com.ideacode.soundly.common.error.AppResult
import com.ideacode.soundly.databinding.FragmentRecordingBinding
import com.ideacode.soundly_sdk.api.model.recording.RecordingState
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.Locale

/** F08/F09/F11/F12/F13. All displayed telemetry comes from RecordingState. */
class RecordingFragment : Fragment(R.layout.fragment_recording) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentRecordingBinding.bind(view)
        val gateway = (requireActivity().application as SoundlyApplication).appContainer.recordingGateway

        binding.recordingMarker.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch { gateway.addMarker() }
        }
        binding.recordingPauseResume.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                when (gateway.state.value) {
                    is RecordingState.Paused -> gateway.resume()
                    is RecordingState.Recording -> gateway.pause()
                    else -> Unit
                }
            }
        }
        binding.recordingFinish.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                when (val result = gateway.stopAndSave()) {
                    is AppResult.Success -> findNavController().navigate(
                        R.id.audioPreviewFragment,
                        Bundle().apply {
                            putString("projectId", result.value.projectId)
                            putString("variantId", result.value.originalVariantId)
                        }
                    )
                    is AppResult.Failure -> showOperationError()
                }
            }
        }
        binding.recordingDiscard.setOnClickListener { showDiscardConfirmation() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                gateway.state.collect { render(binding, it) }
            }
        }
    }

    private fun render(binding: FragmentRecordingBinding, state: RecordingState) = with(binding) {
        val durationMs = when (state) {
            is RecordingState.Recording -> state.durationMs
            is RecordingState.Paused -> state.durationMs
            is RecordingState.Interrupted -> state.durationMs
            else -> 0L
        }
        val bytes = when (state) {
            is RecordingState.Recording -> state.recordedBytes
            is RecordingState.Paused -> state.recordedBytes
            is RecordingState.Interrupted -> state.recordedBytes
            else -> 0L
        }
        val markerCount = when (state) {
            is RecordingState.Recording -> state.markerCount
            is RecordingState.Paused -> state.markerCount
            is RecordingState.Interrupted -> state.markerCount
            else -> 0
        }
        recordingDuration.text = formatDuration(durationMs)
        recordingMetadata.text = getString(R.string.recording_bytes, formatBytes(bytes)) + " · " +
            getString(R.string.recording_markers, markerCount)
        recordingPauseResume.text = if (state is RecordingState.Paused) {
            getString(R.string.recording_resume)
        } else {
            getString(R.string.recording_pause)
        }
        recordingStatus.text = when (state) {
            is RecordingState.Recording -> getString(R.string.recording_title)
            is RecordingState.Paused -> getString(R.string.recording_paused)
            is RecordingState.Interrupted -> getString(R.string.recording_interrupted) + "：${state.reason.name}"
            is RecordingState.Finalizing -> getString(R.string.recording_finalizing)
            else -> getString(R.string.recording_idle)
        }
        val active = state is RecordingState.Recording || state is RecordingState.Paused
        recordingMarker.isEnabled = active
        recordingPauseResume.isEnabled = active
        recordingFinish.isEnabled = active || state is RecordingState.Interrupted
        recordingWaveform.isVisible = state is RecordingState.Recording
    }

    private fun showDiscardConfirmation() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.discard_title)
            .setMessage(R.string.discard_message)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.recording_finish) { _, _ ->
                val gateway = (requireActivity().application as SoundlyApplication).appContainer.recordingGateway
                viewLifecycleOwner.lifecycleScope.launch {
                    when (val result = gateway.stopAndSave()) {
                        is AppResult.Success -> findNavController().navigate(
                            R.id.audioPreviewFragment,
                            Bundle().apply {
                                putString("projectId", result.value.projectId)
                                putString("variantId", result.value.originalVariantId)
                            }
                        )
                        is AppResult.Failure -> showOperationError()
                    }
                }
            }
            .setPositiveButton(R.string.discard_confirm) { _, _ ->
                val gateway = (requireActivity().application as SoundlyApplication).appContainer.recordingGateway
                viewLifecycleOwner.lifecycleScope.launch {
                    gateway.discard()
                    findNavController().navigate(R.id.homeFragment)
                }
            }
            .show()
    }

    private fun showOperationError() {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.error_operation_failed)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun formatDuration(durationMs: Long): String {
        val seconds = durationMs / 1_000L
        return String.format(Locale.US, "%02d:%02d", seconds / 60L, seconds % 60L)
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_048_576 -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576f)
        bytes >= 1_024 -> String.format(Locale.US, "%.1f KB", bytes / 1_024f)
        else -> "$bytes B"
    }
}
