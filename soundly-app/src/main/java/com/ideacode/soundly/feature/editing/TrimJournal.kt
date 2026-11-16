package com.ideacode.soundly.feature.editing

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class TrimJournalEntry(val resultVariantId: String?)
internal interface TrimJournal {
    suspend fun read(): TrimJournalEntry?
    suspend fun markPending()
    suspend fun markComplete(variantId: String)
    suspend fun clear()
}
internal class AndroidTrimJournal(context: Context, projectId: String, sourceId: String) : TrimJournal {
    private val prefs = context.applicationContext.getSharedPreferences("soundly_trim_journal", Context.MODE_PRIVATE)
    private val key = JSONObject().put("project", projectId).put("source", sourceId).toString()
    override suspend fun read(): TrimJournalEntry? = withContext(Dispatchers.IO) {
        val raw = prefs.getString(key, null) ?: return@withContext null
        try { TrimJournalEntry(JSONObject(raw).optString("variant").takeIf { it.isNotBlank() }) }
        catch (_: Exception) { TrimJournalEntry(null) }
    }
    override suspend fun markPending() = write(JSONObject().put("pending", true))
    override suspend fun markComplete(variantId: String) = write(JSONObject().put("variant", variantId))
    override suspend fun clear() = withContext(Dispatchers.IO) { check(prefs.edit().remove(key).commit()) }
    private suspend fun write(json: JSONObject) = withContext(Dispatchers.IO) { check(prefs.edit().putString(key, json.toString()).commit()) }
}
