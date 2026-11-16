package com.ideacode.soundly.feature.settings

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.ideacode.soundly.R

/** A decorative, proportional view of the storage snapshot; labels provide the accessible values. */
class StorageUsageBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = RectF()
    private val clip = Path()
    private var values = LongArray(4)
    private val colors = intArrayOf(R.color.storage_original, R.color.storage_variant, R.color.storage_cache, R.color.storage_other)

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun setUsage(originals: Long, variants: Long, cache: Long, other: Long) {
        values = longArrayOf(originals, variants, cache, other).map { it.coerceAtLeast(0) }.toLongArray()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        bounds.set(0f, 0f, width.toFloat(), height.toFloat())
        val radius = height / 2f
        paint.color = ContextCompat.getColor(context, R.color.storage_track)
        canvas.drawRoundRect(bounds, radius, radius, paint)
        val total = values.sumOf { it.toDouble() }
        if (total <= 0.0 || width <= 0 || height <= 0) return
        clip.reset(); clip.addRoundRect(bounds, radius, radius, Path.Direction.CW)
        canvas.save(); canvas.clipPath(clip)
        var left = 0f
        values.forEachIndexed { index, value ->
            val right = left + (width * (value / total)).toFloat()
            paint.color = ContextCompat.getColor(context, colors[index])
            canvas.drawRect(left, 0f, right, height.toFloat(), paint)
            left = right
        }
        canvas.restore()
    }
}
