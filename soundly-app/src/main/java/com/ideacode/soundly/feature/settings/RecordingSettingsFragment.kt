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
import com.ideacode.soundly.databinding.FragmentRecordingSettingsBinding
import com.ideacode.soundly.feature.home.SceneLabels
import com.ideacode.soundly_sdk.api.model.domain.RecordingScene
import com.ideacode.soundly_sdk.api.model.settings.SoundlySettings
import kotlinx.coroutines.launch

class RecordingSettingsFragment : Fragment(R.layout.fragment_recording_settings) {
    private var current: SoundlySettings? = null
    private var binding: FragmentRecordingSettingsBinding? = null
    private var saving = false
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val b = FragmentRecordingSettingsBinding.bind(view); binding = b
        val gateway = (requireActivity().application as SoundlyApplication).appContainer.settingsStorageGateway
        val scenes = RecordingScene.entries
        SettingsChoiceRows.populate(b.recordingScene, scenes.map {
            getString(SceneLabels.name(it)) to getString(when (it) {
                RecordingScene.PODCAST -> R.string.s4_scene_podcast_desc
                RecordingScene.OUTDOOR -> R.string.s4_scene_outdoor_desc
                else -> SceneLabels.description(it)
            })
        })
        b.recordingQuality.adapter = adapter(SettingsOptions.qualities.map { getString(SettingsOptions.quality(it)) })
        b.recordingFormat.adapter = adapter(SettingsOptions.recordingFormats.map { it.name })
        b.recordingSettingsSave.isEnabled = false
        b.recordingSettingsSave.setOnClickListener {
            val base = current ?: return@setOnClickListener
            if (saving) return@setOnClickListener
            val updated = base.copy(defaultScene = scenes[SettingsChoiceRows.selectedIndex(b.recordingScene).coerceAtLeast(0)],
                recordingQuality = SettingsOptions.qualities[b.recordingQuality.selectedItemPosition],
                recordingFormat = SettingsOptions.recordingFormats[b.recordingFormat.selectedItemPosition],
                recordInBackground = b.recordingBackground.isChecked)
            saving = true; b.recordingSettingsSave.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                when (gateway.updateSettings(updated)) {
                    is AppResult.Success -> { android.widget.Toast.makeText(requireContext(), R.string.settings_saved, android.widget.Toast.LENGTH_SHORT).show(); if (findNavController().currentDestination?.id == R.id.recordingSettingsFragment) findNavController().navigateUp() }
                    is AppResult.Failure -> { saving = false; b.recordingSettingsSave.isEnabled = true; showError() }
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch { viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            gateway.observeSettings().collect { settings ->
                val initial = current == null; current = settings
                if (initial) {
                    SettingsChoiceRows.select(b.recordingScene, (savedInstanceState?.getInt("scene") ?: scenes.indexOf(settings.defaultScene)).coerceIn(scenes.indices))
                    b.recordingQuality.setSelection((savedInstanceState?.getInt("quality") ?: SettingsOptions.qualities.indexOf(settings.recordingQuality)).coerceIn(SettingsOptions.qualities.indices))
                    b.recordingFormat.setSelection((savedInstanceState?.getInt("format") ?: SettingsOptions.recordingFormats.indexOf(settings.recordingFormat)).coerceIn(SettingsOptions.recordingFormats.indices))
                    b.recordingBackground.isChecked = savedInstanceState?.getBoolean("background") ?: settings.recordInBackground
                    b.recordingSettingsSave.isEnabled = true
                }
            }
        } }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        binding?.let { outState.putInt("scene", SettingsChoiceRows.selectedIndex(it.recordingScene)); outState.putInt("quality", it.recordingQuality.selectedItemPosition); outState.putInt("format", it.recordingFormat.selectedItemPosition); outState.putBoolean("background", it.recordingBackground.isChecked) }
        super.onSaveInstanceState(outState)
    }
    override fun onDestroyView() { binding = null; current = null; saving = false; super.onDestroyView() }
    private fun adapter(values: List<String>) = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, values)
    private fun showError() { MaterialAlertDialogBuilder(requireContext()).setMessage(R.string.error_operation_failed).setPositiveButton(android.R.string.ok, null).show() }
}
