package com.ideacode.soundly.feature.startup

import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.navOptions
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ideacode.soundly.R
import com.ideacode.soundly.app.SoundlyApplication
import com.ideacode.soundly.common.ui.DisplayFormat
import com.ideacode.soundly.databinding.FragmentRecoveryBinding
import com.ideacode.soundly.feature.home.SceneLabels
import kotlinx.coroutines.launch

class RecoveryFragment : Fragment(R.layout.fragment_recovery) {
    private val container get() = (requireActivity().application as SoundlyApplication).appContainer
    private val viewModel: RecoveryViewModel by viewModels {
        object : AbstractSavedStateViewModelFactory(this, arguments) {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(key: String, modelClass: Class<T>, handle: SavedStateHandle): T =
                RecoveryViewModel(container.recordingGateway, handle, requireArguments().getString("sessionId").orEmpty()) as T
        }
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val b = FragmentRecoveryBinding.bind(view)
        val back = object : OnBackPressedCallback(true) { override fun handleOnBackPressed() { goHome() } }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, back)
        b.recoveryLater.setOnClickListener { goHome() }
        b.recoveryRetry.setOnClickListener { viewModel.dispatch(RecoveryAction.Load) }
        b.recoverySave.setOnClickListener { viewModel.dispatch(RecoveryAction.Save) }
        b.recoveryDiscard.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext()).setMessage(R.string.h4_recovery_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.h4_discard_confirm) { _, _ -> viewModel.dispatch(RecoveryAction.Discard) }.show()
        }
        viewLifecycleOwner.lifecycleScope.launch { viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.uiState.collect { s ->
                val enabled = !s.loading && !s.busy && !s.error && s.session != null
                b.recoverySave.isEnabled = enabled; b.recoveryDiscard.isEnabled = enabled
                b.recoveryLater.isEnabled = !s.busy
                b.recoveryProgress.isVisible = s.busy || s.loading
                b.recoveryRetry.isVisible = s.error
                b.recoveryRecordingTitle.text = s.session?.title ?: getString(R.string.h4_recovery_unknown_title)
                b.recoveryDetails.text = s.session?.let { session ->
                    listOfNotNull(session.scene?.let { getString(SceneLabels.name(it)) }, session.format?.name,
                        session.markerCount?.let { getString(R.string.h4_marker_count, it) }).joinToString(" · ")
                }.orEmpty()
                b.recoveryInfo.text = s.session?.let { getString(R.string.h4_recovery_info, DisplayFormat.durationMillis(it.recordedDurationMs), DisplayFormat.byteSize(it.recordedBytes)) } ?: ""
                b.recoveryMessage.text = when { s.busy -> getString(R.string.h4_recovery_working); s.error -> getString(R.string.error_operation_failed); !s.loading && s.session == null -> getString(R.string.h4_recovery_missing); else -> "" }
            }
        } }
        viewLifecycleOwner.lifecycleScope.launch { viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.effects.collect { list ->
                val entry = list.firstOrNull() ?: return@collect
                val nav = findNavController()
                if (nav.currentDestination?.id != R.id.recoveryFragment) return@collect
                when (val effect = entry.value) {
                    RecoveryEffect.Home -> goHome(completed = true)
                    is RecoveryEffect.Error -> MaterialAlertDialogBuilder(requireContext()).setMessage(container.errorMessageMapper.messageRes(effect.error)).setPositiveButton(android.R.string.ok, null).show()
                    is RecoveryEffect.Preview -> {
                        nav.navigate(R.id.audioPreviewFragment, bundleOf("projectId" to effect.result.projectId, "variantId" to effect.result.originalVariantId),
                            navOptions { popUpTo(R.id.recoveryFragment) { inclusive = true } })
                        viewModel.acknowledgePreview(effect.result)
                    }
                }
                viewModel.acknowledgeEffect(entry.id)
            }
        } }
    }
    private fun goHome(completed: Boolean = false) {
        if (!completed && viewModel.uiState.value.busy) return
        val nav = findNavController()
        if (nav.currentDestination?.id != R.id.recoveryFragment) return
        if (!nav.popBackStack(R.id.homeFragment, false)) nav.navigate(R.id.homeFragment, null, navOptions { popUpTo(R.id.recoveryFragment) { inclusive = true } })
    }
}
