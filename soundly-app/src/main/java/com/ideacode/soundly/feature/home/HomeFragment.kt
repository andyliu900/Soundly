package com.ideacode.soundly.feature.home

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.ideacode.soundly.R
import com.ideacode.soundly.app.SoundlyApplication
import com.ideacode.soundly.databinding.FragmentHomeBinding
import kotlinx.coroutines.launch

/** F03/F04 low-fidelity functional home. List UI is introduced with F26/F27 in M4. */
class HomeFragment : Fragment(R.layout.fragment_home) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentHomeBinding.bind(view)
        val gateway = (requireActivity().application as SoundlyApplication).appContainer.projectGateway
        binding.homeNewRecording.setOnClickListener {
            findNavController().navigate(R.id.prepareRecordingFragment)
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
