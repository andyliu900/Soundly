package com.ideacode.soundly.feature.recording

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/** A bounded history of actual SDK waveform amplitudes; silence stays visually quiet. */
class LiveWaveformView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private var samples: List<Float> = emptyList()
    var paused: Boolean = false
        set(value) { field = value; invalidate() }
    fun submit(values: List<Float>) { samples = values; invalidate() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val step = 5f * density
        val count = (width / step).toInt().coerceAtLeast(1)
        val visible = samples.takeLast(count)
        val left = (width - visible.size * step) / 2f + step / 2
        paint.strokeWidth = 2.4f * density
        paint.shader = if (paused) null else LinearGradient(0f, 0f, width.toFloat(), 0f,
            intArrayOf(Color.rgb(255, 103, 105), Color.rgb(218, 100, 193), Color.rgb(111, 90, 245)), null, Shader.TileMode.CLAMP)
        paint.color = if (paused) Color.rgb(160, 152, 215) else Color.WHITE
        val center = height / 2f
        visible.forEachIndexed { i, sample ->
            val half = max(density, sample * height * .44f)
            val x = left + i * step
            canvas.drawLine(x, center - half, x, center + half, paint)
        }
        paint.shader = null
    }
}
