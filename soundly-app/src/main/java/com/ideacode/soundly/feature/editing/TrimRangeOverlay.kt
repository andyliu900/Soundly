package com.ideacode.soundly.feature.editing

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.ideacode.soundly.R
import com.ideacode.soundly.common.ui.DisplayFormat

/** Decorative selection, drawn behind the accessible Material range slider. */
class TrimRangeOverlay @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var start = 0L; private var end = 0L; private var duration = 0L
    private var trackInset = 22f * density
    fun setTrackInset(pixels: Int) { trackInset = pixels.coerceAtLeast(0).toFloat(); invalidate() }
    fun setSelection(startMs: Long, endMs: Long, durationMs: Long) { start = startMs; end = endMs; duration = durationMs; invalidate() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (duration <= 0L) return
        val inset = trackInset
        val width = (width - inset * 2).coerceAtLeast(0f)
        val left = inset + width * (start.toDouble() / duration).toFloat().coerceIn(0f, 1f)
        val right = inset + width * (end.toDouble() / duration).toFloat().coerceIn(0f, 1f)
        val center = height / 2f
        val primary = ContextCompat.getColor(context, R.color.preview_primary)
        paint.color = ContextCompat.getColor(context, R.color.preview_tint)
        paint.alpha = 45
        canvas.drawRect(left, 34f * density, right, height - 22f * density, paint)
        paint.color = primary; paint.strokeWidth = 1.5f * density
        canvas.drawLine(left, 25f * density, left, height - 16f * density, paint)
        canvas.drawLine(right, 25f * density, right, height - 16f * density, paint)
        for (x in listOf(left, right)) {
            canvas.drawRoundRect(x - 9f * density, center - 21f * density, x + 9f * density, center + 21f * density, 9f * density, 9f * density, paint)
            paint.color = android.graphics.Color.WHITE; paint.strokeWidth = 2f * density; paint.strokeCap = Paint.Cap.ROUND
            canvas.drawLine(x - 2f * density, center - 7f * density, x - 2f * density, center + 7f * density, paint)
            canvas.drawLine(x + 2f * density, center - 7f * density, x + 2f * density, center + 7f * density, paint)
            paint.color = primary
        }
        paint.textSize = 12f * resources.displayMetrics.scaledDensity; paint.textAlign = Paint.Align.CENTER
        canvas.drawText(DisplayFormat.durationMillis(start), left.coerceIn(24f * density, (getWidth() - 24f * density).coerceAtLeast(24f * density)), 17f * density, paint)
        canvas.drawText(DisplayFormat.durationMillis(end), right.coerceIn(24f * density, (getWidth() - 24f * density).coerceAtLeast(24f * density)), 17f * density, paint)
    }
}
