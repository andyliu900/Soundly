package com.ideacode.soundly.common.ui.audio

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.Rect
import android.view.TouchDelegate
import android.view.View
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.SeekBar
import androidx.core.content.ContextCompat
import com.ideacode.soundly.R
import com.ideacode.soundly.common.ui.DisplayFormat
import com.ideacode.soundly.databinding.ViewAudioPlayerBinding

/** UI input only: playback ownership, errors and SDK operations remain with the feature. */
data class AudioPlayerUiState(
    val durationMs: Long = 0,
    val positionMs: Long = 0,
    val playing: Boolean = false,
    val enabled: Boolean = false
)

class AudioPlayerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {
    private val binding: ViewAudioPlayerBinding
    private var durationMs = 0L
    private var userSeeking = false
    private val compact: Boolean
    private val preview: Boolean
    var onToggle: (() -> Unit)? = null
    var onSeek: ((Long) -> Unit)? = null

    init {
        orientation = VERTICAL
        var detail = false
        context.obtainStyledAttributes(attrs, R.styleable.AudioPlayerView, defStyleAttr, 0).let {
            compact = it.getBoolean(R.styleable.AudioPlayerView_audioPlayerCompact, false)
            preview = it.getBoolean(R.styleable.AudioPlayerView_audioPlayerPreview, false)
            detail = it.getBoolean(R.styleable.AudioPlayerView_audioPlayerDetail, false)
            it.recycle()
        }
        // The layouts share one binding contract; playback and accessibility behavior remain centralized.
        val layout = when { detail -> R.layout.view_audio_player_detail; preview -> R.layout.view_audio_player_preview; compact -> R.layout.view_audio_player_compact; else -> R.layout.view_audio_player }
        LayoutInflater.from(context).inflate(layout, this)
        binding = ViewAudioPlayerBinding.bind(this)
        if (preview) binding.playerSeek.post {
            val parent = binding.playerSeek.parent as? View ?: return@post
            val bounds = Rect()
            binding.playerSeek.getHitRect(bounds)
            val extra = (8 * resources.displayMetrics.density).toInt()
            bounds.inset(0, -extra)
            parent.touchDelegate = TouchDelegate(bounds, binding.playerSeek)
        }
        binding.playerToggle.setOnClickListener { onToggle?.invoke() }
        binding.playerSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar) { userSeeking = true }
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                userSeeking = false
                onSeek?.invoke(positionForProgress(seekBar.progress))
            }
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.playerPosition.text = DisplayFormat.durationMillis(positionForProgress(progress))
                    // Keyboard and accessibility actions have no tracking start/stop callbacks.
                    if (!userSeeking) onSeek?.invoke(positionForProgress(progress))
                }
            }
        })
    }

    fun setAccentColors(active: Int, inactive: Int, gradientStart: Int = active) {
        binding.playerToggle.backgroundTintList = null
        val circle = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(gradientStart, active)).apply {
            shape = GradientDrawable.OVAL
        }
        val inset = (4 * resources.displayMetrics.density).toInt()
        binding.playerToggle.background = InsetDrawable(RippleDrawable(ColorStateList.valueOf(0x44FFFFFF), circle, null), inset)
        binding.playerSeek.progressTintList = ColorStateList.valueOf(active)
        binding.playerSeek.progressBackgroundTintList = ColorStateList.valueOf(inactive)
        binding.playerSeek.thumbTintList = null
        binding.playerSeek.thumb = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(active)
            setStroke((resources.displayMetrics.density * 1.5f).toInt(), ContextCompat.getColor(context, R.color.white))
            setSize((14 * resources.displayMetrics.density).toInt(), (14 * resources.displayMetrics.density).toInt())
        }
    }

    fun render(state: AudioPlayerUiState) {
        durationMs = state.durationMs.coerceAtLeast(0)
        val position = state.positionMs.coerceIn(0, durationMs)
        binding.playerToggle.isEnabled = state.enabled
        binding.playerSeek.isEnabled = state.enabled && durationMs > 0
        binding.playerToggle.setIconResource(if (state.playing) R.drawable.ic_h4_pause else R.drawable.ic_h4_play)
        binding.playerToggle.contentDescription = context.getString(if (state.playing) R.string.preview_pause else R.string.preview_play)
        binding.playerDuration.text = DisplayFormat.durationMillis(durationMs)
        if (!userSeeking) {
            binding.playerPosition.text = DisplayFormat.durationMillis(position)
            binding.playerSeek.progress = if (durationMs == 0L) 0 else ((position.toDouble() / durationMs) * 1000).toInt()
        }
    }

    private fun positionForProgress(progress: Int): Long =
        (durationMs.toDouble() * progress.coerceIn(0, 1000) / 1000.0).toLong()
}
