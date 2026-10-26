package com.ideacode.soundly.feature.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ideacode.soundly.BuildConfig
import com.ideacode.soundly.R
import com.ideacode.soundly.databinding.FragmentHelpFeedbackBinding

/** F36. Diagnostics are opt-in and never include recordings or local file paths. */
class HelpFeedbackFragment : Fragment(R.layout.fragment_help_feedback) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentHelpFeedbackBinding.bind(view)
        binding.helpFeedback.setOnClickListener { launchEmail(includeDiagnostics = false) }
        binding.helpDiagnostics.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.help_diagnostics_title)
                .setMessage(R.string.help_diagnostics_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.help_diagnostics) { _, _ -> launchEmail(includeDiagnostics = true) }
                .show()
        }
    }

    private fun launchEmail(includeDiagnostics: Boolean) {
        val body = if (includeDiagnostics) {
            "\n\n--- Diagnostics ---\nVersion: ${BuildConfig.VERSION_NAME}\nAndroid: ${Build.VERSION.RELEASE}\nDevice: ${Build.MANUFACTURER} ${Build.MODEL}"
        } else {
            ""
        }
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.app_name))
            putExtra(Intent.EXTRA_TEXT, body)
        }
        if (intent.resolveActivity(requireContext().packageManager) != null) startActivity(intent)
        else MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.error_operation_failed)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
