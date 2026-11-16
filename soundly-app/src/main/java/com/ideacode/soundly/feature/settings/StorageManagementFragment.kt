package com.ideacode.soundly.feature.settings

import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.core.content.ContextCompat
import com.ideacode.soundly.common.ui.addTopAppBarMenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.ideacode.soundly.R
import com.ideacode.soundly.app.SoundlyApplication
import com.ideacode.soundly.common.error.AppResult
import com.ideacode.soundly.databinding.FragmentStorageManagementBinding
import com.ideacode.soundly_sdk.api.capability.StorageSnapshot
import com.ideacode.soundly_sdk.api.model.recording.RecordingState
import kotlinx.coroutines.launch

/** F34. Statistics and destructive operations are delegated to the SDK storage manager. */
class StorageManagementFragment : Fragment(R.layout.fragment_storage_management) {
    private var operationRunning = false
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentStorageManagementBinding.bind(view)
        addTopAppBarMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
                inflater.inflate(R.menu.storage_actions, menu)
                menu.findItem(R.id.storage_refresh)?.icon?.mutate()?.setTint(ContextCompat.getColor(requireContext(), R.color.preview_primary))
            }
            override fun onMenuItemSelected(item: MenuItem): Boolean = if (item.itemId == R.id.storage_refresh) { refresh(binding); true } else false
        })
        binding.storageClearCache.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setMessage(R.string.storage_clear_cache_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.storage_clear_cache) { _, _ ->
                    runOperation(action = { clearCache() }) { refresh(binding) }
                }
                .show()
        }
        binding.storageDeleteAll.setOnClickListener { showDeleteAllConfirmation(binding) }
        viewLifecycleOwner.lifecycleScope.launch { viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            (requireActivity().application as SoundlyApplication).appContainer.recordingGateway.state.collect { state ->
                val active = state !is RecordingState.Idle
                binding.storageDeleteAll.isEnabled = !active
                binding.storageRecordingWarning.visibility = if (active) View.VISIBLE else View.GONE
            }
        } }
        refresh(binding)
    }

    private fun showDeleteAllConfirmation(binding: FragmentStorageManagementBinding) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.storage_delete_all_title)
            .setMessage(R.string.storage_delete_all_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.project_delete_confirm) { _, _ ->
                runOperation(action = { deleteAllLocalData() }) { refresh(binding) }
            }
            .show()
    }

    private fun refresh(binding: FragmentStorageManagementBinding) {
        val gateway = (requireActivity().application as SoundlyApplication).appContainer.settingsStorageGateway
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = gateway.storageSnapshot()) {
                is AppResult.Success -> {
                    val snapshot = result.value
                    renderSnapshot(binding, snapshot)
                }
                is AppResult.Failure -> showOperationError(result.error.code == "invalid_state")
            }
        }
    }

    private fun runOperation(
        action: suspend com.ideacode.soundly.data.sdk.SettingsStorageGateway.() -> AppResult<Unit>,
        onSuccess: () -> Unit
    ) {
        if (operationRunning) return
        operationRunning = true
        val gateway = (requireActivity().application as SoundlyApplication).appContainer.settingsStorageGateway
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                when (val result = gateway.action()) {
                    is AppResult.Success -> {
                        Snackbar.make(requireView(), R.string.s4_storage_operation_done, Snackbar.LENGTH_SHORT).show()
                        onSuccess()
                    }
                    is AppResult.Failure -> showOperationError(result.error.code == "invalid_state")
                }
            } finally { operationRunning = false }
        }
    }

    private fun renderSnapshot(binding: FragmentStorageManagementBinding, snapshot: StorageSnapshot) {
        fun size(value: Long) = Formatter.formatFileSize(requireContext(), value)
        val other = snapshot.recoveryBytes + snapshot.auxiliaryBytes
        binding.storageTotal.text = size(snapshot.originalsBytes + snapshot.variantsBytes + snapshot.cacheBytes + other)
        binding.storageOriginalsValue.text = size(snapshot.originalsBytes)
        binding.storageVariantsValue.text = size(snapshot.variantsBytes)
        binding.storageCacheValue.text = size(snapshot.cacheBytes)
        binding.storageCacheActionValue.text = size(snapshot.cacheBytes)
        binding.storageOtherValue.text = size(other)
        binding.storageAvailableValue.text = size(snapshot.availableBytes)
        binding.storageOtherRow.isVisible = other > 0
        binding.storageOtherLegend.isVisible = other > 0
        binding.storageUsageBar.setUsage(snapshot.originalsBytes, snapshot.variantsBytes, snapshot.cacheBytes, other)
    }

    private fun showOperationError(busy: Boolean = false) = MaterialAlertDialogBuilder(requireContext())
        .setMessage(if (busy) R.string.s1_storage_operation_busy else R.string.error_operation_failed)
        .setPositiveButton(android.R.string.ok, null)
        .show()
}
