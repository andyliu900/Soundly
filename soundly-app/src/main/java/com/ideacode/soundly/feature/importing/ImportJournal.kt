package com.ideacode.soundly.feature.importing

import android.content.Context

/** Stores only an operation ID; document access is not assumed to survive a restart. */
internal class ImportJournal(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("soundly_import", Context.MODE_PRIVATE)
    fun read(): String? = preferences.getString("pending", null)
    fun write(id: String): Boolean = synchronized(lock) {
        if (read() != null) false else preferences.edit().putString("pending", id).commit()
    }
    fun clear(id: String): Boolean = synchronized(lock) {
        if (read() == id) preferences.edit().remove("pending").commit() else false
    }
    private companion object { val lock = Any() }
}
