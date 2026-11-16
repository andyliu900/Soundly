package com.ideacode.soundly.feature.project

import android.os.Bundle
import android.view.View
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.core.view.MenuProvider
import android.widget.TextView
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.appcompat.widget.AppCompatRadioButton
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.*
import androidx.navigation.fragment.findNavController
import androidx.navigation.navOptions
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.button.MaterialButton
import com.ideacode.soundly.R
import com.ideacode.soundly.app.SoundlyApplication
import com.ideacode.soundly.common.error.AppResult
import com.ideacode.soundly.common.ui.AudioVersionLabels
import com.ideacode.soundly.common.ui.addTopAppBarMenuProvider
import com.ideacode.soundly.common.ui.DisplayFormat
import com.ideacode.soundly.common.ui.audio.AudioPlayerUiState
import com.ideacode.soundly.databinding.FragmentProjectDetailBinding
import com.ideacode.soundly.databinding.DialogDeleteProjectBinding
import com.ideacode.soundly.databinding.ItemPreviewMarkerBinding
import com.ideacode.soundly.feature.home.SceneLabels
import com.ideacode.soundly.feature.enhancement.StartEnhancementUseCase
import com.ideacode.soundly.feature.preview.*
import com.ideacode.soundly_sdk.api.capability.TrimCapabilities
import com.ideacode.soundly_sdk.api.model.domain.AudioProject
import com.ideacode.soundly_sdk.api.model.domain.AudioVariant
import com.ideacode.soundly_sdk.api.model.domain.VariantType
import com.ideacode.soundly_sdk.api.model.audio.dsp.PresetOption
import com.ideacode.soundly_sdk.api.model.playback.PlaybackState
import kotlinx.coroutines.launch

