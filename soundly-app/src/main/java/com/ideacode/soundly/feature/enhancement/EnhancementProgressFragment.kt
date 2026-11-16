package com.ideacode.soundly.feature.enhancement

import android.app.Dialog
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.core.os.bundleOf
import androidx.core.content.ContextCompat
import android.view.ContextThemeWrapper
import com.ideacode.soundly.common.error.AppResult
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.*
import androidx.navigation.fragment.findNavController
import androidx.navigation.navOptions
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ideacode.soundly.R
import com.ideacode.soundly.app.SoundlyApplication
import com.ideacode.soundly.common.ui.setTopAppBarTitle
import com.ideacode.soundly.databinding.FragmentH6EnhancementProgressBinding
import com.ideacode.soundly.databinding.DialogCancelEnhancementBinding
import com.ideacode.soundly_sdk.api.model.enhancement.EnhancementTaskState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class EnhancementProgressFragment : Fragment(R.layout.fragment_h6_enhancement_progress) {
    private val container get() = (requireActivity().application as SoundlyApplication).appContainer
    internal val model: EnhancementProgressViewModel by viewModels {
        object : AbstractSavedStateViewModelFactory(this, arguments) {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(key: String, modelClass: Class<T>, handle: SavedStateHandle): T =
                EnhancementProgressViewModel(handle, container.editingGateway) as T
        }
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val b = FragmentH6EnhancementProgressBinding.bind(view)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (model.uiState.value.task is EnhancementTaskState.Running) confirmCancel() else if (!model.uiState.value.busy) returnToSource() }
        })
        b.enhancementCancel.setOnClickListener { confirmCancel() }
        b.progressRetry.setOnClickListener { model.dispatch(ProgressAction.Retry) }
        b.progressOriginal.setOnClickListener { returnToSource() }
        b.progressChange.setOnClickListener {
            val state = model.uiState.value
            if (state.projectId.isNotBlank() && state.sourceId.isNotBlank()) findNavController().navigate(R.id.enhancementSetupFragment,
                bundleOf("projectId" to state.projectId, "variantId" to state.sourceId, "sourceDestination" to requireArguments().getString("sourceDestination").orEmpty()), navOptions { popUpTo(R.id.enhancementProgressFragment) { inclusive = true } })
        }
        viewLifecycleOwner.lifecycleScope.launch { viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            model.uiState.collect { s ->
                EnhancementProgressRenderer.render(b, s)
                val unavailable = s.unknown || s.task is EnhancementTaskState.Failed
                setTopAppBarTitle(R.id.enhancementProgressFragment, if (unavailable) R.string.h6_failed else R.string.enhancement_progress_title)
            }
        } }
        viewLifecycleOwner.lifecycleScope.launch { viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            model.uiState.map { it.projectId to it.sourceId }.distinctUntilChanged().collectLatest { (id, sourceId) ->
                if (id.isNotBlank()) container.projectGateway.observeProject(id).collectLatest { project ->
                    val variant = project?.let { (listOf(it.original) + it.variants).firstOrNull { version -> version.id == sourceId } }
                    EnhancementProgressRenderer.renderSource(b, project, variant)
                    b.progressSourceWaveform.setColors(ContextCompat.getColor(requireContext(), R.color.enhancement_progress_primary), ContextCompat.getColor(requireContext(), R.color.enhancement_progress_badge))
                    b.progressSourceWaveform.isVisible = false
                    b.progressSourceAudioIcon.isVisible = true
                    if (variant != null) {
                        val waveform = container.projectGateway.loadWaveform(id, variant.id)
                        if (waveform is AppResult.Success && waveform.value.amplitudes.isNotEmpty()) {
                            b.progressSourceWaveform.setSamples(waveform.value.amplitudes)
                            b.progressSourceWaveform.isVisible = true
                            b.progressSourceAudioIcon.isVisible = false
                        }
                    }
                }
            }
        } }
        viewLifecycleOwner.lifecycleScope.launch { viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.uiState.collect { s ->
                val nav = findNavController()
                if (nav.currentDestination?.id != R.id.enhancementProgressFragment) return@collect
                when (val state = s.task) {
                    is EnhancementTaskState.Completed -> nav.navigate(R.id.enhancementResultFragment,
                        bundleOf("projectId" to state.result.projectId, "originalVariantId" to state.task.sourceVariantId, "enhancedVariantId" to state.result.variantId, "sourceDestination" to requireArguments().getString("sourceDestination").orEmpty()),
                        navOptions { popUpTo(R.id.enhancementProgressFragment) { inclusive = true } })
                    is EnhancementTaskState.Cancelled -> returnToSource()
                    else -> Unit
                }
            }
        } }
    }
    private fun confirmCancel() {
        if (model.uiState.value.busy || model.uiState.value.task !is EnhancementTaskState.Running) return
        if (childFragmentManager.findFragmentByTag("cancel") == null) CancelEnhancementDialog().show(childFragmentManager, "cancel")
    }
    private fun returnToSource() {
        val nav = findNavController(); val s = model.uiState.value
        if (requireArguments().getString("sourceDestination") == "project_detail") {
            val detail = runCatching { nav.getBackStackEntry(R.id.projectDetailFragment) }.getOrNull()
            if (detail?.arguments?.getString("projectId") == s.projectId) {
                detail.savedStateHandle["trim_result_variant"] = s.sourceId
                if (nav.popBackStack(R.id.projectDetailFragment, false)) return
            }
            if (s.projectId.isNotBlank()) {
                nav.navigate(R.id.projectDetailFragment, bundleOf("projectId" to s.projectId, "variantId" to s.sourceId), navOptions { popUpTo(R.id.enhancementProgressFragment) { inclusive = true } })
                return
            }
        }
        val previous = runCatching { nav.getBackStackEntry(R.id.audioPreviewFragment) }.getOrNull()
        val selectedId = previous?.savedStateHandle?.get<String>("current_variant_id")
            ?: previous?.arguments?.getString("variantId")
        val matches = previous?.arguments?.getString("projectId") == s.projectId && selectedId == s.sourceId
        if (!matches || !nav.popBackStack(R.id.audioPreviewFragment, false)) {
            if (s.projectId.isNotBlank() && s.sourceId.isNotBlank()) nav.navigate(R.id.audioPreviewFragment,
                bundleOf("projectId" to s.projectId, "variantId" to s.sourceId), navOptions { popUpTo(R.id.enhancementProgressFragment) { inclusive = true } })
            else nav.popBackStack()
        }
    }
}
class CancelEnhancementDialog : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val model = (requireParentFragment() as EnhancementProgressFragment).model
        val themed = ContextThemeWrapper(requireContext(), R.style.Theme_Soundly_EnhancementProgress)
        val b = DialogCancelEnhancementBinding.inflate(android.view.LayoutInflater.from(themed))
        b.cancelContinue.setOnClickListener { dismiss() }
        b.cancelConfirm.setOnClickListener { model.dispatch(ProgressAction.Cancel); dismiss() }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) {
            model.uiState.collect { state ->
                val running = state.task as? EnhancementTaskState.Running
                if (running == null) { dismissAllowingStateLoss(); return@collect }
                val mode = EnhancementUi.mode(running.task.preset)
                b.cancelProgressDetails.text = getString(R.string.s5_cancel_details, getString(mode?.let(EnhancementUi::title) ?: R.string.h6_unknown_preset), running.progressPercent)
                b.cancelConfirm.isEnabled = !state.busy
            }
        } }
        return MaterialAlertDialogBuilder(themed).setView(b.root).create()
    }
}
