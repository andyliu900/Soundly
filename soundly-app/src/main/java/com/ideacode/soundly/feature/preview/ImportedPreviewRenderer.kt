package com.ideacode.soundly.feature.preview

import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.ideacode.soundly.R
import com.ideacode.soundly.common.ui.DisplayFormat
import com.ideacode.soundly.common.ui.audio.AudioPlayerUiState
import com.ideacode.soundly.databinding.LayoutImportedPreviewBinding
import com.ideacode.soundly_sdk.api.model.playback.PlaybackState
import com.ideacode.soundly_sdk.api.model.domain.VariantType
import java.util.Date

internal class ImportedPreviewRenderer(private val binding: LayoutImportedPreviewBinding) {
    private var samples: List<Float>? = null
    init {
        val context = binding.root.context
        binding.importedWaveform.setBarStyle(3f, 7f, shadeUnplayedByAmplitude = true)
        binding.importedWaveform.setColors(ContextCompat.getColor(context, R.color.preview_primary), ContextCompat.getColor(context, R.color.preview_wave))
        binding.importedPlayer.setAccentColors(ContextCompat.getColor(context, R.color.preview_control), ContextCompat.getColor(context, R.color.preview_track))
    }
    fun render(state: PreviewState) {
        val project = state.project ?: return
        val variant = state.variant ?: return
        val context = binding.root.context
        binding.importedName.text = project.title
        val time = DateFormat.getTimeFormat(context).format(Date(project.createdAt))
        binding.importedDate.text = if (DateUtils.isToday(project.createdAt)) context.getString(R.string.preview_today_time, time)
            else DateFormat.getMediumDateFormat(context).format(Date(project.createdAt)) + " " + time
        binding.importedSource.text = project.sourceDisplayName?.takeIf { it.isNotBlank() }
            ?.let { context.getString(R.string.imported_source_name, it) } ?: context.getString(R.string.imported_source_unknown)
        binding.importedFormat.text = variant.format.name
        binding.importedDuration.text = DisplayFormat.durationMillis(variant.durationMs)
        binding.importedSize.text = DisplayFormat.byteSize(variant.fileSizeBytes)
        binding.importedVersionTitle.setText(when (variant.variantType) {
            VariantType.ORIGINAL -> R.string.imported_original_title
            VariantType.TRIMMED -> R.string.imported_trimmed_title
            VariantType.ENHANCED -> R.string.imported_enhanced_title
        })
        binding.importedVersionDetail.setText(if (variant.isOriginal) R.string.imported_original_detail else R.string.imported_other_detail)
        if (samples != state.waveform) { binding.importedWaveform.setSamples(state.waveform); samples = state.waveform }
        binding.importedWaveStatus.isVisible = state.waveformLoading || state.waveformError
        binding.importedWaveStatus.setText(if (state.waveformLoading) R.string.imported_wave_loading else R.string.imported_wave_retry)
        binding.importedWaveStatus.isEnabled = state.waveformError && !state.waveformLoading
        binding.importedPlaybackError.isVisible = state.playback is PlaybackState.Failed
        val position = when (val playback = state.playback) {
            is PlaybackState.Playing -> playback.positionMs
            is PlaybackState.Paused -> playback.positionMs
            PlaybackState.Completed -> variant.durationMs
            else -> 0L
        }
        binding.importedWaveform.setProgress(if (variant.durationMs > 0) position.toFloat() / variant.durationMs else 0f)
        binding.importedPlayer.render(AudioPlayerUiState(variant.durationMs, position, state.playback is PlaybackState.Playing, !state.preparing && !state.starting))
        binding.importedRename.isEnabled = !state.starting
        binding.importedEnhance.isEnabled = !state.starting
        binding.importedTrim.isEnabled = !state.starting
        binding.importedExport.isEnabled = !state.starting
        binding.importedLibrary.isEnabled = !state.starting
    }
}
