package com.ideacode.soundly.common.ui

import android.content.res.Configuration
import android.os.Build
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.google.android.material.appbar.MaterialToolbar
import com.ideacode.soundly.R

/** Apply enhancement page colors and restore the app appearance on navigation. */
fun AppCompatActivity.applyEnhancementAppearance(root: View, toolbar: MaterialToolbar, enabled: Boolean, compactToolbar: Boolean = enabled) {
    val toolbarHeight = if (compactToolbar) (56 * resources.displayMetrics.density).toInt() else
        obtainStyledAttributes(intArrayOf(androidx.appcompat.R.attr.actionBarSize)).let { attributes ->
            val height = attributes.getDimensionPixelSize(0, (56 * resources.displayMetrics.density).toInt())
            attributes.recycle()
            height
        }
    if (toolbar.layoutParams.height != toolbarHeight) {
        toolbar.layoutParams = toolbar.layoutParams.apply { height = toolbarHeight }
    }
    val background = ContextCompat.getColor(this, if (enabled) R.color.enhancement_progress_background else R.color.soundly_background)
    // API 26–28 may otherwise show the theme's default system bar color over edge-to-edge content.
    if (Build.VERSION.SDK_INT <= 28) {
        @Suppress("DEPRECATION")
        window.statusBarColor = background
        @Suppress("DEPRECATION")
        window.navigationBarColor = background
    }
    root.setBackgroundColor(background)
    toolbar.setBackgroundColor(background)
    toolbar.setTitleTextColor(ContextCompat.getColor(this, if (enabled) R.color.enhancement_progress_text else R.color.soundly_on_surface))
    toolbar.setNavigationIconTint(ContextCompat.getColor(this, if (enabled) R.color.enhancement_progress_primary else R.color.soundly_primary))
    toolbar.overflowIcon?.mutate()?.setTint(ContextCompat.getColor(this, if (enabled) R.color.enhancement_progress_primary else R.color.soundly_primary))
    val lightBars = enabled || resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES
    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = lightBars
        isAppearanceLightNavigationBars = lightBars
    }
}
