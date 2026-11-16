package com.ideacode.soundly.feature.enhancement

import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.ideacode.soundly.R
import com.ideacode.soundly.common.ui.audio.AudioPlayerUiState
import com.ideacode.soundly.databinding.FragmentH6EnhancementResultBinding
import com.ideacode.soundly.domain.mainflow.EnhancementMode
import com.ideacode.soundly.feature.home.SceneLabels
import com.ideacode.soundly_sdk.api.model.playback.PlaybackState
import com.ideacode.soundly_sdk.api.model.domain.AudioSourceType
import com.ideacode.soundly_sdk.api.model.domain.VariantType

/** View-only rendering, shared with the debug visual preview. SDK ownership stays in the ViewModel. */
class EnhancementResultRenderer(private val b: FragmentH6EnhancementResultBinding) {
    private var previousWaveform: List<Float>? = null
    private var enhancedAppearance: Boolean? = null

    init { b.resultWaveform.setBarStyle(widthDp = 4f, minimumStepDp = 10f, shadeUnplayedByAmplitude = true) }

    fun render(s: ResultState) {
        b.resultLoading.isVisible = s.loading
        b.resultError.isVisible = s.missing
        b.resultContent.isVisible = !s.loading && !s.missing && s.project != null && s.selected != null
        val variant = s.selected ?: return
        val context = b.root.context
        b.resultName.text = s.project?.title.orEmpty()
        b.resultScene.isVisible = s.project != null
        s.project?.scene?.let { b.resultScene.setText(SceneLabels.name(it)) }
        val enhanced = variant.id == s.enhanced?.id
        val source = s.source
        val originalRecording = source?.isOriginal == true && s.project?.sourceType == AudioSourceType.RECORDED
        b.versionOriginal.setText(if (source?.isOriginal == true) R.string.enhancement_result_original else R.string.i4_before)
        b.resultSavedDescription.setText(if (originalRecording) R.string.enhancement_result_original_kept else R.string.i4_input_kept)
        b.versionToggle.check(if (enhanced) R.id.version_enhanced else R.id.version_original)
        b.versionOriginal.isEnabled = !s.busy
        b.versionEnhanced.isEnabled = !s.busy
        b.enhancementResultExport.isEnabled = !s.busy
        b.resultNewVersion.isEnabled = !s.busy
        b.enhancementResultExport.alpha = if (s.busy) .5f else 1f
        if (previousWaveform != s.waveform) {
            b.resultWaveform.setSamples(s.waveform)
            previousWaveform = s.waveform
        }
        if (enhancedAppearance != enhanced) {
            enhancedAppearance = enhanced
            val active = ContextCompat.getColor(context, if (enhanced) R.color.enhancement_result_teal else R.color.enhancement_progress_primary)
            val inactive = ContextCompat.getColor(context, if (enhanced) R.color.enhancement_result_teal_pale else R.color.enhancement_progress_badge)
            b.resultWaveform.setColors(active, inactive)
            b.resultPlayer.setAccentColors(active, ContextCompat.getColor(context, R.color.enhancement_progress_track),
                ContextCompat.getColor(context, if (enhanced) R.color.enhancement_result_teal else R.color.enhancement_progress_gradient_start))
        }
        b.resultWaveformError.isVisible = s.waveformError
        b.resultPlaybackError.isVisible = s.playback is PlaybackState.Failed
        val position = when (val playback = s.playback) {
            is PlaybackState.Playing -> playback.positionMs
            is PlaybackState.Paused -> playback.positionMs
            PlaybackState.Completed -> variant.durationMs
            else -> 0L
        }
        b.resultWaveform.setProgress(if (variant.durationMs > 0) position.toFloat() / variant.durationMs else 0f)
        b.resultPlayer.render(AudioPlayerUiState(variant.durationMs, position, s.playback is PlaybackState.Playing, !s.busy))
        val mode = s.enhanced?.presetOption?.let(EnhancementUi::mode)
        b.enhancementResultInfo.setText(mode?.let(EnhancementUi::title) ?: R.string.h6_unknown_preset)
        // These are processing directions, not unmeasured claims of improvement.
        val effects = when (mode) {
            EnhancementMode.AUTO -> listOf(R.string.enhancement_result_auto_volume, R.string.enhancement_result_auto_voice, R.string.enhancement_result_auto_noise)
            null -> listOf(R.string.h6_unknown_preset)
            else -> listOf(EnhancementUi.description(mode))
        }
        listOf(b.resultEffectOne, b.resultEffectTwo, b.resultEffectThree).forEachIndexed { index, row ->
            row.isVisible = index < effects.size
            effects.getOrNull(index)?.let(row::setText)
        }
        b.resultSelectedInfo.text = context.getString(when {
            enhanced && s.project?.sourceType == AudioSourceType.IMPORTED -> R.string.i4_enhanced_audio_file
            enhanced -> R.string.enhancement_result_enhanced_file
            variant.variantType == VariantType.TRIMMED -> R.string.i4_trimmed_file
            originalRecording -> R.string.enhancement_result_original_file
            variant.isOriginal -> R.string.i4_original_audio_file
            else -> R.string.i4_input_audio_file
        }, variant.format.name)
    }
}
