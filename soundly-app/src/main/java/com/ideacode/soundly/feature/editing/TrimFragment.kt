package com.ideacode.soundly.feature.editing

import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
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
import androidx.navigation.navOptions
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.ideacode.soundly.R
import com.ideacode.soundly.app.SoundlyApplication
import com.ideacode.soundly.common.ui.DisplayFormat
import com.ideacode.soundly.databinding.FragmentTrimBinding
import com.ideacode.soundly_sdk.api.model.domain.VariantType
import com.ideacode.soundly_sdk.api.capability.TrimCapabilities
import kotlinx.coroutines.launch

class TrimFragment : Fragment(R.layout.fragment_trim) {
    private val container get() = (requireActivity().application as SoundlyApplication).appContainer
    private val model: TrimViewModel by viewModels {
        object : AbstractSavedStateViewModelFactory(this, arguments) {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(key: String, modelClass: Class<T>, handle: SavedStateHandle): T =
                TrimViewModel(handle, container.projectGateway, container.editingGateway,
                    AndroidTrimJournal(requireContext(), handle.get<String>("projectId").orEmpty(), handle.get<String>("variantId").orEmpty())) as T
        }
    }
    private var rendering = false
    private var navigating = false
    private var uncertainDialog: android.app.Dialog? = null
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentTrimBinding.bind(view)
        val guard = object : OnBackPressedCallback(false) { override fun handleOnBackPressed() = Unit }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, guard)
        binding.trimWaveform.setBarStyle(3f, 6f)
        binding.trimWaveform.setColors(ContextCompat.getColor(requireContext(), R.color.preview_primary), ContextCompat.getColor(requireContext(), R.color.preview_wave))
        binding.trimRange.setValues(0f, 10000f)
        binding.trimRange.setLabelFormatter { value ->
            val duration = model.state.value.variant?.durationMs ?: 0L
            TrimTime.format((duration.toDouble() * value / 10000.0).toLong())
        }
        binding.trimRange.post {
            val inset = binding.trimRange.trackSidePadding
            binding.trimRangeOverlay.setTrackInset(inset)
            val params = binding.trimWaveform.layoutParams as android.widget.FrameLayout.LayoutParams
            params.leftMargin = inset; params.rightMargin = inset
            binding.trimWaveform.layoutParams = params
        }
        binding.trimRange.addOnChangeListener { slider, _, fromUser ->
            if (!rendering && fromUser) {
                val duration = model.state.value.variant?.durationMs ?: return@addOnChangeListener
                val values = slider.values
                model.setRange((duration.toDouble() * values[0] / 10000.0).toLong(), (duration.toDouble() * values[1] / 10000.0).toLong())
            }
        }
        binding.trimStart.setOnClickListener { editTime(start = true) }
        binding.trimEnd.setOnClickListener { editTime(start = false) }
        binding.trimPlay.setOnClickListener { model.togglePreview() }
        binding.trimApply.setOnClickListener { model.apply() }
        binding.trimCancel.setOnClickListener { if (!model.state.value.busy) findNavController().navigateUp() }
        binding.trimWaveStatus.setOnClickListener { if (model.state.value.waveformError) model.loadWaveform() }
        viewLifecycleOwner.lifecycleScope.launch { viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            model.state.collect { state ->
                guard.isEnabled = state.busy || state.restoring || navigating
                render(binding, state)
                if (state.uncertain && !state.busy && uncertainDialog?.isShowing != true) showUncertain()
            }
        } }
        viewLifecycleOwner.lifecycleScope.launch { viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.state.collect { state ->
                val id = state.resultId ?: return@collect
                val nav = findNavController()
                if (navigating || nav.currentDestination?.id != R.id.trimFragment) return@collect
                navigating = true
                val project = requireArguments().getString("projectId").orEmpty()
                val previous = nav.previousBackStackEntry
                val returnToPreview = previous?.destination?.id in setOf(R.id.audioPreviewFragment, R.id.projectDetailFragment) && previous?.arguments?.getString("projectId") == project
                if (returnToPreview) previous?.savedStateHandle?.set("trim_result_variant", id)
                viewLifecycleOwner.lifecycleScope.launch {
                    try { model.resultDelivered() } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel } catch (_: Exception) { /* retain journal for later verification */ }
                    if (nav.currentDestination?.id == R.id.trimFragment) {
                        if (returnToPreview) nav.popBackStack()
                        else nav.navigate(R.id.audioPreviewFragment, bundleOf("projectId" to project, "variantId" to id), navOptions { popUpTo(R.id.trimFragment) { inclusive = true } })
                    }
                }
            }
        } }
    }

    private fun render(b: FragmentTrimBinding, s: TrimUiState) {
        b.trimLoading.isVisible = s.loading || s.restoring
        b.trimMissing.isVisible = !s.loading && s.missing
        b.trimContent.isVisible = !s.loading && !s.restoring && !s.missing && s.project != null && s.variant != null
        val project = s.project ?: return
        val variant = s.variant ?: return
        b.trimProject.text = project.title
        val type = when (variant.variantType) { VariantType.ORIGINAL -> R.string.i3_original; VariantType.TRIMMED -> R.string.i3_trimmed; VariantType.ENHANCED -> R.string.i3_enhanced }
        b.trimSource.text = getString(R.string.i3_source, getString(type), variant.format.name, DisplayFormat.durationMillis(variant.durationMs))
        b.trimTotal.text = DisplayFormat.durationMillis(variant.durationMs)
        b.trimStart.text = getString(R.string.i3_start_value, TrimTime.format(s.startMs))
        b.trimEnd.text = getString(R.string.i3_end_value, TrimTime.format(s.endMs))
        b.trimRetained.text = getString(R.string.i3_retained, TrimTime.format((s.endMs - s.startMs).coerceAtLeast(0)))
        b.trimWaveform.setSamples(s.waveform)
        val selectionDuration = variant.durationMs.coerceAtLeast(1).toFloat()
        b.trimWaveform.setSelection(s.startMs / selectionDuration, s.endMs / selectionDuration)
        b.trimRangeOverlay.setSelection(s.startMs, s.endMs, variant.durationMs)
        rendering = true
        try {
            val duration = variant.durationMs.coerceAtLeast(1).toDouble()
            val start = (s.startMs / duration * 10000).toFloat().coerceIn(0f, 10000f)
            val end = (s.endMs / duration * 10000).toFloat().coerceIn(start, 10000f)
            if (b.trimRange.values != listOf(start, end)) b.trimRange.setValues(start, end)
        } finally { rendering = false }
        val enabled = !s.busy && !s.restoring && !s.uncertain
        b.trimRange.isEnabled = enabled
        b.trimStart.isEnabled = enabled; b.trimEnd.isEnabled = enabled
        b.trimPlay.isEnabled = enabled && s.valid && !s.previewPreparing
        b.trimPlay.setIconResource(if (s.playing) R.drawable.ic_h4_pause else R.drawable.ic_h4_play)
        b.trimPlay.contentDescription = getString(if (s.playing) R.string.i3_pause else R.string.i3_preview)
        b.trimApply.isEnabled = enabled && s.valid && s.resultId == null && !s.previewPreparing
        b.trimApply.setText(if (s.busy) R.string.i3_applying else R.string.trim_apply)
        b.trimCancel.isEnabled = !s.busy && !s.restoring
        b.trimWaveStatus.isVisible = s.waveformLoading || s.waveformError
        b.trimWaveStatus.setText(if (s.waveformLoading) R.string.i3_wave_loading else R.string.i3_wave_retry)
        b.trimWaveStatus.isEnabled = s.waveformError && !s.waveformLoading
        b.trimError.isVisible = s.error != null || !s.valid
        b.trimError.text = s.error?.let { getString(container.errorMessageMapper.messageRes(it)) }
            ?: getString(if (!TrimCapabilities.supports(variant.format)) R.string.imported_trim_unsupported else R.string.i3_invalid_range)
    }

    private fun editTime(start: Boolean) {
        val state = model.state.value
        val variant = state.variant ?: return
        val layout = TextInputLayout(requireContext()).apply { setPadding(24, 8, 24, 0); hint = getString(R.string.i3_time_hint) }
        val input = TextInputEditText(requireContext()).apply {
            id = R.id.trim_time_input
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setText(TrimTime.format(if (start) state.startMs else state.endMs))
            setSelection(length())
        }
        layout.addView(input)
        val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle(if (start) R.string.trim_start else R.string.trim_end)
            .setView(layout).setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok, null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = TrimTime.parse(input.text?.toString().orEmpty())
                val current = model.state.value
                if (value == null || value > variant.durationMs || (start && value >= current.endMs) || (!start && value <= current.startMs)) {
                    layout.error = getString(R.string.i3_time_invalid)
                } else { model.setRange(if (start) value else current.startMs, if (start) current.endMs else value); dialog.dismiss() }
            }
        }
        dialog.show()
    }

    private fun showUncertain() {
        uncertainDialog = MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.i3_uncertain_title).setMessage(R.string.i3_uncertain_message)
            .setCancelable(false).setPositiveButton(R.string.i3_check_library) { _, _ ->
                val nav = findNavController()
                if (!nav.popBackStack(R.id.libraryFragment, false)) nav.navigate(R.id.libraryFragment, null, navOptions { popUpTo(R.id.homeFragment) { inclusive = false } })
            }.setNegativeButton(R.string.i3_continue_editing) { _, _ ->
                MaterialAlertDialogBuilder(requireContext()).setMessage(R.string.i3_continue_warning)
                    .setNegativeButton(android.R.string.cancel) { _, _ -> uncertainDialog = null; showUncertain() }
                    .setPositiveButton(R.string.i3_continue_confirm) { _, _ -> model.acknowledgeUncertain() }.show()
            }.show()
    }
    override fun onStart() { super.onStart(); model.setActive(true) }
    override fun onStop() { model.setActive(false); super.onStop() }
    override fun onDestroyView() { uncertainDialog?.dismiss(); uncertainDialog = null; navigating = false; super.onDestroyView() }
}
