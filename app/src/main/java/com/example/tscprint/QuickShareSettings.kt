package com.example.tscprint

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class QuickShareSettings(context: Context) {

    data class AppEntry(val packageName: String, val label: String)

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    fun candidates(): Set<String> = prefs.getStringSet(KEY_CANDIDATES, emptySet()).orEmpty()

    fun recordCandidate(packageName: String) {
        val updated = candidates() + packageName
        prefs.edit().putStringSet(KEY_CANDIDATES, updated).apply()
    }

    fun allowedPackages(): Set<String> = prefs.getStringSet(KEY_ALLOWED, emptySet()).orEmpty()

    fun isAllowed(packageName: String?): Boolean =
        enabled && packageName != null && packageName in allowedPackages()

    fun setAllowed(packageName: String, allowed: Boolean) {
        val updated = allowedPackages().toMutableSet()
        if (allowed) updated += packageName else updated -= packageName
        prefs.edit().putStringSet(KEY_ALLOWED, updated).apply()
    }

    fun cachedApps(): List<AppEntry> {
        val raw = prefs.getString(KEY_APP_CACHE, null) ?: return emptyList()
        return runCatching {
            val json = JSONArray(raw)
            (0 until json.length()).map { index ->
                val item = json.getJSONObject(index)
                AppEntry(item.getString("package"), item.getString("label"))
            }
        }.getOrDefault(emptyList())
    }

    fun saveCachedApps(apps: List<AppEntry>) {
        val json = JSONArray()
        apps.forEach { app ->
            json.put(JSONObject().apply {
                put("package", app.packageName)
                put("label", app.label)
            })
        }
        prefs.edit().putString(KEY_APP_CACHE, json.toString()).apply()
    }

    companion object {
        private const val PREFS = "tsc_quick_share"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_CANDIDATES = "candidates"
        private const val KEY_ALLOWED = "allowed"
        private const val KEY_APP_CACHE = "app_cache"
    }
}
