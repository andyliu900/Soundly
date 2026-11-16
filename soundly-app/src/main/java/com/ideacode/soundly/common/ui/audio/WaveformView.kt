package com.ideacode.soundly.common.ui.audio

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.ideacode.soundly.R
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Presentation-only waveform. The caller supplies normalized real samples and playback position. */
class WaveformView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 3f * density
    }
    private var samples = FloatArray(0)
    private var progress = 0f
    private var selection: ClosedFloatingPointRange<Float>? = null
    private var activeColor = ContextCompat.getColor(context, R.color.soundly_primary)
    private var inactiveColor = ContextCompat.getColor(context, R.color.soundly_waveform_inactive)
    private var minimumStep = 6f * density
    private var shadeUnplayed = false

    init {
        // Playback controls expose accessibility separately; the drawing is decorative.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setColors(active: Int, inactive: Int) {
        activeColor = active
        inactiveColor = inactive
        invalidate()
    }

    /** Optional visual density; peak reduction and playback position still use the supplied samples. */
    fun setBarStyle(widthDp: Float, minimumStepDp: Float, shadeUnplayedByAmplitude: Boolean = false) {
        paint.strokeWidth = widthDp.coerceAtLeast(1f) * density
        minimumStep = max(widthDp + 1f, minimumStepDp) * density
        shadeUnplayed = shadeUnplayedByAmplitude
        invalidate()
    }

    /** Copies, bounds and peak-reduces input so an indefinitely growing stream is never retained. */
    fun setSamples(input: List<Float>) {
        if (input.isEmpty()) {
            samples = FloatArray(0)
        } else {
            val count = min(input.size, MAX_SAMPLES)
            samples = FloatArray(count) { index ->
                val start = index.toLong() * input.size / count
                val end = (index + 1L) * input.size / count
                var peak = 0f
                for (i in start.toInt() until end.toInt()) {
                    val value = input[i]
                    if (value.isFinite()) peak = max(peak, abs(value).coerceAtMost(1f))
                }
                peak
            }
        }
        invalidate()
    }

    fun setProgress(fraction: Float) {
        progress = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f
        invalidate()
    }

    /** Highlights a selected interval; pass null to return to playback progress coloring. */
    fun setSelection(start: Float?, end: Float?) {
        selection = if (start == null || end == null || !start.isFinite() || !end.isFinite()) null
            else start.coerceIn(0f, 1f)..end.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (samples.isEmpty()) return
        val availableWidth = (width - paddingLeft - paddingRight).toFloat().coerceAtLeast(0f)
        val availableHeight = (height - paddingTop - paddingBottom).toFloat().coerceAtLeast(0f)
        if (availableWidth <= 0f || availableHeight <= 0f) return
        val count = min(samples.size, max(1, (availableWidth / minimumStep).toInt()))
        val step = availableWidth / count
        val center = paddingTop + availableHeight / 2f
        for (i in 0 until count) {
            val start = i * samples.size / count
            val end = max(start + 1, ceil((i + 1.0) * samples.size / count).toInt()).coerceAtMost(samples.size)
            var peak = 0f
            for (j in start until end) peak = max(peak, samples[j])
            val halfHeight = max(density, peak * (availableHeight - paint.strokeWidth).coerceAtLeast(0f) / 2f)
            paint.color = when {
                selection != null -> if ((i + 0.5f) / count in selection!!) activeColor else inactiveColor
                (i + 0.5f) / count <= progress -> activeColor
                shadeUnplayed -> ColorUtils.blendARGB(inactiveColor, activeColor, .15f + .7f * peak)
                else -> inactiveColor
            }
            val x = paddingLeft + step * (i + 0.5f)
            canvas.drawLine(x, center - halfHeight, x, center + halfHeight, paint)
        }
    }

    companion object { private const val MAX_SAMPLES = 1024 }
}
