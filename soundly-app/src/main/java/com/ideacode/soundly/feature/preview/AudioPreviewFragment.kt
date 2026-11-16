package com.ideacode.soundly.feature.preview

import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import androidx.activity.OnBackPressedCallback
import androidx.core.os.bundleOf
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ideacode.soundly.R
import com.ideacode.soundly.app.SoundlyApplication
import com.ideacode.soundly.common.ui.addTopAppBarMenuProvider
import com.ideacode.soundly.common.ui.setTopAppBarTitle
import com.ideacode.soundly.databinding.FragmentAudioPreviewBinding
import com.ideacode.soundly.databinding.LayoutImportedPreviewBinding
import com.ideacode.soundly.feature.enhancement.StartEnhancementUseCase
import com.ideacode.soundly_sdk.api.model.domain.AudioSourceType
import com.ideacode.soundly_sdk.api.capability.TrimCapabilities
import kotlinx.coroutines.launch

class AudioPreviewFragment : Fragment(R.layout.fragment_audio_preview), RenameProjectHost {
    internal val model: AudioPreviewViewModel by viewModels {
        val container = (requireActivity().application as SoundlyApplication).appContainer
        object : AbstractSavedStateViewModelFactory(this, arguments) {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(key: String, modelClass: Class<T>, handle: SavedStateHandle): T =
                AudioPreviewViewModel(handle, container.projectGateway, StartEnhancementUseCase(container.editingGateway)) as T
        }
    }
    override val renameModel: AudioPreviewViewModel get() = model

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val b = FragmentAudioPreviewBinding.bind(view)
        val previewEntry = findNavController().currentBackStackEntry
        previewEntry?.savedStateHandle?.getLiveData<String>("trim_result_variant")
            ?.observe(viewLifecycleOwner) { id ->
                if (!id.isNullOrBlank()) {
                    model.selectVariant(id)
                    previewEntry.savedStateHandle.remove<String>("trim_result_variant")
                }
            }
        val renderer = AudioPreviewRenderer(b) { model.dispatch(PreviewAction.Seek(it)) }
        var importedRenderer: ImportedPreviewRenderer? = null
        var importedBinding: LayoutImportedPreviewBinding? = null
        val guard = object : OnBackPressedCallback(false) { override fun handleOnBackPressed() = Unit }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, guard)
        b.previewPlayer.onToggle = { model.dispatch(PreviewAction.Toggle) }
        b.previewPlayer.onSeek = { model.dispatch(PreviewAction.Seek(it)) }
        b.previewRename.setOnClickListener {
            if (childFragmentManager.findFragmentByTag("rename") == null) RenameProjectDialog().show(childFragmentManager, "rename")
        }
        b.previewEnhance.setOnClickListener { model.dispatch(PreviewAction.Enhance) }
        b.previewAdjust.setOnClickListener { navigateWithSource(R.id.enhancementSetupFragment) }
        b.previewSkip.setOnClickListener {
            model.uiState.value.project?.let { findNavController().navigate(R.id.projectDetailFragment, bundleOf("projectId" to it.id)) }
        }
        addTopAppBarMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, inflater: MenuInflater) { inflater.inflate(R.menu.preview_actions, menu) }
            override fun onMenuItemSelected(item: MenuItem): Boolean = when (item.itemId) {
                R.id.preview_menu_trim -> { openTrim(); true }
                R.id.preview_menu_export -> { navigateWithSource(R.id.exportFragment); true }
                else -> false
            }
        })
        viewLifecycleOwner.lifecycleScope.launch { viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            model.uiState.collect { state ->
                guard.isEnabled = state.starting
                state.variant?.id?.let { id ->
                    previewEntry?.savedStateHandle?.set("current_variant_id", id)
                }
                if (state.project?.sourceType == AudioSourceType.IMPORTED && !state.missing) {
                    setTopAppBarTitle(R.id.audioPreviewFragment, R.string.imported_preview_title)
                    b.previewLoading.isVisible = state.loading
                    b.previewError.isVisible = false
                    if (importedRenderer == null) {
                        (b.previewContent.parent as? ViewGroup)?.removeView(b.previewContent)
                        val imported = LayoutImportedPreviewBinding.bind(requireNotNull(view.findViewById<ViewStub>(R.id.imported_preview_stub)).inflate())
                        imported.importedPlayer.onToggle = { model.dispatch(PreviewAction.Toggle) }
                        imported.importedPlayer.onSeek = { model.dispatch(PreviewAction.Seek(it)) }
                        imported.importedRename.setOnClickListener { if (childFragmentManager.findFragmentByTag("rename") == null) RenameProjectDialog().show(childFragmentManager, "rename") }
                        imported.importedWaveStatus.setOnClickListener { if (model.uiState.value.waveformError) model.dispatch(PreviewAction.RetryWaveform) }
                        imported.importedEnhance.setOnClickListener { navigateWithSource(R.id.enhancementSetupFragment) }
                        imported.importedTrim.setOnClickListener { openTrim() }
                        imported.importedExport.setOnClickListener { navigateWithSource(R.id.exportFragment) }
                        imported.importedLibrary.setOnClickListener { openLibrary() }
                        importedBinding = imported
                        importedRenderer = ImportedPreviewRenderer(imported)
                    }
                    importedBinding?.root?.isVisible = true
                    importedRenderer?.render(state)
                } else {
                    if (importedRenderer != null) { importedBinding?.root?.isVisible = false; b.previewLoading.isVisible = state.loading; b.previewError.isVisible = state.missing }
                    else renderer.render(state)
                }
            }
        } }
        viewLifecycleOwner.lifecycleScope.launch { viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.effects.collect { entries ->
                val entry = entries.firstOrNull() ?: return@collect
                if (findNavController().currentDestination?.id != R.id.audioPreviewFragment) return@collect
                when (val effect = entry.value) {
                    is PreviewEffect.Error -> MaterialAlertDialogBuilder(requireContext())
                        .setMessage((requireActivity().application as SoundlyApplication).appContainer.errorMessageMapper.messageRes(effect.error))
                        .setPositiveButton(android.R.string.ok, null).show()
                    PreviewEffect.Renamed -> (childFragmentManager.findFragmentByTag("rename") as? RenameProjectDialog)?.dismissAllowingStateLoss()
                    is PreviewEffect.EnhancementStarted -> {
                        findNavController().navigate(R.id.enhancementProgressFragment, bundleOf("taskId" to effect.taskId, "projectId" to model.uiState.value.project?.id.orEmpty(), "sourceVariantId" to model.uiState.value.variant?.id.orEmpty()))
                        model.navigationHandled()
                    }
                }
                model.acknowledgeEffect(entry.id)
            }
        } }
    }
    override fun onStart() { super.onStart(); model.setActive(true) }
    override fun onStop() { model.setActive(false); super.onStop() }

    private fun navigateWithSource(destination: Int) {
        val s = model.uiState.value
        val project = s.project ?: return
        val variant = s.variant ?: return
        if (s.starting) return
        findNavController().navigate(destination, bundleOf("projectId" to project.id, "variantId" to variant.id, "durationMs" to variant.durationMs))
    }

    private fun openTrim() {
        val variant = model.uiState.value.variant ?: return
        if (!TrimCapabilities.supports(variant.format)) {
            MaterialAlertDialogBuilder(requireContext()).setMessage(R.string.imported_trim_unsupported)
                .setPositiveButton(android.R.string.ok, null).show()
        } else navigateWithSource(R.id.trimFragment)
    }

    private fun openLibrary() {
        if (model.uiState.value.starting) return
        val nav = findNavController()
        if (!nav.popBackStack(R.id.libraryFragment, false)) nav.navigate(R.id.libraryFragment, null,
            androidx.navigation.navOptions { popUpTo(R.id.homeFragment) { inclusive = false }; launchSingleTop = true })
    }
}
