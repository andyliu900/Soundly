package com.ideacode.soundly.common.ui

import java.util.Locale

object DisplayFormat {
    fun durationMillis(milliseconds: Long, alwaysShowHours: Boolean = false): String {
        val seconds = milliseconds.coerceAtLeast(0) / 1_000
        val hours = seconds / 3600
        val minutes = (seconds / 60) % 60
        return if (hours > 0 || alwaysShowHours) {
            String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds % 60)
        } else {
            String.format(Locale.ROOT, "%02d:%02d", minutes, seconds % 60)
        }
    }

    fun byteSize(bytes: Long, locale: Locale = Locale.getDefault()): String {
        val safeBytes = bytes.coerceAtLeast(0)
        val units = arrayOf("B", "KB", "MB", "GB", "TB", "PB", "EB")
        var value = safeBytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return if (unit == 0) "$safeBytes B" else String.format(locale, "%.1f %s", value, units[unit])
    }
}
