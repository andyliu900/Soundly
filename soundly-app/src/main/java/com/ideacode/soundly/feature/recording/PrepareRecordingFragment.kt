package com.ideacode.soundly.feature.recording

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ideacode.soundly.R
import com.ideacode.soundly.app.SoundlyApplication
import com.ideacode.soundly.common.error.AppResult
import com.ideacode.soundly.databinding.FragmentPrepareRecordingBinding
import kotlinx.coroutines.launch

/** F05/F06/F07. Runtime permission remains an App-owned responsibility. */
class PrepareRecordingFragment : Fragment(R.layout.fragment_prepare_recording) {
    private var pendingTitle: String? = null
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.RECORD_AUDIO] == true) {
            prepareAndStart(pendingTitle)
        } else {
            showPermissionDenied()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentPrepareRecordingBinding.bind(view)
        binding.prepareStart.setOnClickListener {
            pendingTitle = binding.recordingName.text?.toString()?.trim()?.takeIf(String::isNotEmpty)
            if (hasRecordPermission()) prepareAndStart(pendingTitle) else requestPermissions()
        }
    }

    private fun hasRecordPermission(): Boolean = ContextCompat.checkSelfPermission(
        requireContext(), Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        val permissions = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
        permissionLauncher.launch(permissions)
    }

    private fun prepareAndStart(title: String?) {
        val container = (requireActivity().application as SoundlyApplication).appContainer
        viewLifecycleOwner.lifecycleScope.launch {
            when (container.recordingGateway.prepareUsingDefaults(title)) {
                is AppResult.Failure -> showOperationError()
                is AppResult.Success -> when (container.recordingGateway.start()) {
                    is AppResult.Success -> findNavController().navigate(R.id.recordingFragment)
                    is AppResult.Failure -> showOperationError()
                }
            }
        }
    }

    private fun showPermissionDenied() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.permission_title)
            .setMessage(R.string.permission_body)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.permission_denied_action) { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", requireContext().packageName, null)
                })
            }
            .show()
    }

    private fun showOperationError() {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.error_operation_failed)
            .setPositiveButton(R.string.foundation_retry, null)
            .show()
    }
}
