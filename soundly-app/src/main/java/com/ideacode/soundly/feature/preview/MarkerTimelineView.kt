package com.ideacode.soundly.feature.preview

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextUtils
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.ideacode.soundly.R
import com.ideacode.soundly.common.ui.DisplayFormat
import com.ideacode.soundly_sdk.api.model.domain.AudioMarker

/** Decorative timeline: the accessible, actionable marker list is immediately below the card. */
class MarkerTimelineView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f * resources.displayMetrics.scaledDensity
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        color = ContextCompat.getColor(context, R.color.preview_primary)
    }
    private val chip = RectF()
    private var markers: List<AudioMarker> = emptyList()
    private var durationMs = 0L
    private val primary = ContextCompat.getColor(context, R.color.preview_primary)
    private val chipColor = ContextCompat.getColor(context, R.color.preview_tint)

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun setMarkers(items: List<AudioMarker>, duration: Long) {
        markers = items.sortedBy { it.positionMs }
        durationMs = duration
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (durationMs <= 0 || width <= 0) return
        var previousRight = Float.NEGATIVE_INFINITY
        markers.forEachIndexed { index, marker ->
            if (marker.positionMs !in 0..durationMs) return@forEachIndexed
            val x = (marker.positionMs.toDouble() / durationMs * width).toFloat().coerceIn(density, width - density)
            paint.color = primary; paint.strokeWidth = 1.5f * density; paint.strokeCap = Paint.Cap.ROUND
            canvas.drawLine(x, 1f * density, x, 66f * density, paint)
            val name = marker.label?.takeIf { it.isNotBlank() } ?: context.getString(R.string.h5_marker_number, index + 1)
            val text = context.getString(R.string.preview_timeline_marker, name, DisplayFormat.durationMillis(marker.positionMs))
            val maxTextWidth = (width - 16f * density).coerceAtLeast(0f)
            val fitted = TextUtils.ellipsize(text, textPaint, maxTextWidth, TextUtils.TruncateAt.END).toString()
            val labelWidth = textPaint.measureText(fitted) + 16f * density
            val left = (x - labelWidth / 2).coerceIn(0f, (width - labelWidth).coerceAtLeast(0f))
            if (left < previousRight + 4 * density) return@forEachIndexed
            chip.set(left, 67f * density, left + labelWidth, 88f * density)
            paint.color = chipColor
            canvas.drawRoundRect(chip, 12f * density, 12f * density, paint)
            val baseline = chip.centerY() - (textPaint.ascent() + textPaint.descent()) / 2
            canvas.drawText(fitted, left + 8f * density, baseline, textPaint)
            previousRight = chip.right
        }
    }
}
