package com.ideacode.soundly.feature.preview

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ideacode.soundly.R
import com.ideacode.soundly.app.SoundlyApplication
import com.ideacode.soundly.common.error.AppResult
import com.ideacode.soundly.databinding.FragmentAudioPreviewBinding
import com.ideacode.soundly_sdk.api.model.domain.AudioProject
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.Locale

/** F15/F16. The M3 editing and enhancement destinations extend this page. */
class AudioPreviewFragment : Fragment(R.layout.fragment_audio_preview) {
    private lateinit var projectId: String
    private var project: AudioProject? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        projectId = requireArguments().getString("projectId").orEmpty()
        val binding = FragmentAudioPreviewBinding.bind(view)
        val gateway = (requireActivity().application as SoundlyApplication).appContainer.projectGateway

        binding.previewPlay.setOnClickListener {
            val current = project ?: return@setOnClickListener
            viewLifecycleOwner.lifecycleScope.launch {
                when (gateway.preparePlayback(current.original)) {
                    is AppResult.Success -> gateway.play()
                    is AppResult.Failure -> showOperationError()
                }
            }
        }
        binding.previewRename.setOnClickListener { showRenameDialog() }
        // M3 supplies F18; keeping this disabled prevents a non-functional route.
        binding.previewEnhance.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                gateway.observeProject(projectId).collect { item ->
                    project = item
                    if (item != null) render(binding, item)
                }
            }
        }
    }

    private fun render(binding: FragmentAudioPreviewBinding, item: AudioProject) = with(binding) {
        previewName.text = item.title
        previewInfo.text = "${item.original.format.name} · ${formatDuration(item.original.durationMs)} · ${item.markers.size}"
    }

    private fun showRenameDialog() {
        val current = project ?: return
        val input = com.google.android.material.textfield.TextInputEditText(requireContext()).apply {
            setText(current.title)
            selectAll()
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.rename_title)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val title = input.text?.toString()?.trim().orEmpty()
                if (title.isNotEmpty()) {
                    val gateway = (requireActivity().application as SoundlyApplication).appContainer.projectGateway
                    viewLifecycleOwner.lifecycleScope.launch { gateway.renameProject(projectId, title) }
                }
            }
            .show()
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
