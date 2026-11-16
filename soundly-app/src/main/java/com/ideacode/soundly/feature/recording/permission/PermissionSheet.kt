package com.ideacode.soundly.feature.recording.permission

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.ideacode.soundly.R
import com.ideacode.soundly.databinding.SheetMicrophonePermissionBinding

class PermissionSheet : BottomSheetDialogFragment() {
    private var sent = false
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val b = SheetMicrophonePermissionBinding.inflate(inflater, container, false)
        val denied = requireArguments().getBoolean("denied")
        val permanent = requireArguments().getBoolean("permanent")
        b.permissionTitle.setText(if (denied) R.string.mic_denied_title else R.string.mic_intro_title)
        b.permissionDescription.setText(if (permanent) R.string.mic_settings_description else if (denied) R.string.mic_retry_description else R.string.mic_intro_description)
        b.permissionInfo.setText(if (permanent) R.string.mic_settings_info else R.string.mic_local_info)
        b.permissionContinue.setText(if (permanent) R.string.mic_open_settings else if (denied) R.string.mic_retry else R.string.mic_continue)
        b.permissionImport.isVisible = denied
        b.permissionHint.isVisible = !denied
        b.permissionContinue.setOnClickListener { send(CONTINUE) }
        b.permissionImport.setOnClickListener { send(IMPORT) }
        b.permissionCancel.setOnClickListener { send(CANCEL) }
        return b.root
    }
    override fun onCancel(dialog: DialogInterface) { super.onCancel(dialog); sendResult(CANCEL) }
    private fun send(action: String) { sendResult(action); dismiss() }
    private fun sendResult(action: String) {
        if (!sent) { sent = true; parentFragmentManager.setFragmentResult(RESULT, bundleOf(ACTION to action)) }
    }
    companion object {
        const val TAG = "microphone_permission_sheet"
        const val RESULT = "permission_sheet_result"
        const val ACTION = "action"
        const val CONTINUE = "continue"
        const val IMPORT = "import"
        const val CANCEL = "cancel"
        fun create(denied: Boolean, permanent: Boolean) = PermissionSheet().apply {
            arguments = bundleOf("denied" to denied, "permanent" to permanent)
        }
    }
}
