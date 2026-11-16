package com.ideacode.soundly.feature.preview

import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.ideacode.soundly.R
import com.ideacode.soundly.common.ui.DisplayFormat
import com.ideacode.soundly.common.ui.audio.AudioPlayerUiState
import com.ideacode.soundly.databinding.FragmentAudioPreviewBinding
import com.ideacode.soundly.databinding.ItemPreviewMarkerBinding
import com.ideacode.soundly.feature.home.SceneLabels
import com.ideacode.soundly_sdk.api.model.domain.AudioMarker
import com.ideacode.soundly_sdk.api.model.playback.PlaybackState
import java.util.Date

/** Renders actual project state without owning playback or navigation. */
class AudioPreviewRenderer(private val b: FragmentAudioPreviewBinding, private val onMarkerClick: (Long) -> Unit) {
    private var displayedMarkers: List<AudioMarker>? = null
    private var displayedDuration: Long? = null
    private var displayedWaveform: List<Float>? = null

    init {
        val context = b.root.context
        b.previewWaveform.setBarStyle(2f, 5f, shadeUnplayedByAmplitude = true)
        b.previewWaveform.setColors(ContextCompat.getColor(context, R.color.preview_primary), ContextCompat.getColor(context, R.color.preview_wave))
        b.previewPlayer.setAccentColors(ContextCompat.getColor(context, R.color.preview_control), ContextCompat.getColor(context, R.color.preview_track))
    }

    fun render(s: PreviewState) {
        b.previewLoading.isVisible = s.loading
        b.previewError.isVisible = s.missing
        b.previewContent.isVisible = !s.loading && !s.missing && s.variant != null && s.project != null
        val project = s.project ?: return
        val variant = s.variant ?: return
        val context = b.root.context
        b.previewSaved.setText(if (variant.isOriginal) R.string.preview_original_saved else R.string.h5_version_saved)
        b.previewName.text = project.title
        val time = DateFormat.getTimeFormat(context).format(Date(project.createdAt))
        b.previewDate.text = if (DateUtils.isToday(project.createdAt)) context.getString(R.string.preview_today_time, time)
            else DateFormat.getMediumDateFormat(context).format(Date(project.createdAt)) + " " + time
        b.previewScene.isVisible = project.scene != null
        project.scene?.let { b.previewScene.setText(SceneLabels.name(it)) }
        b.previewFormat.text = variant.format.name
        b.previewDuration.text = DisplayFormat.durationMillis(variant.durationMs)
        b.previewSize.text = DisplayFormat.byteSize(variant.fileSizeBytes)
        if (displayedWaveform != s.waveform) {
            b.previewWaveform.setSamples(s.waveform)
            displayedWaveform = s.waveform
        }
        b.previewWaveformError.isVisible = s.waveformError
        b.previewPlaybackError.isVisible = s.playback is PlaybackState.Failed
        val position = when (val playback = s.playback) {
            is PlaybackState.Playing -> playback.positionMs
            is PlaybackState.Paused -> playback.positionMs
            PlaybackState.Completed -> variant.durationMs
            else -> 0L
        }
        b.previewWaveform.setProgress(if (variant.durationMs > 0) position.toFloat() / variant.durationMs else 0f)
        b.previewPlayer.render(AudioPlayerUiState(variant.durationMs, position, s.playback is PlaybackState.Playing, !s.preparing && !s.starting))
        b.previewEnhance.isEnabled = !s.starting
        b.previewAdjust.isEnabled = !s.starting
        b.previewSkip.isEnabled = !s.starting
        b.previewRename.isEnabled = !s.starting
        if (displayedMarkers != project.markers || displayedDuration != variant.durationMs) {
            renderMarkers(project.markers, variant.durationMs)
            displayedMarkers = project.markers.toList()
            displayedDuration = variant.durationMs
        }
    }

    private fun renderMarkers(markers: List<AudioMarker>, durationMs: Long) {
        val context = b.root.context
        val density = context.resources.displayMetrics.density
        val ordered = markers.sortedBy { it.positionMs }
        b.previewTimeline.setMarkers(ordered, durationMs)
        b.previewMarkers.removeAllViews()
        if (ordered.isEmpty()) {
            b.previewMarkers.addView(TextView(context).apply {
                setText(R.string.h5_no_markers)
                setTextColor(ContextCompat.getColor(context, R.color.preview_secondary))
                textSize = 14f
                minHeight = (48 * density).toInt()
                gravity = android.view.Gravity.CENTER_VERTICAL
            })
        }
        ordered.forEachIndexed { index, marker ->
            if (index > 0) b.previewMarkers.addView(View(context).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(-1, (density).toInt().coerceAtLeast(1))
                setBackgroundColor(ContextCompat.getColor(context, R.color.preview_border))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            val row = ItemPreviewMarkerBinding.inflate(LayoutInflater.from(context), b.previewMarkers, false)
            row.markerName.text = marker.label?.takeIf { it.isNotBlank() } ?: context.getString(R.string.h5_marker_number, index + 1)
            row.markerTime.text = DisplayFormat.durationMillis(marker.positionMs)
            row.root.setOnClickListener { onMarkerClick(marker.positionMs) }
            b.previewMarkers.addView(row.root)
        }
    }
}
