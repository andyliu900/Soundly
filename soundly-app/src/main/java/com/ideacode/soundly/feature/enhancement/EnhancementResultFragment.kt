package com.ideacode.soundly.feature.enhancement

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.*
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ideacode.soundly.R
import com.ideacode.soundly.app.SoundlyApplication
import com.ideacode.soundly.databinding.FragmentH6EnhancementResultBinding
import kotlinx.coroutines.launch

class EnhancementResultFragment : Fragment(R.layout.fragment_h6_enhancement_result) {
    private val container get() = (requireActivity().application as SoundlyApplication).appContainer
    private val model: EnhancementResultViewModel by viewModels {
        object : AbstractSavedStateViewModelFactory(this, arguments) {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(key: String, modelClass: Class<T>, handle: SavedStateHandle): T =
                EnhancementResultViewModel(handle, container.projectGateway) as T
        }
    }
    private var rendering = false
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val b = FragmentH6EnhancementResultBinding.bind(view)
        val renderer = EnhancementResultRenderer(b)
        b.versionToggle.setOnCheckedChangeListener { _, id ->
            if (!rendering) {
                val s = model.uiState.value
                val next = if (id == R.id.version_original) s.source else s.enhanced
                next?.let { model.dispatch(ResultAction.Select(it.id)) }
            }
        }
        b.resultPlayer.onToggle = { model.dispatch(ResultAction.Toggle) }
        b.resultPlayer.onSeek = { model.dispatch(ResultAction.Seek(it)) }
        b.enhancementResultExport.setOnClickListener {
            val s = model.uiState.value; val v = s.selected ?: return@setOnClickListener
            if (!s.busy) findNavController().navigate(R.id.exportFragment, bundleOf("projectId" to s.project?.id, "variantId" to v.id))
        }
        b.resultNewVersion.setOnClickListener {
            val s = model.uiState.value; val v = s.source ?: return@setOnClickListener
            if (!s.busy) findNavController().navigate(R.id.enhancementSetupFragment, bundleOf("projectId" to s.project?.id, "variantId" to v.id, "sourceDestination" to requireArguments().getString("sourceDestination").orEmpty()))
        }
        viewLifecycleOwner.lifecycleScope.launch { viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            model.uiState.collect { s ->
                rendering = true
                try { renderer.render(s) } finally { rendering = false }
            }
        } }
        viewLifecycleOwner.lifecycleScope.launch { viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.effects.collect { entries ->
                val entry = entries.firstOrNull() ?: return@collect
                if (findNavController().currentDestination?.id != R.id.enhancementResultFragment) return@collect
                val effect = entry.value as ResultEffect.Error
                val message = if (effect.error.code == "h6_switch_failed") R.string.h6_switch_failed else container.errorMessageMapper.messageRes(effect.error)
                MaterialAlertDialogBuilder(android.view.ContextThemeWrapper(requireContext(), R.style.Theme_Soundly_EnhancementProgress)).setMessage(message).setPositiveButton(android.R.string.ok, null).show()
                model.acknowledgeEffect(entry.id)
            }
        } }
    }
    override fun onStart() { super.onStart(); model.setActive(true) }
    override fun onStop() { model.setActive(false); super.onStop() }
}
