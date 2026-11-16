package com.ideacode.soundly.feature.enhancement

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.ideacode.soundly.R

/** A bounded, inset ring. Progress is supplied by the task, never driven by a timer. */
class EnhancementProgressRingView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val density = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 16f * density
        color = ContextCompat.getColor(context, R.color.enhancement_progress_track)
    }
    private val arc = Paint(track).apply { strokeCap = Paint.Cap.ROUND }
    private val bounds = RectF()
    private var fraction = 0f
    private var target = 0
    private var animator: ValueAnimator? = null

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun setProgress(percent: Int, animate: Boolean = true) {
        val next = percent.coerceIn(0, 100)
        if (target == next) return
        target = next
        animator?.cancel()
        if (!animate || !isLaidOut || !ValueAnimator.areAnimatorsEnabled()) {
            fraction = next / 100f
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(fraction, next / 100f).apply {
            duration = 250
            addUpdateListener { fraction = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val inset = track.strokeWidth / 2 + density
        val side = minOf(w - paddingLeft - paddingRight, h - paddingTop - paddingBottom).toFloat()
        val left = paddingLeft + (w - paddingLeft - paddingRight - side) / 2
        val top = paddingTop + (h - paddingTop - paddingBottom - side) / 2
        bounds.set(left + inset, top + inset, left + side - inset, top + side - inset)
        arc.shader = SweepGradient(bounds.centerX(), bounds.centerY(), intArrayOf(
            ContextCompat.getColor(context, R.color.enhancement_progress_gradient_start),
            ContextCompat.getColor(context, R.color.enhancement_progress_gradient_end),
            ContextCompat.getColor(context, R.color.enhancement_progress_gradient_start)
        ), floatArrayOf(0f, .72f, 1f)).apply {
            setLocalMatrix(Matrix().apply { setRotate(-90f, bounds.centerX(), bounds.centerY()) })
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (bounds.width() <= 0) return
        canvas.drawOval(bounds, track)
        if (fraction > 0) canvas.drawArc(bounds, -90f, 360f * fraction, false, arc)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        fraction = target / 100f
        super.onDetachedFromWindow()
    }
}
