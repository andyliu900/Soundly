package com.ideacode.soundly.feature.home

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.ideacode.soundly.feature.recording.permission.MicrophonePermissionController
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.ideacode.soundly.R
import com.ideacode.soundly.app.SoundlyApplication
import com.ideacode.soundly.common.ui.UiStatus
import com.ideacode.soundly.databinding.FragmentHomeBinding
import com.ideacode.soundly_sdk.api.model.domain.RecordingScene
import kotlinx.coroutines.launch
import com.ideacode.soundly.feature.importing.ImportJournal

/** F03/F04: recent projects and per-recording scene selection. */
class HomeFragment : Fragment(R.layout.fragment_home) {
    private val viewModel: HomeViewModel by viewModels {
        HomeViewModel.factory(this, arguments,
            (requireActivity().application as SoundlyApplication).appContainer)
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.post {
            if (isAdded && !parentFragmentManager.isStateSaved && ImportJournal(requireContext()).read() != null &&
                findNavController().currentDestination?.id == R.id.homeFragment) findNavController().navigate(R.id.importFragment)
        }
        val binding = FragmentHomeBinding.bind(view)
        childFragmentManager.setFragmentResultListener(MicrophonePermissionController.RESULT, viewLifecycleOwner) { _, result ->
            when (result.getString(MicrophonePermissionController.OUTCOME)) {
                MicrophonePermissionController.GRANTED, MicrophonePermissionController.SETTINGS_GRANTED -> viewModel.dispatch(HomeAction.StartRecording)
                MicrophonePermissionController.IMPORT -> viewModel.dispatch(HomeAction.OpenImportPicker)
            }
        }
        val adapter = HomeRecentAdapter { viewModel.dispatch(HomeAction.OpenProject(it)) }
        binding.homeRecentList.layoutManager = LinearLayoutManager(requireContext())
        binding.homeRecentList.adapter = adapter
        binding.homeRecentStatus.onRetry = { viewModel.dispatch(HomeAction.RetryProjects) }
        binding.homeNewRecording.setOnClickListener { viewModel.dispatch(HomeAction.StartRecording) }
        binding.homeScene.setOnClickListener { viewModel.dispatch(HomeAction.OpenScenePicker) }
        binding.homeImportAudio.setOnClickListener { viewModel.dispatch(HomeAction.OpenImportPicker) }
        binding.homeViewAll.setOnClickListener { viewModel.dispatch(HomeAction.OpenLibrary) }
        childFragmentManager.setFragmentResultListener(RecordingSceneSheet.RESULT_KEY, viewLifecycleOwner) { _, result ->
            val name = result.getString(RecordingSceneSheet.RESULT_SCENE)
            RecordingScene.entries.firstOrNull { it.name == name }?.let { viewModel.dispatch(HomeAction.SelectScene(it)) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    binding.homeScene.text = getString(R.string.home_scene_label, getString(SceneLabels.name(state.displayedScene)))
                    binding.homeImportProgress.isVisible = false
                    binding.homeEmpty.isVisible = !state.loading && !state.loadError && state.recent.isEmpty()
                    binding.homeRecentList.isVisible = !state.loading && !state.loadError && state.recent.isNotEmpty()
                    binding.homeViewAll.isVisible = !state.loading && !state.loadError && state.recent.isNotEmpty()
                    binding.homeRecentStatus.render(when {
                        state.loading -> UiStatus.Loading()
                        state.loadError -> UiStatus.Error(getString(R.string.home_recent_failed), true)
                        else -> UiStatus.Hidden
                    })
                    adapter.submitList(state.recent)
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.effects.collect { effects ->
                    val entry = effects.firstOrNull() ?: return@collect
                    val permissionNeeded = entry.value is HomeEffect.OpenRecording && !hasMicrophonePermission()
                    if (handleEffect(entry.value)) viewModel.effectHandled(entry.id, entry.value, !permissionNeeded)
                }
            }
        }
    }

    private fun hasMicrophonePermission() = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun handleEffect(effect: HomeEffect): Boolean {
        val nav = findNavController()
        if (nav.currentDestination?.id != R.id.homeFragment) return false
        when (effect) {
            is HomeEffect.OpenRecording -> if (hasMicrophonePermission()) {
                nav.navigate(R.id.prepareRecordingFragment, bundleOf("homeScene" to effect.scene?.name),
                    NavOptions.Builder().setLaunchSingleTop(true).build())
            } else MicrophonePermissionController.get(childFragmentManager).begin()
            is HomeEffect.OpenScenePicker -> if (childFragmentManager.findFragmentByTag(RecordingSceneSheet.TAG) == null) {
                RecordingSceneSheet.create(effect.scene).show(childFragmentManager, RecordingSceneSheet.TAG)
            }
            HomeEffect.OpenImportPicker -> nav.navigate(R.id.importFragment)
            is HomeEffect.OpenProject -> nav.navigate(R.id.projectDetailFragment, bundleOf("projectId" to effect.projectId))
            HomeEffect.OpenLibrary -> nav.navigate(R.id.libraryFragment, null, NavOptions.Builder().setLaunchSingleTop(true).build())
        }
        return true
    }
}
