package com.ideacode.soundly.feature.enhancement

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.ideacode.soundly.R
import com.ideacode.soundly.app.SoundlyApplication
import com.ideacode.soundly.common.error.AppResult
import com.ideacode.soundly.databinding.FragmentEnhancementResultBinding
import com.ideacode.soundly_sdk.api.model.domain.AudioVariant
import kotlinx.coroutines.launch

/** F22/F23: version selection preserves position through the SDK player API. */
class EnhancementResultFragment : Fragment(R.layout.fragment_enhancement_result) {
    private var selectedVariant: AudioVariant? = null
    private var playbackPrepared = false
    private lateinit var original: AudioVariant
    private lateinit var enhanced: AudioVariant

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentEnhancementResultBinding.bind(view)
        val projectId = requireArguments().getString("projectId").orEmpty()
        val originalId = requireArguments().getString("originalVariantId").orEmpty()
        val enhancedId = requireArguments().getString("enhancedVariantId").orEmpty()
        val projectGateway = (requireActivity().application as SoundlyApplication).appContainer.projectGateway

        viewLifecycleOwner.lifecycleScope.launch {
            when (val loaded = projectGateway.getProject(projectId)) {
                is AppResult.Success -> {
                    val all = listOf(loaded.value.original) + loaded.value.variants
                    original = all.first { it.id == originalId }
                    enhanced = all.first { it.id == enhancedId }
                    selectedVariant = enhanced
                    binding.versionToggle.check(R.id.version_enhanced)
                    binding.enhancementResultInfo.text = enhanced.presetOption.title
                }
                is AppResult.Failure -> findNavController().popBackStack()
            }
        }
        binding.versionToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || !::original.isInitialized) return@addOnButtonCheckedListener
            val next = if (checkedId == R.id.version_original) original else enhanced
            selectedVariant = next
            binding.enhancementResultInfo.text = next.presetOption.title
            if (playbackPrepared) {
                viewLifecycleOwner.lifecycleScope.launch { projectGateway.switchPlaybackKeepingPosition(next) }
            }
        }
        binding.enhancementResultPlay.setOnClickListener {
            val item = selectedVariant ?: return@setOnClickListener
            viewLifecycleOwner.lifecycleScope.launch {
                if (!playbackPrepared && projectGateway.preparePlayback(item) is AppResult.Success) {
                    playbackPrepared = true
                }
                if (playbackPrepared) projectGateway.play()
            }
        }
        binding.enhancementResultExport.setOnClickListener {
            val item = selectedVariant ?: return@setOnClickListener
            findNavController().navigate(R.id.exportFragment, Bundle().apply {
                putString("projectId", projectId)
                putString("variantId", item.id)
            })
        }
    }
}
