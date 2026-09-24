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

    fun list(): List<Entry> = synchronized(LOCK) {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        runCatching {
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
        return synchronized(LOCK) {
            val entry = Entry(uri.toString(), name, System.currentTimeMillis(), pages, copies, status)
            val all = (list() + entry).takeLast(MAX_ENTRIES)
            save(all)
            entry
        }
    }

    fun clear() = synchronized(LOCK) { prefs.edit().remove(KEY_ENTRIES).commit() }

    fun remove(timestamp: Long) {
        synchronized(LOCK) { save(list().filterNot { it.timestamp == timestamp }) }
    }

    fun updateStatus(timestamp: Long, status: String) {
        synchronized(LOCK) {
            val updated = list().map { entry ->
                if (entry.timestamp == timestamp) entry.copy(status = status) else entry
            }
            save(updated)
        }
    }

    private fun save(entries: List<Entry>) {
        val json = JSONArray()
        entries.forEach { item ->
            json.put(JSONObject().apply {
                put("uri", item.uri)
                put("name", item.name)
                put("timestamp", item.timestamp)
                put("pages", item.pages)
                put("copies", item.copies)
                put("status", item.status)
            })
        }
        prefs.edit().putString(KEY_ENTRIES, json.toString()).commit()
    }

    companion object {
        private const val PREFS = "tsc_print_history"
        private const val KEY_ENTRIES = "entries"
        private const val MAX_ENTRIES = 20
        private val LOCK = Any()
    }
}
