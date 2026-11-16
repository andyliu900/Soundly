package com.ideacode.soundly.common.ui

import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import kotlin.math.max

/** Applies bars, cutout and keyboard to a non-scrolling container, preserving its padding. */
fun View.applySoundlyInsets(consume: Boolean = false, enabled: () -> Boolean = { true }) {
    val left = paddingLeft
    val top = paddingTop
    val right = paddingRight
    val bottom = paddingBottom
    // Scrollable content must not draw over the transparent status/navigation bars.
    if (this is ViewGroup) clipToPadding = true
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        if (!enabled()) {
            view.updatePadding(left = left, top = top, right = right, bottom = bottom)
            return@setOnApplyWindowInsetsListener insets
        }
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
        view.updatePadding(
            left = left + bars.left,
            top = top + bars.top,
            right = right + bars.right,
            bottom = bottom + max(bars.bottom, keyboard.bottom)
        )
        if (consume) WindowInsetsCompat.CONSUMED else insets
    }
    ViewCompat.requestApplyInsets(this)
}
