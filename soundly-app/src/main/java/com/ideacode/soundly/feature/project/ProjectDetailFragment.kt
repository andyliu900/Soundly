package com.ideacode.soundly.feature.project

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
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
import com.ideacode.soundly.databinding.FragmentProjectDetailBinding
import com.ideacode.soundly_sdk.api.model.domain.AudioProject
import com.ideacode.soundly_sdk.api.model.domain.AudioVariant
import java.util.Locale
import kotlinx.coroutines.launch

/** F28/F29: inspect a project's versions and markers, play a version, or delete it. */
class ProjectDetailFragment : Fragment(R.layout.fragment_project_detail) {
    private lateinit var projectId: String
    private var selectedVariant: AudioVariant? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        projectId = requireArguments().getString("projectId").orEmpty()
        val binding = FragmentProjectDetailBinding.bind(view)
        val gateway = (requireActivity().application as SoundlyApplication).appContainer.projectGateway

        binding.projectPlay.setOnClickListener {
            val variant = selectedVariant ?: return@setOnClickListener
            viewLifecycleOwner.lifecycleScope.launch {
                when (gateway.preparePlayback(variant)) {
                    is AppResult.Success -> when (gateway.play()) {
                        is AppResult.Success -> Unit
                        is AppResult.Failure -> showOperationError()
                    }
                    is AppResult.Failure -> showOperationError()
                }
            }
        }
        binding.projectDelete.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.project_delete_title)
                .setMessage(R.string.project_delete_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.project_delete_confirm) { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        when (gateway.deleteProject(projectId)) {
                            is AppResult.Success -> findNavController().popBackStack(
                                R.id.libraryFragment,
                                false
                            )
                            is AppResult.Failure -> showOperationError()
                        }
                    }
                }
                .show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                gateway.observeProject(projectId).collect { project ->
                    if (project == null) {
                        findNavController().popBackStack()
                    } else {
                        render(binding, project)
                    }
                }
            }
        }
    }

    private fun render(binding: FragmentProjectDetailBinding, project: AudioProject) = with(binding) {
        projectTitle.text = project.title
        projectSummary.text = getString(
            R.string.project_duration,
            formatDuration(project.original.durationMs)
        )
        projectMarkers.text = project.markers
            .sortedBy { it.positionMs }
            .joinToString(separator = "\n") { marker ->
                "${formatDuration(marker.positionMs)} · ${marker.label.orEmpty()}".trimEnd(' ', '·')
            }
            .ifBlank { getString(R.string.project_no_markers) }

        val versions = listOf(project.original) + project.variants
        val labels = versions.map { variant ->
            getString(R.string.project_variant_item, variant.variantType.name, formatDuration(variant.durationMs))
        }
        projectVersions.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            labels
        )
        selectedVariant = versions.firstOrNull()
        projectVersions.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, itemView: View?, position: Int, id: Long) {
                selectedVariant = versions.getOrNull(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>) {
                selectedVariant = null
            }
        }
    }

    private fun showOperationError() {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.error_operation_failed)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun formatDuration(durationMs: Long): String {
        val seconds = durationMs / 1_000L
        return String.format(Locale.US, "%02d:%02d", seconds / 60L, seconds % 60L)
    }
}
