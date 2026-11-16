package com.ideacode.soundly.feature.editing

import java.util.Locale

/** Precise, locale-independent time entry for long and short recordings. */
internal object TrimTime {
    fun format(ms: Long): String {
        val safe = ms.coerceAtLeast(0)
        val seconds = safe / 1000
        val base = if (seconds >= 3600) String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
            else String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
        return if (safe % 1000 == 0L) base else base + String.format(Locale.ROOT, ".%03d", safe % 1000)
    }
    fun parse(value: String): Long? {
        val text = value.trim()
        val match = Regex("^(?:(\\d+):)?(\\d{1,2}):(\\d{2})(?:\\.(\\d{1,3}))?$").matchEntire(text) ?: return null
        val hours = match.groupValues[1].takeIf { it.isNotEmpty() }?.toLongOrNull() ?: if (match.groupValues[1].isEmpty()) 0L else return null
        val minutes = match.groupValues[2].toLongOrNull() ?: return null
        val seconds = match.groupValues[3].toLongOrNull() ?: return null
        if (seconds >= 60 || (match.groupValues[1].isNotEmpty() && minutes >= 60)) return null
        val millis = match.groupValues[4].padEnd(3, '0').ifEmpty { "0" }.toLongOrNull() ?: return null
        return try {
            Math.addExact(Math.multiplyExact(Math.addExact(Math.addExact(Math.multiplyExact(hours, 3600), Math.multiplyExact(minutes, 60)), seconds), 1000), millis)
        } catch (_: ArithmeticException) { null }
    }
}
