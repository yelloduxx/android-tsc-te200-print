package com.example.tscprint

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import java.io.File
import java.io.FileOutputStream
import org.json.JSONArray
import org.json.JSONObject

class QuickShareSettings(context: Context) {

    data class AppEntry(val packageName: String, val label: String)

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val iconDirectory = appContext.getDir(ICON_DIRECTORY, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    fun candidates(): Set<String> = prefs.getStringSet(KEY_CANDIDATES, emptySet()).orEmpty()

    fun recordCandidate(packageName: String) {
        val updated = candidates() + packageName
        prefs.edit().putStringSet(KEY_CANDIDATES, updated).apply()
    }

    fun allowedPackages(): Set<String> = prefs.getStringSet(KEY_ALLOWED, emptySet()).orEmpty()

    fun isAllowed(action: String?, packageName: String?): Boolean = QuickSharePolicy.shouldPrintSilently(
        enabled,
        action,
        packageName,
        allowedPackages()
    )

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

    fun cachedIcon(packageName: String): Drawable? {
        val bitmap = iconCache.get(packageName)
            ?: BitmapFactory.decodeFile(iconFile(packageName).absolutePath)?.also {
                iconCache.put(packageName, it)
            }
            ?: return null
        return BitmapDrawable(appContext.resources, bitmap)
    }

    fun saveIcon(packageName: String, drawable: Drawable) {
        val size = 96
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        FileOutputStream(iconFile(packageName)).use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        iconCache.put(packageName, bitmap)
    }

    private fun iconFile(packageName: String): File {
        val safeName = packageName.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        return File(iconDirectory, "$safeName.png")
    }

    companion object {
        private const val PREFS = "tsc_quick_share"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_CANDIDATES = "candidates"
        private const val KEY_ALLOWED = "allowed"
        private const val KEY_APP_CACHE = "app_cache"
        private const val ICON_DIRECTORY = "quick_share_icons"
        private val iconCache = object : LruCache<String, Bitmap>(4 * 1024) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
        }
    }
}
