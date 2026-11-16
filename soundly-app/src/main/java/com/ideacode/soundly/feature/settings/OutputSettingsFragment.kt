package com.ideacode.soundly.feature.settings

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ideacode.soundly.R
import com.ideacode.soundly.app.SoundlyApplication
import com.ideacode.soundly.common.error.AppResult
import com.ideacode.soundly.databinding.FragmentOutputSettingsBinding
import com.ideacode.soundly_sdk.api.model.settings.SoundlySettings
import kotlinx.coroutines.launch

class OutputSettingsFragment : Fragment(R.layout.fragment_output_settings) {
    private var current: SoundlySettings? = null
    private var binding: FragmentOutputSettingsBinding? = null
    private var saving = false
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val b = FragmentOutputSettingsBinding.bind(view); binding = b
        val gateway = (requireActivity().application as SoundlyApplication).appContainer.settingsStorageGateway
        b.outputFormat.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, SettingsOptions.outputFormats.map { it.name })
        SettingsChoiceRows.populate(b.outputNaming, SettingsOptions.naming.map { getString(SettingsOptions.naming(it)) to getString(if (it == com.ideacode.soundly_sdk.api.model.settings.RecordingNamingStrategy.DATE_TIME) R.string.s4_name_date_desc else R.string.s4_name_timestamp_desc) })
        b.outputSettingsSave.isEnabled = false
        b.outputSettingsSave.setOnClickListener {
            val base = current ?: return@setOnClickListener
            if (saving) return@setOnClickListener
            val updated = base.copy(outputFormat = SettingsOptions.outputFormats[b.outputFormat.selectedItemPosition], namingStrategy = SettingsOptions.naming[SettingsChoiceRows.selectedIndex(b.outputNaming).coerceAtLeast(0)])
            saving = true; b.outputSettingsSave.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                when (gateway.updateSettings(updated)) {
                    is AppResult.Success -> { android.widget.Toast.makeText(requireContext(), R.string.settings_saved, android.widget.Toast.LENGTH_SHORT).show(); if (findNavController().currentDestination?.id == R.id.outputSettingsFragment) findNavController().navigateUp() }
                    is AppResult.Failure -> { saving = false; b.outputSettingsSave.isEnabled = true; MaterialAlertDialogBuilder(requireContext()).setMessage(R.string.error_operation_failed).setPositiveButton(android.R.string.ok, null).show() }
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch { viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            gateway.observeSettings().collect { settings ->
                val initial = current == null; current = settings
                if (initial) {
                    b.outputFormat.setSelection((savedInstanceState?.getInt("format") ?: SettingsOptions.outputFormats.indexOf(settings.outputFormat)).coerceIn(SettingsOptions.outputFormats.indices))
                    SettingsChoiceRows.select(b.outputNaming, (savedInstanceState?.getInt("naming") ?: SettingsOptions.naming.indexOf(settings.namingStrategy)).coerceIn(SettingsOptions.naming.indices))
                    b.outputSettingsSave.isEnabled = true
                }
            }
        } }
    }
    override fun onSaveInstanceState(outState: Bundle) { binding?.let { outState.putInt("format", it.outputFormat.selectedItemPosition); outState.putInt("naming", SettingsChoiceRows.selectedIndex(it.outputNaming)) }; super.onSaveInstanceState(outState) }
    override fun onDestroyView() { binding = null; current = null; saving = false; super.onDestroyView() }
}
