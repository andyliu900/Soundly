package com.ideacode.soundly.feature.settings

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.ideacode.soundly.R
import com.ideacode.soundly.app.SoundlyApplication
import com.ideacode.soundly.common.error.AppResult
import com.ideacode.soundly.databinding.FragmentPrivacyBinding
import kotlinx.coroutines.launch

/** F35. The irreversible SDK privacy operation always requires host confirmation. */
class PrivacyFragment : Fragment(R.layout.fragment_privacy) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentPrivacyBinding.bind(view)
        binding.privacyDeleteAll.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.storage_delete_all_title)
                .setMessage(R.string.storage_delete_all_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.project_delete_confirm) { _, _ -> deleteAllData(view) }
                .show()
        }
    }

    private fun deleteAllData(view: View) {
        val gateway = (requireActivity().application as SoundlyApplication).appContainer.settingsStorageGateway
        viewLifecycleOwner.lifecycleScope.launch {
            when (gateway.deleteAllLocalData()) {
                is AppResult.Success -> Snackbar.make(view, R.string.settings_saved, Snackbar.LENGTH_SHORT).show()
                is AppResult.Failure -> MaterialAlertDialogBuilder(requireContext())
                    .setMessage(R.string.error_operation_failed)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }
}
