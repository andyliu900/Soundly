package com.ideacode.soundly.feature.library

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ideacode.soundly.R
import com.ideacode.soundly.app.SoundlyApplication
import com.ideacode.soundly.common.error.AppResult
import com.ideacode.soundly.databinding.FragmentLibraryBinding
import com.ideacode.soundly.feature.recording.permission.MicrophonePermissionController
import com.ideacode.soundly_sdk.api.model.domain.AudioProject
import com.ideacode.soundly_sdk.api.model.domain.AudioSourceType
import com.ideacode.soundly_sdk.api.model.domain.VariantType
import com.ideacode.soundly_sdk.api.model.playback.PlaybackState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class LibraryFragment : Fragment(R.layout.fragment_library) {
    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(R.id.library_filter_all)
    private var ownsPlayback = false
    private var playingProject: String? = null
    private var preparing = false
    private var active = false
    private val gateway get() = (requireActivity().application as SoundlyApplication).appContainer.projectGateway

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val b = FragmentLibraryBinding.bind(view)
        filter.value = savedInstanceState?.getInt("library_filter", R.id.library_filter_all) ?: R.id.library_filter_all
        b.libraryFilters.check(filter.value)
        val adapter = LibraryProjectAdapter(onPlay = { play(it) }) { project ->
            if (findNavController().currentDestination?.id == R.id.libraryFragment)
                findNavController().navigate(R.id.projectDetailFragment, Bundle().apply { putString("projectId", project.id) })
        }
        b.libraryList.layoutManager = LinearLayoutManager(requireContext()); b.libraryList.adapter = adapter
        b.librarySearch.doOnTextChanged { text, _, _, _ -> query.value = text?.toString().orEmpty() }
        b.libraryFilters.setOnCheckedStateChangeListener { _, ids -> ids.firstOrNull()?.let { filter.value = it } }
        childFragmentManager.setFragmentResultListener(MicrophonePermissionController.RESULT, viewLifecycleOwner) { _, result ->
            when (result.getString(MicrophonePermissionController.OUTCOME)) {
                MicrophonePermissionController.GRANTED, MicrophonePermissionController.SETTINGS_GRANTED -> openRecording()
                MicrophonePermissionController.IMPORT -> openImport()
            }
        }
        val startRecording = View.OnClickListener {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) openRecording()
            else MicrophonePermissionController.get(childFragmentManager).begin()
        }
        b.libraryEmptyAction.setOnClickListener(startRecording)
        b.libraryRecordFab.setOnClickListener(startRecording)
        b.libraryImportAudio.setOnClickListener { openImport() }
        b.libraryImportHeader.setOnClickListener { openImport() }
        viewLifecycleOwner.lifecycleScope.launch { viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            combine(gateway.observeProjects(), query, filter) { projects, search, selected ->
                val result = projects.filter { p ->
                    p.title.contains(search.trim(), ignoreCase = true) && when (selected) {
                        R.id.library_filter_enhanced -> p.variants.any { it.variantType == VariantType.ENHANCED }
                        R.id.library_filter_recorded -> p.sourceType == AudioSourceType.RECORDED && p.variants.none { it.variantType == VariantType.ENHANCED }
                        R.id.library_filter_imported -> p.sourceType == AudioSourceType.IMPORTED
                        else -> true
                    }
                }
                projects.isEmpty() to result
            }.collect { (empty, projects) ->
                adapter.submitList(projects)
                b.libraryList.isVisible = projects.isNotEmpty()
                b.libraryRecent.isVisible = projects.isNotEmpty()
                b.libraryEmptyContainer.isVisible = projects.isEmpty()
                b.libraryEmpty.setText(if (empty) R.string.library_empty_title else R.string.s2_no_results)
                b.libraryEmptyDescription.setText(if (empty) R.string.library_empty_description else R.string.library_no_results_description)
                b.libraryEmptyAction.isVisible = empty
                b.libraryImportAudio.isVisible = empty
                b.libraryRecordFab.isVisible = projects.isNotEmpty()
                b.libraryImportHeader.isVisible = !empty
            }
        } }
        viewLifecycleOwner.lifecycleScope.launch { viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            gateway.playbackState.collect { state -> adapter.playingProjectId = playingProject.takeIf { ownsPlayback && state is PlaybackState.Playing } }
        } }
    }
    private fun play(project: AudioProject) {
        if (preparing) return
        if (ownsPlayback && playingProject == project.id) {
            if (gateway.playbackState.value is PlaybackState.Playing) gateway.pausePlayback() else gateway.play()
            return
        }
        if (ownsPlayback) gateway.pausePlayback()
        ownsPlayback = false; playingProject = null; preparing = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                when (gateway.preparePlayback(project.original)) {
                    is AppResult.Success -> if (active) { ownsPlayback = true; playingProject = project.id; gateway.play() }
                    is AppResult.Failure -> if (active) MaterialAlertDialogBuilder(requireContext()).setMessage(R.string.error_playback_failed).setPositiveButton(android.R.string.ok, null).show()
                }
            } finally { preparing = false }
        }
    }
    private fun openRecording() { if (findNavController().currentDestination?.id == R.id.libraryFragment) findNavController().navigate(R.id.prepareRecordingFragment) }
    private fun openImport() { if (findNavController().currentDestination?.id == R.id.libraryFragment) findNavController().navigate(R.id.importFragment) }
    override fun onSaveInstanceState(outState: Bundle) { outState.putInt("library_filter", filter.value); super.onSaveInstanceState(outState) }
    override fun onStart() { super.onStart(); active = true }
    override fun onStop() { active = false; if (ownsPlayback) gateway.pausePlayback(); ownsPlayback = false; playingProject = null; super.onStop() }
}
