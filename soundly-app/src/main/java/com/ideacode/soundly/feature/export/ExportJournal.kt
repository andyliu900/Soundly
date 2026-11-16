package com.ideacode.soundly.feature.export

import android.content.Context
import com.ideacode.soundly_sdk.api.model.domain.AudioFormat
import com.ideacode.soundly_sdk.api.model.export.ExportQuality
import com.ideacode.soundly_sdk.api.model.export.ExportResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Small durable record; audio and progress are never stored in the journal. */
data class ExportJournalEntry(val pending: Boolean, val result: ExportResult? = null)
interface ExportJournal {
    suspend fun read(): ExportJournalEntry?
    suspend fun markPending()
    suspend fun markComplete(result: ExportResult)
    suspend fun clear()
}
object NoExportJournal : ExportJournal {
    override suspend fun read(): ExportJournalEntry? = null
    override suspend fun markPending() = Unit
    override suspend fun markComplete(result: ExportResult) = Unit
    override suspend fun clear() = Unit
}

class AndroidExportJournal(context: Context, projectId: String, variantId: String) : ExportJournal {
    private val preferences = context.applicationContext.getSharedPreferences("soundly_export_journal", Context.MODE_PRIVATE)
    private val key = JSONObject().put("project", projectId).put("variant", variantId).toString()
    override suspend fun read(): ExportJournalEntry? = withContext(Dispatchers.IO) {
        val raw = preferences.getString(key, null) ?: return@withContext null
        try {
            val json = JSONObject(raw)
            if (json.optBoolean("pending", false)) ExportJournalEntry(pending = true)
            else {
                val result = ExportResult(json.getString("uri"), json.getString("name"), AudioFormat.valueOf(json.getString("format")),
                    json.getLong("size"), ExportQuality.valueOf(json.getString("quality")))
                ExportJournalEntry(pending = false, result = result)
            }
        } catch (_: Exception) { ExportJournalEntry(pending = true) }
    }
    override suspend fun markPending() = write(JSONObject().put("pending", true))
    override suspend fun markComplete(result: ExportResult) = write(JSONObject().put("pending", false).put("uri", result.destinationUri)
        .put("name", result.displayName).put("format", result.format.name).put("size", result.fileSizeBytes).put("quality", result.quality.name))
    override suspend fun clear() = withContext(Dispatchers.IO) { check(preferences.edit().remove(key).commit()) }
    private suspend fun write(value: JSONObject) = withContext(Dispatchers.IO) { check(preferences.edit().putString(key, value.toString()).commit()) }
}
