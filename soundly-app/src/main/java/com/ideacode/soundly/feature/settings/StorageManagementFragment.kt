package com.ideacode.soundly.feature.settings

import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.ideacode.soundly.R
import com.ideacode.soundly.app.SoundlyApplication
import com.ideacode.soundly.common.error.AppResult
import com.ideacode.soundly.databinding.FragmentStorageManagementBinding
import com.ideacode.soundly_sdk.api.capability.StorageSnapshot
import kotlinx.coroutines.launch

/** F34. Statistics and destructive operations are delegated to the SDK storage manager. */
class StorageManagementFragment : Fragment(R.layout.fragment_storage_management) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentStorageManagementBinding.bind(view)
        binding.storageRefresh.setOnClickListener { refresh(binding) }
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
                is AppResult.Success -> binding.storageUsage.text = renderSnapshot(result.value)
                is AppResult.Failure -> showOperationError()
            }
        }
    }

    private fun runOperation(
        action: suspend com.ideacode.soundly.data.sdk.SettingsStorageGateway.() -> AppResult<Unit>,
        onSuccess: () -> Unit
    ) {
        val gateway = (requireActivity().application as SoundlyApplication).appContainer.settingsStorageGateway
        viewLifecycleOwner.lifecycleScope.launch {
            when (gateway.action()) {
                is AppResult.Success -> {
                    Snackbar.make(requireView(), R.string.settings_saved, Snackbar.LENGTH_SHORT).show()
                    onSuccess()
                }
                is AppResult.Failure -> showOperationError()
            }
        }
    }

    private fun renderSnapshot(snapshot: StorageSnapshot): String = getString(
        R.string.storage_usage,
        Formatter.formatFileSize(requireContext(), snapshot.originalsBytes),
        Formatter.formatFileSize(requireContext(), snapshot.variantsBytes),
        Formatter.formatFileSize(requireContext(), snapshot.cacheBytes),
        Formatter.formatFileSize(requireContext(), snapshot.availableBytes)
    )

    private fun showOperationError() = MaterialAlertDialogBuilder(requireContext())
        .setMessage(R.string.error_operation_failed)
        .setPositiveButton(android.R.string.ok, null)
        .show()
}
