package com.example.tscprint

import android.content.Context

class PrintSettings(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var widthMm: Int
        get() = prefs.getInt(KEY_WIDTH, 58)
        set(value) = prefs.edit().putInt(KEY_WIDTH, value).apply()

    var heightMm: Int
        get() = prefs.getInt(KEY_HEIGHT, 30)
        set(value) = prefs.edit().putInt(KEY_HEIGHT, value).apply()

    var gapMm: Int
        get() = prefs.getInt(KEY_GAP, 2)
        set(value) = prefs.edit().putInt(KEY_GAP, value).apply()

    var threshold: Int
        get() = prefs.getInt(KEY_THRESHOLD, 128)
        set(value) = prefs.edit().putInt(KEY_THRESHOLD, value).apply()

    var density: Int
        get() = prefs.getInt(KEY_DENSITY, 8)
        set(value) = prefs.edit().putInt(KEY_DENSITY, value).apply()

    var copies: Int
        get() = prefs.getInt(KEY_COPIES, 1)
        set(value) = prefs.edit().putInt(KEY_COPIES, value).apply()

    var dither: Boolean
        get() = prefs.getBoolean(KEY_DITHER, false)
        set(value) = prefs.edit().putBoolean(KEY_DITHER, value).apply()

    var cover: Boolean
        get() = prefs.getBoolean(KEY_COVER, false)
        set(value) = prefs.edit().putBoolean(KEY_COVER, value).apply()

    var trim: Boolean
        get() = prefs.getBoolean(KEY_TRIM, false)
        set(value) = prefs.edit().putBoolean(KEY_TRIM, value).apply()

    var vendorId: Int
        get() = prefs.getInt(KEY_VID, 0)
        set(value) = prefs.edit().putInt(KEY_VID, value).apply()

    var productId: Int
        get() = prefs.getInt(KEY_PID, 0)
        set(value) = prefs.edit().putInt(KEY_PID, value).apply()

    companion object {
        private const val PREFS = "tsc_print"
        private const val KEY_WIDTH = "width_mm"
        private const val KEY_HEIGHT = "height_mm"
        private const val KEY_GAP = "gap_mm"
        private const val KEY_THRESHOLD = "threshold"
        private const val KEY_DENSITY = "density"
        private const val KEY_COPIES = "copies"
        private const val KEY_DITHER = "dither"
        private const val KEY_COVER = "cover"
        private const val KEY_TRIM = "trim"
        private const val KEY_VID = "vendor_id"
        private const val KEY_PID = "product_id"
    }
}
