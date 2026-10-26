package com.ideacode.soundly.feature.settings

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ideacode.soundly.R
import com.ideacode.soundly.app.SoundlyApplication
import com.ideacode.soundly.databinding.FragmentSettingsBinding

/** F31 entry point. It only routes; each page owns its persisted SDK operation. */
class SettingsFragment : Fragment(R.layout.fragment_settings) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentSettingsBinding.bind(view)
        binding.settingsRecording.setOnClickListener { findNavController().navigate(R.id.recordingSettingsFragment) }
        binding.settingsOutput.setOnClickListener { findNavController().navigate(R.id.outputSettingsFragment) }
        binding.settingsStorage.setOnClickListener { findNavController().navigate(R.id.storageManagementFragment) }
        binding.settingsPrivacy.setOnClickListener { findNavController().navigate(R.id.privacyFragment) }
        binding.settingsHelp.setOnClickListener { findNavController().navigate(R.id.helpFeedbackFragment) }
        binding.settingsPro.setOnClickListener { findNavController().navigate(R.id.proFragment) }
        binding.settingsTheme.setOnClickListener { showThemePicker() }
    }

    private fun showThemePicker() {
        val preferences = (requireActivity().application as SoundlyApplication).appContainer.appPreferences
        val modes = intArrayOf(
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
            AppCompatDelegate.MODE_NIGHT_NO,
            AppCompatDelegate.MODE_NIGHT_YES
        )
        val labels = arrayOf(getString(R.string.theme_system), getString(R.string.theme_light), getString(R.string.theme_dark))
        val selected = modes.indexOf(preferences.nightMode).coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_theme)
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                preferences.nightMode = modes[which]
                AppCompatDelegate.setDefaultNightMode(modes[which])
                dialog.dismiss()
            }
            .show()
    }
}
