package com.ideacode.soundly.feature.enhancement

import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import androidx.core.view.isVisible
import com.ideacode.soundly.R
import com.ideacode.soundly.common.ui.DisplayFormat
import com.ideacode.soundly.databinding.FragmentH6EnhancementProgressBinding
import com.ideacode.soundly.feature.home.SceneLabels
import com.ideacode.soundly_sdk.api.model.domain.AudioProject
import com.ideacode.soundly_sdk.api.model.domain.AudioVariant
import com.ideacode.soundly_sdk.api.model.domain.VariantType
import com.ideacode.soundly_sdk.api.model.enhancement.EnhancementTaskState

/** The production screen and debug visual fixture share the same layout and rendering. */
object EnhancementProgressRenderer {
    fun render(b: FragmentH6EnhancementProgressBinding, state: ProgressState, animate: Boolean = true) {
        val context = b.root.context
        val running = state.task as? EnhancementTaskState.Running
        val failed = state.task as? EnhancementTaskState.Failed
        val unavailable = state.unknown || failed != null
        b.progressCircle.isVisible = !unavailable
        b.progressFailureIcon.isVisible = unavailable
        b.enhancementProgress.setProgress(running?.progressPercent ?: 0, animate)
        b.progressPercent.text = running?.let {
            val text = context.getString(R.string.h6_percent, it.progressPercent)
            SpannableString(text).apply {
                val percentStart = text.indexOf('%')
                if (percentStart >= 0) setSpan(RelativeSizeSpan(.65f), percentStart, percentStart + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } ?: ""
        b.progressCircle.contentDescription = running?.let {
            context.getString(R.string.enhancement_progress_accessibility, it.progressPercent, context.getString(EnhancementUi.stage(it.stage)))
        }
        b.progressHeading.setText(if (unavailable) R.string.h6_failed else R.string.h6_running)
        b.enhancementStage.text = when {
            state.unknown -> context.getString(R.string.h6_unknown)
            failed != null -> context.getString(R.string.h6_failed_description)
            state.busy -> context.getString(R.string.h6_cancelling)
            running != null -> context.getString(R.string.enhancement_progress_stage_wait, context.getString(EnhancementUi.stage(running.stage)))
            else -> context.getString(R.string.h6_processing)
        }
        val preset = state.task?.task?.preset
        b.progressMode.text = preset?.let(EnhancementUi::mode)?.let { context.getString(EnhancementUi.title(it)) }
            ?: if (preset != null) context.getString(R.string.h6_unknown_preset) else ""
        b.progressModeCard.isVisible = preset != null
        b.progressModeStatus.setText(when {
            unavailable -> R.string.h6_failed
            state.busy -> R.string.h6_cancelling
            else -> R.string.enhancement_progress_processing
        })
        b.progressMessage.setText(if (state.operationError) R.string.h6_operation_failed else R.string.enhancement_progress_local)
        b.enhancementCancel.isVisible = !unavailable
        b.enhancementCancel.isEnabled = running != null && !state.busy
        b.enhancementCancel.alpha = if (b.enhancementCancel.isEnabled) 1f else .5f
        b.progressCancelNote.isVisible = !unavailable
        b.progressRetry.isVisible = failed?.retryable == true
        b.progressRetry.isEnabled = !state.busy
        b.progressChange.isVisible = unavailable && state.projectId.isNotBlank() && state.sourceId.isNotBlank()
        b.progressChange.isEnabled = !state.busy
        b.progressOriginal.isVisible = unavailable
        b.progressOriginal.isEnabled = !state.busy
    }

    fun renderSource(b: FragmentH6EnhancementProgressBinding, project: AudioProject?, variant: AudioVariant?) {
        b.progressSource.text = project?.title.orEmpty()
        val scene = project?.scene
        b.progressSourceScene.isVisible = scene != null
        b.progressSourceDivider.isVisible = scene != null && variant != null
        b.progressSourceDuration.isVisible = variant != null
        if (scene != null) b.progressSourceScene.setText(SceneLabels.name(scene))
        b.progressSourceDuration.text = variant?.let {
            val duration = DisplayFormat.durationMillis(it.durationMs)
            if (it.variantType == VariantType.ORIGINAL) duration else b.root.context.getString(R.string.i4_version_duration,
                b.root.context.getString(if (it.variantType == VariantType.TRIMMED) R.string.i3_trimmed else R.string.i3_enhanced), duration)
        }.orEmpty()
    }
}
