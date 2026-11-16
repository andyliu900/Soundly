package com.ideacode.soundly.feature.recording

import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.navOptions
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ideacode.soundly.R
import com.ideacode.soundly.app.SoundlyApplication
import com.ideacode.soundly.common.error.AppResult
import com.ideacode.soundly.common.ui.setTopAppBarTitle
import com.ideacode.soundly.common.ui.DisplayFormat
import com.ideacode.soundly.databinding.FragmentRecordingBinding
import com.ideacode.soundly.databinding.DialogDiscardRecordingBinding
import com.ideacode.soundly.feature.home.SceneLabels
import com.ideacode.soundly_sdk.api.model.recording.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.collectLatest

class RecordingFragment : Fragment(R.layout.fragment_recording) {
    private val container get() = (requireActivity().application as SoundlyApplication).appContainer
    internal val viewModel: RecordingViewModel by viewModels {
        object : AbstractSavedStateViewModelFactory(this, arguments) {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(key: String, modelClass: Class<T>, handle: SavedStateHandle): T =
                RecordingViewModel(container.recordingGateway, handle) as T
        }
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val b = FragmentRecordingBinding.bind(view)
        val back = object : OnBackPressedCallback(true) { override fun handleOnBackPressed() { confirmExit() } }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, back)
        b.recordingMarker.setOnClickListener { viewModel.dispatch(RecordingAction.Marker) }
        b.recordingPauseResume.setOnClickListener { viewModel.dispatch(RecordingAction.Toggle) }
        b.recordingFinish.setOnClickListener { viewModel.dispatch(RecordingAction.Save) }
        b.recordingProblemRetry.setOnClickListener {
            viewModel.dispatch(if (viewModel.uiState.value.recording is RecordingState.Failed) RecordingAction.Recover else RecordingAction.Toggle)
        }
        b.recordingProblemSave.setOnClickListener { viewModel.dispatch(RecordingAction.Save) }
        b.recordingStorage.setOnClickListener { findNavController().navigate(R.id.storageManagementFragment) }
        viewLifecycleOwner.lifecycleScope.launch { viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.uiState.collect {
                render(b, it)
                val dialog = childFragmentManager.findFragmentByTag("discard_recording") as? DiscardRecordingDialog
                if (it.discardSessionId != null && dialog == null && !childFragmentManager.isStateSaved)
                    DiscardRecordingDialog().showNow(childFragmentManager, "discard_recording")
                else if (it.discardSessionId == null) dialog?.dismissAllowingStateLoss()
            }
        } }
        viewLifecycleOwner.lifecycleScope.launch { viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.waveform.collect { b.recordingWaveform.submit(it) }
        } }
        viewLifecycleOwner.lifecycleScope.launch { viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.uiState.map { (it.recording as? RecordingState.Interrupted)?.reason == RecordingInterruptionReason.INSUFFICIENT_STORAGE }
                .distinctUntilChanged().collectLatest { low ->
                    b.recordingProblemSpace.isVisible = false
                    if (low) {
                        val result = container.settingsStorageGateway.storageSnapshot()
                        if (result is AppResult.Success) {
                            b.recordingProblemSpace.text = getString(R.string.s1_available_space, DisplayFormat.byteSize(result.value.availableBytes))
                            b.recordingProblemSpace.isVisible = true
                        }
                    }
                }
        } }
        viewLifecycleOwner.lifecycleScope.launch { viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.effects.collect { entries ->
                val entry = entries.firstOrNull() ?: return@collect
                val nav = findNavController()
                if (nav.currentDestination?.id != R.id.recordingFragment) return@collect
                when (val effect = entry.value) {
                    is RecordingEffect.Preview -> {
                        nav.navigate(R.id.audioPreviewFragment, bundleOf("projectId" to effect.result.projectId,
                            "variantId" to effect.result.originalVariantId), navOptions { popUpTo(R.id.recordingFragment) { inclusive = true } })
                        viewModel.acknowledgePreview(effect.result)
                    }
                    RecordingEffect.Home -> if (!nav.popBackStack(R.id.homeFragment, false)) {
                        nav.navigate(R.id.homeFragment, null, navOptions { popUpTo(R.id.recordingFragment) { inclusive = true } })
                    }
                    is RecordingEffect.Error -> MaterialAlertDialogBuilder(requireContext())
                        .setMessage(container.errorMessageMapper.messageRes(effect.error)).setPositiveButton(android.R.string.ok, null).show()
                }
                viewModel.acknowledgeEffect(entry.id)
            }
        } }
        viewLifecycleOwner.lifecycleScope.launch { viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val guard = (requireActivity().application as SoundlyApplication).backgroundRecordingGuard
            guard.failure.collect { error ->
                if (error != null && findNavController().currentDestination?.id == R.id.recordingFragment) {
                    guard.clearFailure()
                    MaterialAlertDialogBuilder(requireContext()).setMessage(R.string.s1_background_pause_failed)
                        .setPositiveButton(android.R.string.ok, null).show()
                }
            }
        } }
    }
    private fun render(b: FragmentRecordingBinding, ui: RecordingUiState) {
        val state = ui.recording
        val active = state is RecordingState.Recording
        val paused = state is RecordingState.Paused
        val interrupted = state is RecordingState.Interrupted
        val failed = state is RecordingState.Failed
        val finalizing = state is RecordingState.Finalizing
        b.recordingDuration.text = DisplayFormat.durationMillis(ui.durationMs, true)
        b.recordingMetadata.text = getString(R.string.h4_bytes, DisplayFormat.byteSize(ui.recordedBytes))
        setTopAppBarTitle(R.id.recordingFragment, when { finalizing -> R.string.h4_finalizing; failed -> R.string.h4_failed; interrupted -> R.string.h4_interrupted; paused -> R.string.h4_paused; else -> R.string.h4_recording })
        b.recordingStatus.setText(when { finalizing -> R.string.h4_finalizing; failed -> R.string.h4_failed; interrupted -> R.string.h4_interrupted_status; paused -> R.string.h4_paused_status; else -> R.string.h4_recording_status })
        b.recordingConfig.text = ui.config?.let { config -> getString(R.string.h4_details, getString(SceneLabels.name(config.scene)),
            getString(if (config.quality == RecordingQuality.HIGH) R.string.quality_high else R.string.quality_standard)) } ?: getString(R.string.h4_unknown_config)
        b.recordingWaveform.paused = !active
        val last = ui.markers.lastOrNull()
        b.recordingLatestMarker.isVisible = last != null
        if (last != null) b.recordingLatestMarker.text = getString(R.string.h4_marker_latest, ui.markers.size, DisplayFormat.durationMillis(last.positionMs))
        b.recordingMarkers.isVisible = ui.markers.isNotEmpty()
        if (ui.markers.isNotEmpty()) b.recordingMarkers.text = getString(R.string.h4_marker_count, ui.markers.size)
        b.recordingControls.isVisible = !interrupted && !failed
        b.recordingProblem.isVisible = interrupted || failed
        val lowStorage = (state as? RecordingState.Interrupted)?.reason == RecordingInterruptionReason.INSUFFICIENT_STORAGE
        b.recordingProblemTitle.setText(if (lowStorage) R.string.s1_storage_title else if (failed) R.string.h4_failed else R.string.s1_interrupted_title)
        b.recordingProblemRetained.text = getString(R.string.s1_retained, DisplayFormat.byteSize(ui.recordedBytes))
        b.recordingProgress.isVisible = ui.busy || finalizing
        b.recordingMarker.isEnabled = active && !ui.busy
        b.recordingMarker.alpha = if (b.recordingMarker.isEnabled) 1f else .4f
        b.recordingPauseResume.isEnabled = (active || paused) && !ui.busy
        b.recordingPauseResume.setImageResource(if (paused) R.drawable.ic_h4_play else R.drawable.ic_h4_pause)
        b.recordingPauseLabel.setText(if (paused) R.string.h4_resume else R.string.h4_pause)
        b.recordingPauseResume.contentDescription = b.recordingPauseLabel.text
        b.recordingFinish.isEnabled = (active || paused) && !ui.busy
        b.recordingProblemRetry.isEnabled = !ui.busy && (interrupted || (state is RecordingState.Failed && state.sessionId != null))
        b.recordingProblemRetry.isVisible = !lowStorage
        b.recordingProblemSave.isVisible = interrupted
        b.recordingProblemSave.isEnabled = !ui.busy
        b.recordingStorage.isVisible = state is RecordingState.Interrupted && state.reason == RecordingInterruptionReason.INSUFFICIENT_STORAGE
        if (interrupted) b.recordingProblemText.setText(when ((state as RecordingState.Interrupted).reason) {
            RecordingInterruptionReason.INSUFFICIENT_STORAGE -> R.string.h4_interruption_storage
            RecordingInterruptionReason.MICROPHONE_UNAVAILABLE -> R.string.h4_interruption_mic
            RecordingInterruptionReason.AUDIO_FOCUS_LOST, RecordingInterruptionReason.PHONE_CALL -> R.string.h4_interruption_focus
            RecordingInterruptionReason.INPUT_DEVICE_DISCONNECTED -> R.string.h4_interruption_device
            RecordingInterruptionReason.UNKNOWN -> R.string.h4_interruption_unknown
        }) else if (failed) b.recordingProblemText.setText(R.string.h4_recover_failed)
        b.recordingProblemRetry.setText(if (failed) R.string.h4_recover_now else R.string.h4_retry)
    }
    private fun confirmExit() {
        val state = viewModel.uiState.value
        if (state.busy || state.recording is RecordingState.Finalizing || state.discardSessionId != null) return
        if (state.recording is RecordingState.Idle) {
            val nav = findNavController()
            if (!nav.popBackStack(R.id.homeFragment, false)) nav.navigate(R.id.homeFragment)
            return
        }
        viewModel.requestDiscardConfirmation()
    }
}

class DiscardRecordingDialog : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): android.app.Dialog {
        val model = (requireParentFragment() as RecordingFragment).viewModel
        val state = model.uiState.value
        val binding = DialogDiscardRecordingBinding.inflate(layoutInflater)
        binding.discardDetails.text = getString(R.string.s1_discard_details, DisplayFormat.durationMillis(state.durationMs, true), DisplayFormat.byteSize(state.recordedBytes))
        val dialog = MaterialAlertDialogBuilder(requireContext()).setView(binding.root).create()
        binding.discardReturn.setOnClickListener { model.clearDiscardConfirmation(); dismiss() }
        binding.discardConfirm.setOnClickListener { model.dispatch(RecordingAction.Discard); dismiss() }
        return dialog
    }
    override fun onCancel(dialog: android.content.DialogInterface) {
        (parentFragment as? RecordingFragment)?.viewModel?.clearDiscardConfirmation()
        super.onCancel(dialog)
    }
}
