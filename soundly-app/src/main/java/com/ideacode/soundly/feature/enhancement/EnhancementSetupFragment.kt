package com.ideacode.soundly.feature.enhancement

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.ideacode.soundly.R
import com.ideacode.soundly.app.SoundlyApplication
import com.ideacode.soundly.common.error.AppResult
import com.ideacode.soundly.databinding.FragmentEnhancementSetupBinding
import com.ideacode.soundly_sdk.api.model.audio.dsp.PresetOption
import kotlinx.coroutines.launch

/** F18: select a stable SDK preset before starting an asynchronous task. */
class EnhancementSetupFragment : Fragment(R.layout.fragment_enhancement_setup) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentEnhancementSetupBinding.bind(view)
        val presets = listOf(
            PresetOption.DEFAULT,
            PresetOption.SPEECH_FOCUS,
            PresetOption.VOICE_STABLE,
            PresetOption.NATURAL_SMOOTH
        )
        binding.enhancementMode.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_dropdown_item, presets.map { it.title }
        )
        binding.enhancementStart.setOnClickListener {
            val projectId = requireArguments().getString("projectId").orEmpty()
            val variantId = requireArguments().getString("variantId").orEmpty()
            val preset = presets[binding.enhancementMode.selectedItemPosition]
            val gateway = (requireActivity().application as SoundlyApplication).appContainer.editingGateway
            viewLifecycleOwner.lifecycleScope.launch {
                when (val result = gateway.startEnhancement(projectId, variantId, preset)) {
                    is AppResult.Success -> findNavController().navigate(
                        R.id.enhancementProgressFragment,
                        Bundle().apply { putString("taskId", result.value.taskId) }
                    )
                    is AppResult.Failure -> binding.enhancementStart.error = getString(R.string.error_operation_failed)
                }
            }
        }
    }
}
