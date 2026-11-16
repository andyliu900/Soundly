package com.ideacode.soundly.feature.preview

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ideacode.soundly.R
import com.ideacode.soundly.databinding.DialogRenameProjectBinding
import kotlinx.coroutines.launch

interface RenameProjectHost { val renameModel: AudioPreviewViewModel }

class RenameProjectDialog : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val model = (requireParentFragment() as RenameProjectHost).renameModel
        if (savedInstanceState == null) model.clearRenameError()
        val b = DialogRenameProjectBinding.inflate(LayoutInflater.from(requireContext()))
        b.renameInput.setText(model.uiState.value.project?.title.orEmpty())
        b.renameInput.setSelection(b.renameInput.length())
        val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.rename_title)
            .setView(b.root).setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.save, null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                model.dispatch(PreviewAction.Rename(b.renameInput.text?.toString().orEmpty()))
            }
        }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) {
            model.uiState.collect { state ->
                b.renameLayout.error = when (state.renameError) {
                    "empty" -> getString(R.string.h5_name_empty)
                    "length" -> getString(R.string.h5_name_long)
                    "failed" -> getString(R.string.h5_rename_failed)
                    else -> null
                }
                b.renameInput.isEnabled = !state.renaming
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.isEnabled = !state.renaming
                dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.isEnabled = !state.renaming
                isCancelable = !state.renaming
            }
        } }
        return dialog
    }
}
