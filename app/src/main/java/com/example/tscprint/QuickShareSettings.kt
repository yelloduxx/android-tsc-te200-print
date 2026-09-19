package com.example.tscprint

import android.content.Context

class QuickShareSettings(context: Context) {

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

    companion object {
        private const val PREFS = "tsc_quick_share"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_CANDIDATES = "candidates"
        private const val KEY_ALLOWED = "allowed"
    }
}
