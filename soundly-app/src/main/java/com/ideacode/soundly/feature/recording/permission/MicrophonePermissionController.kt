package com.ideacode.soundly.feature.recording.permission

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.ideacode.soundly.app.SoundlyApplication

/** Lifecycle-aware platform boundary shared by every recording entry point. */
class MicrophonePermissionController : Fragment() {
    private val preferences get() = (requireActivity().application as SoundlyApplication).appContainer.appPreferences
    private val request = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) deliver(GRANTED) else showExplanation(denied = true)
    }
    private val settings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // Returning from settings never starts a recording without another user action.
        deliver(if (isGranted()) SETTINGS_GRANTED else CANCELLED)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        childFragmentManager.setFragmentResultListener(PermissionSheet.RESULT, this) { _, result ->
            when (result.getString(PermissionSheet.ACTION)) {
                PermissionSheet.CONTINUE -> {
                    if (isGranted()) deliver(GRANTED)
                    else if (isPermanentlyDenied()) openSettings()
                    else {
                        preferences.microphoneRequestCount += 1
                        request.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
                PermissionSheet.IMPORT -> deliver(IMPORT)
                else -> deliver(CANCELLED)
            }
        }
    }

    fun begin() {
        if (isGranted()) deliver(GRANTED) else showExplanation(preferences.microphoneRequestCount > 0)
    }

    private fun isGranted() = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun isPermanentlyDenied() = preferences.microphoneRequestCount >= 2 &&
        !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)

    private fun showExplanation(denied: Boolean) {
        if (childFragmentManager.findFragmentByTag(PermissionSheet.TAG) == null) {
            PermissionSheet.create(denied, isPermanentlyDenied()).show(childFragmentManager, PermissionSheet.TAG)
        }
    }
    private fun openSettings() {
        settings.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", requireContext().packageName, null)))
    }
    private fun deliver(value: String) {
        parentFragmentManager.setFragmentResult(RESULT, bundleOf(OUTCOME to value))
    }

    companion object {
        const val RESULT = "microphone_permission_result"
        const val OUTCOME = "outcome"
        const val GRANTED = "granted"
        const val SETTINGS_GRANTED = "settings_granted"
        const val IMPORT = "import"
        const val CANCELLED = "cancelled"
        private const val TAG = "microphone_permission_controller"
        fun get(manager: FragmentManager): MicrophonePermissionController =
            (manager.findFragmentByTag(TAG) as? MicrophonePermissionController)
                ?: MicrophonePermissionController().also { manager.beginTransaction().add(it, TAG).commitNow() }
    }
}
