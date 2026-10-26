package com.ideacode.soundly.feature.settings

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.ideacode.soundly.R
import com.ideacode.soundly.app.SoundlyApplication
import com.ideacode.soundly.common.error.AppResult
import com.ideacode.soundly.databinding.FragmentRecordingSettingsBinding
import com.ideacode.soundly_sdk.api.model.domain.AudioFormat
import com.ideacode.soundly_sdk.api.model.domain.RecordingScene
import com.ideacode.soundly_sdk.api.model.recording.RecordingQuality
import com.ideacode.soundly_sdk.api.model.settings.SoundlySettings
import kotlinx.coroutines.launch

/** F32. Values are persisted by the SDK and used by prepareUsingDefaults(). */
class RecordingSettingsFragment : Fragment(R.layout.fragment_recording_settings) {
    private var current: SoundlySettings? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentRecordingSettingsBinding.bind(view)
        val gateway = (requireActivity().application as SoundlyApplication).appContainer.settingsStorageGateway
        bindAdapter(binding.recordingScene, RecordingScene.entries.map { it.name })
        bindAdapter(binding.recordingQuality, RecordingQuality.entries.map { it.name })
        bindAdapter(binding.recordingFormat, AudioFormat.entries.map { it.name })
        binding.recordingSettingsSave.setOnClickListener {
            val base = current ?: return@setOnClickListener
            val updated = base.copy(
                defaultScene = RecordingScene.entries[binding.recordingScene.selectedItemPosition],
                recordingQuality = RecordingQuality.entries[binding.recordingQuality.selectedItemPosition],
                recordingFormat = AudioFormat.entries[binding.recordingFormat.selectedItemPosition],
                recordInBackground = binding.recordingBackground.isChecked
            )
            viewLifecycleOwner.lifecycleScope.launch {
                when (gateway.updateSettings(updated)) {
                    is AppResult.Success -> Snackbar.make(view, R.string.settings_saved, Snackbar.LENGTH_SHORT).show()
                    is AppResult.Failure -> showOperationError()
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                gateway.observeSettings().collect { settings ->
                    current = settings
                    binding.recordingScene.setSelection(RecordingScene.entries.indexOf(settings.defaultScene))
                    binding.recordingQuality.setSelection(RecordingQuality.entries.indexOf(settings.recordingQuality))
                    binding.recordingFormat.setSelection(AudioFormat.entries.indexOf(settings.recordingFormat))
                    binding.recordingBackground.isChecked = settings.recordInBackground
                }
            }
        }
    }

    private fun bindAdapter(spinner: android.widget.Spinner, entries: List<String>) {
        spinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, entries)
    }

    private fun showOperationError() = MaterialAlertDialogBuilder(requireContext())
        .setMessage(R.string.error_operation_failed)
        .setPositiveButton(android.R.string.ok, null)
        .show()
}
