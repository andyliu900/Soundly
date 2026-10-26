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
import com.ideacode.soundly.databinding.FragmentOutputSettingsBinding
import com.ideacode.soundly_sdk.api.model.domain.AudioFormat
import com.ideacode.soundly_sdk.api.model.settings.RecordingNamingStrategy
import com.ideacode.soundly_sdk.api.model.settings.SoundlySettings
import kotlinx.coroutines.launch

/** F33. Export defaults and naming strategy are persisted in soundly-sdk. */
class OutputSettingsFragment : Fragment(R.layout.fragment_output_settings) {
    private var current: SoundlySettings? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentOutputSettingsBinding.bind(view)
        val gateway = (requireActivity().application as SoundlyApplication).appContainer.settingsStorageGateway
        binding.outputFormat.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, AudioFormat.entries.map { it.name })
        binding.outputNaming.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, RecordingNamingStrategy.entries.map { it.name })
        binding.outputSettingsSave.setOnClickListener {
            val base = current ?: return@setOnClickListener
            val updated = base.copy(
                outputFormat = AudioFormat.entries[binding.outputFormat.selectedItemPosition],
                namingStrategy = RecordingNamingStrategy.entries[binding.outputNaming.selectedItemPosition]
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
                    binding.outputFormat.setSelection(AudioFormat.entries.indexOf(settings.outputFormat))
                    binding.outputNaming.setSelection(RecordingNamingStrategy.entries.indexOf(settings.namingStrategy))
                }
            }
        }
    }

    private fun showOperationError() = MaterialAlertDialogBuilder(requireContext())
        .setMessage(R.string.error_operation_failed)
        .setPositiveButton(android.R.string.ok, null)
        .show()
}
