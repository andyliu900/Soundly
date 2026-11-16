package com.ideacode.soundly.feature.enhancement

import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.*
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ideacode.soundly.R
import com.ideacode.soundly.app.SoundlyApplication
import com.ideacode.soundly.databinding.FragmentH6EnhancementSetupBinding
import com.ideacode.soundly.feature.preview.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine

class EnhancementSetupFragment : Fragment(R.layout.fragment_h6_enhancement_setup) {
    private val container get() = (requireActivity().application as SoundlyApplication).appContainer
    private val model: EnhancementStartViewModel by viewModels {
        object : AbstractSavedStateViewModelFactory(this, arguments) {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(key: String, modelClass: Class<T>, handle: SavedStateHandle): T =
                EnhancementStartViewModel(handle, StartEnhancementUseCase(container.editingGateway)) as T
        }
    }
    private val source: AudioPreviewViewModel by viewModels {
        object : AbstractSavedStateViewModelFactory(this, arguments) {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(key: String, modelClass: Class<T>, handle: SavedStateHandle): T =
                AudioPreviewViewModel(handle, container.projectGateway, StartEnhancementUseCase(container.editingGateway)) as T
        }
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val b = FragmentH6EnhancementSetupBinding.bind(view)
        val renderer = EnhancementSetupRenderer(b,
            onSelect = { model.dispatch(EnhancementStartAction.Select(it)) },
            onSeek = { if (!model.uiState.value.busy) source.dispatch(PreviewAction.Seek(it)) })
        b.enhancementStart.setOnClickListener {
            if (source.uiState.value.variant != null) model.dispatch(EnhancementStartAction.Start(model.uiState.value.selected))
        }
        b.setupPlay.setOnClickListener { if (!model.uiState.value.busy) source.dispatch(PreviewAction.Toggle) }
        val guard = object : OnBackPressedCallback(false) { override fun handleOnBackPressed() = Unit }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, guard)
        viewLifecycleOwner.lifecycleScope.launch { viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            combine(model.uiState, source.uiState) { start, recording -> start to recording }.collect { (start, recording) ->
                guard.isEnabled = start.busy
                renderer.render(start, recording)
            }
        } }
        viewLifecycleOwner.lifecycleScope.launch { viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.effects.collect { effects ->
                val entry = effects.firstOrNull() ?: return@collect
                if (findNavController().currentDestination?.id != R.id.enhancementSetupFragment) return@collect
                when (val effect = entry.value) {
                    is EnhancementStartEffect.Started -> {
                        findNavController().navigate(R.id.enhancementProgressFragment, bundleOf("taskId" to effect.taskId, "projectId" to requireArguments().getString("projectId").orEmpty(), "sourceVariantId" to requireArguments().getString("variantId").orEmpty(), "sourceDestination" to requireArguments().getString("sourceDestination").orEmpty()))
                        model.navigationHandled()
                    }
                    is EnhancementStartEffect.Error -> MaterialAlertDialogBuilder(requireContext()).setMessage(container.errorMessageMapper.messageRes(effect.error)).setPositiveButton(android.R.string.ok, null).show()
                }
                model.acknowledgeEffect(entry.id)
            }
        } }
        viewLifecycleOwner.lifecycleScope.launch { viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            source.effects.collect { entries ->
                val entry = entries.firstOrNull() ?: return@collect
                if (findNavController().currentDestination?.id != R.id.enhancementSetupFragment) return@collect
                (entry.value as? PreviewEffect.Error)?.let { MaterialAlertDialogBuilder(requireContext()).setMessage(container.errorMessageMapper.messageRes(it.error)).setPositiveButton(android.R.string.ok, null).show() }
                source.acknowledgeEffect(entry.id)
            }
        } }
    }
    override fun onStart() { super.onStart(); source.setActive(true) }
    override fun onStop() { source.setActive(false); super.onStop() }
}
