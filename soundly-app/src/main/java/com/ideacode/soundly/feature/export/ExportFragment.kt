package com.ideacode.soundly.feature.export

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ideacode.soundly.R
import com.ideacode.soundly.app.SoundlyApplication
import com.ideacode.soundly.common.error.AppResult
import com.ideacode.soundly.databinding.FragmentExportBinding
import kotlinx.coroutines.launch

/** F24/F25: export and share always use SDK-generated public/cache URIs. */
class ExportFragment : Fragment(R.layout.fragment_export) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentExportBinding.bind(view)
        val projectId = requireArguments().getString("projectId").orEmpty()
        val variantId = requireArguments().getString("variantId").orEmpty()
        val gateway = (requireActivity().application as SoundlyApplication).appContainer.editingGateway
        binding.exportDownloads.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                binding.exportDownloads.isEnabled = false
                when (val result = gateway.exportToDownloads(projectId, variantId)) {
                    is AppResult.Success -> binding.exportStatus.text = getString(
                        R.string.export_success, result.value.destinationUri
                    )
                    is AppResult.Failure -> showError()
                }
                binding.exportDownloads.isEnabled = true
            }
        }
        binding.exportShare.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                when (val result = gateway.prepareShare(projectId, variantId)) {
                    is AppResult.Success -> startActivity(Intent.createChooser(result.value, getString(R.string.export_share)))
                    is AppResult.Failure -> showError()
                }
            }
        }
    }

    private fun showError() {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.error_operation_failed)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
