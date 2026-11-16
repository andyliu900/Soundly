package com.ideacode.soundly.feature.recording

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ideacode.soundly.R
import com.ideacode.soundly.app.SoundlyApplication
import com.ideacode.soundly.databinding.FragmentPrepareRecordingBinding
import com.ideacode.soundly.feature.home.RecordingSceneSheet
import com.ideacode.soundly.feature.home.SceneLabels
import com.ideacode.soundly.feature.recording.permission.MicrophonePermissionController
import com.ideacode.soundly_sdk.api.model.domain.RecordingScene
import com.ideacode.soundly_sdk.api.model.recording.RecordingQuality
import com.ideacode.soundly_sdk.api.model.recording.RecordingState
import kotlinx.coroutines.launch

/** F07. Permission and system UI are kept outside the recording state machine. */
class PrepareRecordingFragment : Fragment(R.layout.fragment_prepare_recording) {
    private val container get() = (requireActivity().application as SoundlyApplication).appContainer
    private val viewModel: PrepareRecordingViewModel by viewModels { PrepareRecordingViewModel.factory(this, arguments, container) }
    private var pendingStart = false
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (pendingStart) { pendingStart = false; startIfPermitted() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingStart = savedInstanceState?.getBoolean("pending_start") ?: false
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putBoolean("pending_start", pendingStart); super.onSaveInstanceState(outState) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentPrepareRecordingBinding.bind(view)
        val backGuard = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = Unit
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backGuard)
        binding.prepareScene.setOnClickListener {
            val scene = viewModel.uiState.value.scene ?: return@setOnClickListener
            if (childFragmentManager.findFragmentByTag(RecordingSceneSheet.TAG) == null)
                RecordingSceneSheet.create(scene).show(childFragmentManager, RecordingSceneSheet.TAG)
        }
        binding.prepareQuality.setOnClickListener {
            val qualities = RecordingQuality.entries.toTypedArray()
            val current = qualities.indexOf(viewModel.uiState.value.quality)
            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.prepare_quality)
                .setSingleChoiceItems(qualities.map { getString(qualityLabel(it)) }.toTypedArray(), current) { dialog, which ->
                    viewModel.dispatch(PrepareAction.Quality(qualities[which])); dialog.dismiss()
                }.setNegativeButton(android.R.string.cancel, null).show()
        }
        binding.prepareStart.setOnClickListener {
            if (!hasMicrophonePermission()) MicrophonePermissionController.get(childFragmentManager).begin()
            else offerNotificationsThenStart()
        }
        childFragmentManager.setFragmentResultListener(RecordingSceneSheet.RESULT_KEY, viewLifecycleOwner) { _, result ->
            RecordingScene.entries.firstOrNull { it.name == result.getString(RecordingSceneSheet.RESULT_SCENE) }
                ?.let { viewModel.dispatch(PrepareAction.Scene(it)) }
        }
        childFragmentManager.setFragmentResultListener(MicrophonePermissionController.RESULT, viewLifecycleOwner) { _, result ->
            when (result.getString(MicrophonePermissionController.OUTCOME)) {
                MicrophonePermissionController.GRANTED -> offerNotificationsThenStart()
                MicrophonePermissionController.IMPORT -> if (findNavController().currentDestination?.id == R.id.prepareRecordingFragment)
                    findNavController().navigate(R.id.importFragment, null, androidx.navigation.navOptions { launchSingleTop = true })
                // Returning from settings rechecks permission; the user starts explicitly.
                MicrophonePermissionController.SETTINGS_GRANTED -> Unit
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    backGuard.isEnabled = state.busy
                    val enabled = !state.loading && !state.busy && !state.loadError
                    binding.prepareStart.isEnabled = enabled
                    binding.prepareScene.isEnabled = enabled
                    binding.prepareQuality.isEnabled = enabled
                    binding.prepareProgress.isVisible = state.loading || state.busy
                    binding.prepareStartLabel.setText(if (state.busy) R.string.prepare_starting else R.string.prepare_start)
                    if (state.scene != null && state.quality != null) {
                        val scene = getString(SceneLabels.name(state.scene)); val quality = getString(qualityLabel(state.quality))
                        binding.prepareSceneValue.text = getString(R.string.prepare_option_value, scene)
                        binding.prepareQualityValue.text = getString(R.string.prepare_option_value, quality)
                        binding.prepareSummary.text = getString(R.string.prepare_summary, scene, quality)
                    }
                    if (state.loadError && childFragmentManager.findFragmentByTag("load_error") == null) {
                        showLoadError()
                    }
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.effects.collect { effects ->
                    val entry = effects.firstOrNull() ?: return@collect
                    if (findNavController().currentDestination?.id != R.id.prepareRecordingFragment) return@collect
                    when (val effect = entry.value) {
                        PrepareEffect.RecordingStarted -> {
                            findNavController().navigate(R.id.recordingFragment)
                            viewModel.recordingNavigationHandled()
                        }
                        is PrepareEffect.Error -> showError(if (effect.error.code == "recording_recovery_required") R.string.prepare_recovery_required else container.errorMessageMapper.messageRes(effect.error))
                    }
                    viewModel.acknowledgeEffect(entry.id)
                }
            }
        }
    }
    override fun onResume() {
        super.onResume()
        when (container.recordingGateway.state.value) {
            is RecordingState.Idle -> viewModel.clearFinishedNavigation()
            else -> Unit
        }
    }
    private fun hasMicrophonePermission() = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun startIfPermitted() {
        if (hasMicrophonePermission()) viewModel.dispatch(PrepareAction.Start)
        else MicrophonePermissionController.get(childFragmentManager).begin()
    }
    private fun offerNotificationsThenStart() {
        if (viewModel.uiState.value.defaults?.recordInBackground == true && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !container.appPreferences.hasAskedNotificationPermission) {
            MaterialAlertDialogBuilder(requireContext()).setMessage(R.string.prepare_notification_message)
                .setPositiveButton(R.string.prepare_notification_allow) { _, _ ->
                    container.appPreferences.hasAskedNotificationPermission = true
                    pendingStart = true
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }.setNegativeButton(R.string.prepare_notification_skip) { _, _ ->
                    container.appPreferences.hasAskedNotificationPermission = true; startIfPermitted()
                }.show()
        } else startIfPermitted()
    }
    private fun showError(message: Int) {
        MaterialAlertDialogBuilder(requireContext()).setMessage(message).setPositiveButton(android.R.string.ok, null).show()
    }
    private var loadDialogShown = false
    private fun showLoadError() {
        if (loadDialogShown) return
        loadDialogShown = true
        MaterialAlertDialogBuilder(requireContext()).setMessage(R.string.prepare_load_failed)
            .setPositiveButton(R.string.foundation_retry) { _, _ -> viewModel.dispatch(PrepareAction.Load) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> findNavController().navigateUp() }
            .setOnDismissListener { loadDialogShown = false }.show()
    }
    private fun qualityLabel(quality: RecordingQuality) = if (quality == RecordingQuality.HIGH) R.string.quality_high else R.string.quality_standard
}
