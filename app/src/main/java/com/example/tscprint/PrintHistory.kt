package com.example.tscprint

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

class PrintHistory(context: Context) {

    data class Entry(
        val uri: String,
        val name: String,
        val timestamp: Long,
        val pages: Int,
        val copies: Int,
        val status: String
    )

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun list(): List<Entry> {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        return runCatching {
            val json = JSONArray(raw)
            (0 until json.length()).map { index ->
                val item = json.getJSONObject(index)
                Entry(
                    item.getString("uri"),
                    item.getString("name"),
                    item.getLong("timestamp"),
                    item.getInt("pages"),
                    item.getInt("copies"),
                    item.getString("status")
                )
            }
        }.getOrDefault(emptyList())
    }

    fun add(uri: Uri, name: String, pages: Int, copies: Int, status: String): Entry {
        val entry = Entry(uri.toString(), name, System.currentTimeMillis(), pages, copies, status)
        val all = (list() + entry).takeLast(MAX_ENTRIES)
        val json = JSONArray()
        all.forEach { item ->
            json.put(JSONObject().apply {
                put("uri", item.uri)
                put("name", item.name)
                put("timestamp", item.timestamp)
                put("pages", item.pages)
                put("copies", item.copies)
                put("status", item.status)
            })
        }
        prefs.edit().putString(KEY_ENTRIES, json.toString()).apply()
        return entry
    }

    fun clear() = prefs.edit().remove(KEY_ENTRIES).apply()

    fun updateStatus(timestamp: Long, status: String) {
        val updated = list().map { entry ->
            if (entry.timestamp == timestamp) entry.copy(status = status) else entry
        }
        val json = JSONArray()
        updated.forEach { item ->
            json.put(JSONObject().apply {
                put("uri", item.uri)
                put("name", item.name)
                put("timestamp", item.timestamp)
                put("pages", item.pages)
                put("copies", item.copies)
                put("status", item.status)
            })
        }
        prefs.edit().putString(KEY_ENTRIES, json.toString()).apply()
    }

    companion object {
        private const val PREFS = "tsc_print_history"
        private const val KEY_ENTRIES = "entries"
        private const val MAX_ENTRIES = 20
    }
}
