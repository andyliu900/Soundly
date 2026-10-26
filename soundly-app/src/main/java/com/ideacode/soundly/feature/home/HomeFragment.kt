package com.ideacode.soundly.feature.home

import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ideacode.soundly.R
import com.ideacode.soundly.app.SoundlyApplication
import com.ideacode.soundly.databinding.FragmentHomeBinding
import kotlinx.coroutines.launch

/** F03/F04 low-fidelity functional home. List UI is introduced with F26/F27 in M4. */
class HomeFragment : Fragment(R.layout.fragment_home) {
    private val importAudioLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val container = (requireActivity().application as SoundlyApplication).appContainer
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = container.editingGateway.importAudio(uri)) {
                is com.ideacode.soundly.common.error.AppResult.Success -> findNavController().navigate(
                    R.id.audioPreviewFragment,
                    Bundle().apply { putString("projectId", result.value) }
                )
                is com.ideacode.soundly.common.error.AppResult.Failure -> MaterialAlertDialogBuilder(requireContext())
                    .setMessage(R.string.error_operation_failed)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentHomeBinding.bind(view)
        val gateway = (requireActivity().application as SoundlyApplication).appContainer.projectGateway
        binding.homeNewRecording.setOnClickListener {
            findNavController().navigate(R.id.prepareRecordingFragment)
        }
        binding.homeImportAudio.setOnClickListener {
            importAudioLauncher.launch(arrayOf("audio/*"))
        }
        binding.homeOpenLibrary.setOnClickListener {
            findNavController().navigate(R.id.libraryFragment)
        }
        binding.homeOpenSettings.setOnClickListener {
            findNavController().navigate(R.id.settingsFragment)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                gateway.observeProjects().collect { projects ->
                    binding.homeRecent.text = if (projects.isEmpty()) {
                        getString(R.string.home_no_recordings)
                    } else {
                        projects.take(3).joinToString(separator = "\n") { it.title }
                    }
                }
            }
        }
    }
}