class ProjectDetailFragment : Fragment(R.layout.fragment_project_detail), RenameProjectHost {
    private val container get() = (requireActivity().application as SoundlyApplication).appContainer
    override val renameModel: AudioPreviewViewModel by viewModels {
        object : AbstractSavedStateViewModelFactory(this, arguments) {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(key: String, modelClass: Class<T>, handle: SavedStateHandle): T =
                AudioPreviewViewModel(handle, container.projectGateway, StartEnhancementUseCase(container.editingGateway)) as T
        }
    }
    private val deletionModel: ProjectDeletionViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ProjectDeletionViewModel(requireArguments().getString("projectId").orEmpty(), container.projectGateway::deleteProject) as T
        }
    }
    private var renderedVersions: List<AudioVariant> = emptyList()
    private var renderedProject: AudioProject? = null
    private var changingSelection = false
    private val deleting get() = deletionModel.state.value == ProjectDeletionState.RUNNING || deletionModel.state.value == ProjectDeletionState.SUCCEEDED
    private var markerKey: Pair<AudioProject, String>? = null
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val b = FragmentProjectDetailBinding.bind(view)
        val entry = findNavController().currentBackStackEntry
        entry?.savedStateHandle?.getLiveData<String>("trim_result_variant")?.observe(viewLifecycleOwner) { id ->
            if (!id.isNullOrBlank()) { renameModel.selectVariant(id); entry.savedStateHandle.remove<String>("trim_result_variant") }
        }
        b.projectPlayer.onToggle = { renameModel.dispatch(PreviewAction.Toggle) }
        b.projectPlayer.onSeek = { renameModel.dispatch(PreviewAction.Seek(it)) }
        val primary = androidx.core.content.ContextCompat.getColor(requireContext(), R.color.preview_success)
        b.projectPlayer.setAccentColors(primary, androidx.core.content.ContextCompat.getColor(requireContext(), R.color.preview_track))
        b.projectWaveform.setColors(primary, androidx.core.content.ContextCompat.getColor(requireContext(), R.color.imported_success_border))
        b.projectWaveform.setBarStyle(3f, 7f, true)
        b.projectWaveError.setOnClickListener { renameModel.dispatch(PreviewAction.RetryWaveform) }
        b.projectRename.setOnClickListener { if (childFragmentManager.findFragmentByTag("rename") == null) RenameProjectDialog().show(childFragmentManager, "rename") }
        b.projectTrim.setOnClickListener {
            val v = renameModel.uiState.value.variant ?: return@setOnClickListener
            if (TrimCapabilities.supports(v.format)) navigateWithSource(R.id.trimFragment)
            else MaterialAlertDialogBuilder(requireContext()).setMessage(R.string.imported_trim_unsupported).setPositiveButton(android.R.string.ok, null).show()
        }
        b.projectEnhance.setOnClickListener { navigateWithSource(R.id.enhancementSetupFragment, true) }
        b.projectExport.setOnClickListener { navigateWithSource(R.id.exportFragment) }
        addTopAppBarMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, inflater: MenuInflater) { inflater.inflate(R.menu.project_actions, menu) }
            override fun onMenuItemSelected(item: MenuItem): Boolean = when (item.itemId) {
                R.id.project_menu_delete -> { confirmDelete(); true }
                R.id.project_menu_open_preview -> { navigateWithSource(R.id.audioPreviewFragment); true }
                else -> false
            }
        })
        viewLifecycleOwner.lifecycleScope.launch { viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            renameModel.uiState.collect { s ->
                if (s.missing) { if (!deleting && findNavController().currentDestination?.id == R.id.projectDetailFragment) findNavController().popBackStack(); return@collect }
                val project = s.project ?: return@collect
                val variant = s.variant ?: return@collect
                entry?.savedStateHandle?.set("current_variant_id", variant.id)
                b.projectTitle.text = project.title
                val date = DateUtils.formatDateTime(requireContext(), project.createdAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)
                b.projectMetadata.text = project.scene?.let { getString(R.string.detail_date_separator, getString(SceneLabels.name(it)), date) } ?: date
                b.projectSummary.text = getString(R.string.s2_summary, DisplayFormat.durationMillis(project.original.durationMs), project.variants.size + 1, project.markers.size)
                b.projectCurrentVersion.text = versionName(project, variant)
                val versions = AudioVersionLabels.versions(project)
                if (renderedProject != project || renderedVersions != versions) {
                    renderedProject = project; renderedVersions = versions
                    changingSelection = true
                    b.projectVersions.removeAllViews()
                    versions.forEach { item ->
                        b.projectVersions.addView(versionRow(project, item))
                    }
                    b.projectVersions.setOnCheckedChangeListener { _, id ->
                        if (!changingSelection) {
                            val selected = b.projectVersions.findViewById<View>(id)
                            renderedVersions.getOrNull(b.projectVersions.indexOfChild(selected))?.let { renameModel.selectVariant(it.id) }
                        }
                    }
                    changingSelection = false
                }
                val index = versions.indexOfFirst { it.id == variant.id }
                val selected = b.projectVersions.getChildAt(index)
                if (selected != null && b.projectVersions.checkedRadioButtonId != selected.id) { changingSelection = true; b.projectVersions.check(selected.id); changingSelection = false }
                b.projectWaveform.setSamples(s.waveform)
                b.projectWaveError.isVisible = s.waveformError
                val pos = when (val p = s.playback) { is PlaybackState.Playing -> p.positionMs; is PlaybackState.Paused -> p.positionMs; PlaybackState.Completed -> variant.durationMs; else -> 0L }
                b.projectWaveform.setProgress(if (variant.durationMs > 0) pos.toFloat() / variant.durationMs else 0f)
                b.projectPlayer.render(AudioPlayerUiState(variant.durationMs, pos, s.playback is PlaybackState.Playing, !s.preparing && !deleting))
                val key = project to variant.id
                if (markerKey != key) { markerKey = key; renderMarkers(b, project, variant) }
            }
        } }
        viewLifecycleOwner.lifecycleScope.launch { viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            deletionModel.state.collect { state ->
                when (state) {
                    ProjectDeletionState.RUNNING -> renameModel.setActive(false)
                    ProjectDeletionState.SUCCEEDED -> {
                        val nav = findNavController()
                        if (nav.currentDestination?.id == R.id.projectDetailFragment && !nav.popBackStack(R.id.libraryFragment, false))
                            nav.navigate(R.id.libraryFragment, null, navOptions { popUpTo(R.id.homeFragment) { inclusive = false }; launchSingleTop = true })
                    }
                    ProjectDeletionState.FAILED -> {
                        deletionModel.acknowledgeFailure(); renameModel.setActive(true)
                        MaterialAlertDialogBuilder(requireContext()).setMessage(R.string.error_operation_failed).setPositiveButton(android.R.string.ok, null).show()
                    }
                    ProjectDeletionState.IDLE -> Unit
                }
            }
        } }
        viewLifecycleOwner.lifecycleScope.launch { viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            renameModel.effects.collect { effects ->
                val effect = effects.firstOrNull() ?: return@collect
                if (findNavController().currentDestination?.id != R.id.projectDetailFragment) return@collect
                when (val value = effect.value) {
                    PreviewEffect.Renamed -> (childFragmentManager.findFragmentByTag("rename") as? RenameProjectDialog)?.dismissAllowingStateLoss()
                    is PreviewEffect.Error -> MaterialAlertDialogBuilder(requireContext()).setMessage(container.errorMessageMapper.messageRes(value.error)).setPositiveButton(android.R.string.ok, null).show()
                    is PreviewEffect.EnhancementStarted -> Unit
                }
                renameModel.acknowledgeEffect(effect.id)
            }
        } }
    }
    private fun versionName(project: AudioProject, variant: AudioVariant): String = when (variant.variantType) {
        VariantType.ENHANCED -> when (variant.presetOption) {
            PresetOption.AI -> getString(R.string.enhancement_mode_auto)
            PresetOption.SPEECH_FOCUS -> getString(R.string.enhancement_mode_speech)
            PresetOption.DEFAULT -> getString(R.string.enhancement_mode_noise)
            PresetOption.NATURAL_SMOOTH -> getString(R.string.enhancement_mode_natural)
            else -> variant.presetOption.title
        }
        else -> AudioVersionLabels.type(requireContext(), project, variant)
    }
    private fun versionRow(project: AudioProject, variant: AudioVariant): AppCompatRadioButton {
        val context = requireContext(); val density = resources.displayMetrics.density
        val enhanced = variant.variantType == VariantType.ENHANCED
        val title = versionName(project, variant)
        val detail = when (variant.variantType) {
            VariantType.ENHANCED -> getString(R.string.detail_enhanced_metadata, variant.format.name)
            VariantType.TRIMMED -> getString(R.string.detail_trimmed_metadata, variant.format.name)
            VariantType.ORIGINAL -> getString(R.string.detail_variant_metadata, variant.format.name, if (variant.fileSizeBytes > 0) Formatter.formatShortFileSize(context, variant.fileSizeBytes) else getString(R.string.detail_unknown_size))
        }
        val label = SpannableString("$title\n$detail").apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, title.length, 0)
            setSpan(RelativeSizeSpan(.82f), title.length + 1, length, 0)
            setSpan(ForegroundColorSpan(androidx.core.content.ContextCompat.getColor(context, R.color.preview_secondary)), title.length + 1, length, 0)
        }
        val circle = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(androidx.core.content.ContextCompat.getColor(context, if (enhanced) R.color.library_icon_enhanced_bg else R.color.library_icon_bg)) }
        val wave = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_soundly_wave)!!.mutate().apply { setTint(androidx.core.content.ContextCompat.getColor(context, if (enhanced) R.color.library_icon_enhanced else R.color.preview_primary)) }
        val icon = LayerDrawable(arrayOf(circle, InsetDrawable(wave, (11 * density).toInt()))).apply { setBounds(0, 0, (48 * density).toInt(), (48 * density).toInt()) }
        return AppCompatRadioButton(context).apply {
            id = View.generateViewId(); text = label; textSize = 16f
            layoutParams = android.widget.RadioGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            contentDescription = AudioVersionLabels.details(context, project, variant)
            layoutDirection = View.LAYOUT_DIRECTION_RTL; textDirection = View.TEXT_DIRECTION_LOCALE
            gravity = android.view.Gravity.CENTER_VERTICAL
            minHeight = (72 * density).toInt()
            setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
            setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.preview_text))
            setButtonDrawable(R.drawable.detail_radio_inset); buttonTintList = null
            setCompoundDrawables(icon, null, null, null); compoundDrawablePadding = (14 * density).toInt()
            setBackgroundResource(R.drawable.bg_detail_version_row)
        }
    }
    private fun renderMarkers(b: FragmentProjectDetailBinding, project: AudioProject, variant: AudioVariant) {
        b.projectMarkers.removeAllViews()
        val versions = (listOf(project.original) + project.variants).associateBy { it.id }
        var current: AudioVariant? = variant; var offset = 0L; val seen = mutableSetOf<String>(); var valid = true
        while (current != null && current.variantType != VariantType.ORIGINAL) {
            val item = current
            if (!seen.add(item.id)) { valid = false; break }
            if (item.variantType == VariantType.TRIMMED) offset += item.trimStartMs ?: 0L
            current = item.sourceVariantId?.let(versions::get)
            if (current == null) valid = false
        }
        val markers = if (valid) project.markers.filter { it.positionMs >= offset && it.positionMs - offset < variant.durationMs }.sortedBy { it.positionMs } else emptyList()
        b.projectMarkerCount.text = getString(R.string.detail_marker_count, markers.size)
        if (markers.isEmpty()) b.projectMarkers.addView(TextView(requireContext()).apply { setText(R.string.project_no_markers); setPadding(24, 20, 24, 20) })
        markers.forEach { marker ->
            val target = marker.positionMs - offset
            val row = ItemPreviewMarkerBinding.inflate(layoutInflater, b.projectMarkers, false)
            row.markerName.text = marker.label.orEmpty().ifBlank { getString(R.string.h4_marker) }
            row.markerTime.text = DisplayFormat.durationMillis(target)
            row.root.setOnClickListener { renameModel.dispatch(PreviewAction.Seek(target)) }
            b.projectMarkers.addView(row.root)
        }
    }
    private fun navigateWithSource(destination: Int, detailOrigin: Boolean = false) {
        val s = renameModel.uiState.value; val project = s.project ?: return; val variant = s.variant ?: return
        if (s.preparing || deleting) return
        findNavController().navigate(destination, bundleOf("projectId" to project.id, "variantId" to variant.id, "durationMs" to variant.durationMs, "sourceDestination" to if (detailOrigin) "project_detail" else ""))
    }
    internal fun deleteProject() {
        if (deleting) return
        renameModel.setActive(false)
        deletionModel.delete()
    }
    private fun confirmDelete() {
        if (!deleting && childFragmentManager.findFragmentByTag("delete_project") == null)
            DeleteProjectDialog().show(childFragmentManager, "delete_project")
    }
    override fun onStart() { super.onStart(); renameModel.setActive(!deleting) }
    override fun onStop() { renameModel.setActive(false); super.onStop() }
    override fun onDestroyView() { markerKey = null; renderedProject = null; renderedVersions = emptyList(); super.onDestroyView() }
}

class DeleteProjectDialog : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): android.app.Dialog {
        val host = requireParentFragment() as ProjectDetailFragment
        val binding = DialogDeleteProjectBinding.inflate(layoutInflater)
        host.renameModel.uiState.value.project?.let { project ->
            binding.deleteProjectSummary.text = getString(R.string.s2_delete_summary, project.title,
                DisplayFormat.durationMillis(project.original.durationMs), project.variants.size + 1, project.markers.size)
        }
        binding.deleteKeep.setOnClickListener { dismiss() }
        binding.deleteConfirm.setOnClickListener { host.deleteProject(); dismiss() }
        return MaterialAlertDialogBuilder(requireContext()).setView(binding.root).create()
    }
}
