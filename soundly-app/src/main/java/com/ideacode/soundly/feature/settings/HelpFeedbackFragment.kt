package com.ideacode.soundly.feature.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ideacode.soundly.BuildConfig
import com.ideacode.soundly.R
import com.ideacode.soundly.databinding.FragmentHelpFeedbackBinding

/** Opens an editable mail draft only. The diagnostics switch is off by default. */
class HelpFeedbackFragment : Fragment(R.layout.fragment_help_feedback) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val b = FragmentHelpFeedbackBinding.bind(view)
        val questions = listOf(b.helpFaqLocalQuestion to b.helpFaqLocalAnswer, b.helpFaqFailQuestion to b.helpFaqFailAnswer, b.helpFaqExportQuestion to b.helpFaqExportAnswer)
        questions.forEachIndexed { index, (question, answer) ->
            answer.isVisible = savedInstanceState?.getBoolean("faq_$index") ?: (index == 0)
            bindExpanded(question, answer)
        }
        b.helpFeedback.setOnClickListener { launchEmail(b.helpFeedbackText.text.toString(), b.helpDiagnostics.isChecked) }
    }
    private fun bindExpanded(question: TextView, answer: TextView) {
        fun update() {
            question.contentDescription = getString(if (answer.isVisible) R.string.s4_faq_expanded else R.string.s4_faq_collapsed, question.text)
            question.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, if (answer.isVisible) R.drawable.ic_faq_up else R.drawable.ic_faq_down, 0)
            question.compoundDrawablePadding = (12 * resources.displayMetrics.density).toInt()
        }
        update()
        question.setOnClickListener { answer.isVisible = !answer.isVisible; update() }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        view?.let { v -> val b = FragmentHelpFeedbackBinding.bind(v); listOf(b.helpFaqLocalAnswer, b.helpFaqFailAnswer, b.helpFaqExportAnswer).forEachIndexed { i, answer -> outState.putBoolean("faq_$i", answer.isVisible) } }
        super.onSaveInstanceState(outState)
    }
    private fun launchEmail(feedback: String, includeDiagnostics: Boolean) {
        val body = FeedbackDraft.body(feedback, includeDiagnostics, BuildConfig.VERSION_NAME, Build.VERSION.RELEASE, "${Build.MANUFACTURER} ${Build.MODEL}")
        val recipient = getString(R.string.s4_feedback_recipient).trim()
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.fromParts("mailto", recipient, null)
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.help_feedback_title))
            putExtra(Intent.EXTRA_TEXT, body)
        }
        try {
            if (recipient.isEmpty()) Toast.makeText(requireContext(), R.string.s4_feedback_no_recipient, Toast.LENGTH_LONG).show()
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            MaterialAlertDialogBuilder(requireContext()).setMessage(R.string.s4_feedback_no_app).setPositiveButton(android.R.string.ok, null).show()
        }
    }
}
