package com.ideacode.soundly.feature.enhancement

import android.widget.SeekBar
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.ideacode.soundly.R
import com.ideacode.soundly.common.ui.DisplayFormat
import com.ideacode.soundly.databinding.FragmentH6EnhancementSetupBinding
import com.ideacode.soundly.domain.mainflow.EnhancementMode
import com.ideacode.soundly.feature.home.SceneLabels
import com.ideacode.soundly.feature.preview.PreviewState
import com.ideacode.soundly_sdk.api.model.playback.PlaybackState
import com.ideacode.soundly_sdk.api.model.domain.VariantType

/** View-only mapping, shared by the real screen and debug visual fixture. */
class EnhancementSetupRenderer(
    private val b: FragmentH6EnhancementSetupBinding,
    private val onSelect: (EnhancementMode) -> Unit,
    private val onSeek: (Long) -> Unit = {}
) {
    private var rendering = false
    private var seeking = false
    private var durationMs = 0L
    private var previousWaveform: List<Float>? = null
    private val buttons = EnhancementMode.entries.associateWith { mode ->
        createEnhancementModeOption(b.root.context, mode).also { b.setupModes.addView(it) }
    }

    init {
        b.setupModes.setOnCheckedChangeListener { _, id ->
            if (!rendering) buttons.entries.firstOrNull { it.value.id == id }?.key?.let(onSelect)
        }
        val context = b.root.context
        b.setupWaveform.setColors(ContextCompat.getColor(context, R.color.preview_primary), ContextCompat.getColor(context, R.color.preview_wave))
        b.setupWaveform.setBarStyle(2.5f, 5f, shadeUnplayedByAmplitude = true)
        b.setupSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar) { seeking = true }
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                seeking = false
                onSeek(positionForProgress(seekBar.progress))
            }
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser && !seeking) onSeek(positionForProgress(progress))
            }
        })
    }

    fun render(start: EnhancementStartState, source: PreviewState) {
        val ready = !source.loading && !source.missing && source.project != null && source.variant != null
        b.setupLoading.isVisible = source.loading
        b.setupError.isVisible = source.missing
        b.setupContent.isVisible = ready
        b.setupFooter.isVisible = ready
        b.enhancementStart.isEnabled = ready && !start.busy
        b.enhancementStart.alpha = if (b.enhancementStart.isEnabled) 1f else .5f
        rendering = true
        try {
            buttons.forEach { (mode, option) ->
                option.isEnabled = !start.busy
                if (mode == start.selected && !option.isChecked) option.isChecked = true
            }
        } finally { rendering = false }
        val project = source.project ?: return
        val variant = source.variant ?: return
        b.setupSourceTitle.text = project.title
        b.setupSourceScene.isVisible = project.scene != null
        project.scene?.let { b.setupSourceScene.setText(SceneLabels.name(it)) }
        b.setupSourceDuration.text = if (variant.variantType == VariantType.ORIGINAL) DisplayFormat.durationMillis(variant.durationMs)
            else b.root.context.getString(R.string.i4_version_duration, b.root.context.getString(if (variant.variantType == VariantType.TRIMMED) R.string.i3_trimmed else R.string.i3_enhanced), DisplayFormat.durationMillis(variant.durationMs))
        if (previousWaveform != source.waveform) {
            b.setupWaveform.setSamples(source.waveform)
            previousWaveform = source.waveform
        }
        b.setupWaveform.isVisible = source.waveform.isNotEmpty()
        b.setupWaveformPlaceholder.isVisible = source.waveform.isEmpty()
        b.setupWaveformError.isVisible = source.waveformError
        b.setupPlaybackError.isVisible = source.playback is PlaybackState.Failed
        val position = when (val playback = source.playback) {
            is PlaybackState.Playing -> playback.positionMs
            is PlaybackState.Paused -> playback.positionMs
            PlaybackState.Completed -> variant.durationMs
            else -> 0L
        }
        durationMs = variant.durationMs.coerceAtLeast(0L)
        val progress = if (durationMs > 0) (position.toDouble() / durationMs).coerceIn(0.0, 1.0) else 0.0
        b.setupWaveform.setProgress(progress.toFloat())
        b.setupSeek.isEnabled = ready && !source.preparing && !start.busy && durationMs > 0
        if (!seeking) b.setupSeek.progress = (progress * 1000).toInt()
        val playing = source.playback is PlaybackState.Playing
        b.setupPlay.setImageResource(if (playing) R.drawable.ic_h4_pause else R.drawable.ic_h4_play)
        b.setupPlay.contentDescription = b.root.context.getString(if (playing) R.string.preview_pause else R.string.preview_play)
        b.setupPlay.isEnabled = ready && !source.preparing && !start.busy
    }

    private fun positionForProgress(progress: Int): Long =
        (durationMs.toDouble() * progress.coerceIn(0, 1000) / 1000.0).toLong()
}
