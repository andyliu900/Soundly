package com.ideacode.soundly.feature.library

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.ideacode.soundly.R
import com.ideacode.soundly.app.SoundlyApplication
import com.ideacode.soundly.databinding.FragmentLibraryBinding
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi

/** F26/F27: SDK-backed project library with live search. */
class LibraryFragment : Fragment(R.layout.fragment_library) {
    private val query = MutableStateFlow("")

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentLibraryBinding.bind(view)
        val gateway = (requireActivity().application as SoundlyApplication).appContainer.projectGateway
        val adapter = LibraryProjectAdapter { project ->
            findNavController().navigate(
                R.id.projectDetailFragment,
                Bundle().apply { putString("projectId", project.id) }
            )
        }

        binding.libraryList.layoutManager = LinearLayoutManager(requireContext())
        binding.libraryList.adapter = adapter
        binding.librarySearch.doOnTextChanged { text, _, _, _ -> query.value = text?.toString().orEmpty() }
        binding.libraryEmptyAction.setOnClickListener {
            findNavController().navigate(R.id.prepareRecordingFragment)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                query
                    .map { it.trim() }
                    .distinctUntilChanged()
                    .flatMapLatest { searchQuery ->
                        if (searchQuery.isBlank()) gateway.observeProjects()
                        else gateway.searchProjects(searchQuery)
                    }
                    .collect { projects ->
                        adapter.submitList(projects)
                        binding.libraryList.isVisible = projects.isNotEmpty()
                        binding.libraryEmpty.isVisible = projects.isEmpty()
                    }
            }
        }
    }
}
