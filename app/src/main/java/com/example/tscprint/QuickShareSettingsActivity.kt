package com.example.tscprint

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.R as MaterialR
import com.google.android.material.switchmaterial.SwitchMaterial

class QuickShareSettingsActivity : Activity() {

    private val quickShare by lazy { QuickShareSettings(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle(R.string.quick_share_settings)
        setContentView(buildUi())
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    private fun buildUi(): ViewGroup {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(themeColor(MaterialR.attr.colorSurface))
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.quick_share_settings)
            textSize = 24f
            setTextColor(themeColor(MaterialR.attr.colorOnSurface))
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.quick_share_explanation)
            setTextColor(themeColor(MaterialR.attr.colorOnSurfaceVariant))
            setPadding(0, dp(8), 0, dp(12))
        })
        root.addView(SwitchMaterial(this).apply {
            text = getString(R.string.quick_share_enabled)
            isChecked = quickShare.enabled
            setOnCheckedChangeListener { _, checked -> quickShare.enabled = checked }
        })

        val scrollContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val apps = availableApps()
        if (apps.isEmpty()) {
            scrollContent.addView(TextView(this).apply {
                text = getString(R.string.quick_share_no_apps)
                setTextColor(themeColor(MaterialR.attr.colorOnSurfaceVariant))
                setPadding(0, dp(20), 0, dp(20))
            })
        } else {
            scrollContent.addView(TextView(this).apply {
                text = getString(R.string.quick_share_choose_apps)
                setTextColor(themeColor(MaterialR.attr.colorOnSurfaceVariant))
                setPadding(0, dp(16), 0, dp(4))
            })
            apps.forEach { app ->
                scrollContent.addView(MaterialCheckBox(this).apply {
                    text = "${app.label}\n${app.packageName}"
                    isChecked = app.packageName in quickShare.allowedPackages()
                    setOnCheckedChangeListener { _, checked ->
                        quickShare.setAllowed(app.packageName, checked)
                    }
                    contentDescription = app.packageName
                })
            }
        }
        root.addView(ScrollView(this).apply { addView(scrollContent) }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        root.addView(MaterialButton(this).apply {
            text = getString(R.string.close)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(52)
        ).apply { topMargin = dp(12) })
        return root
    }

    private data class AppEntry(val packageName: String, val label: String)

    private fun availableApps(): List<AppEntry> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val entries = packageManager.queryIntentActivities(launcherIntent, 0)
            .mapNotNull { info ->
                val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
                if (packageName == packageName()) return@mapNotNull null
                AppEntry(packageName, info.loadLabel(packageManager).toString())
            }
            .distinctBy { it.packageName }
            .toMutableList()

        // Keep previously discovered/selected packages visible even if their launcher
        // activity is not exposed by the current device's package-visibility rules.
        (quickShare.candidates() + quickShare.allowedPackages()).forEach { packageName ->
            if (packageName != packageName() && entries.none { it.packageName == packageName }) {
                entries += AppEntry(packageName, applicationLabel(packageName))
            }
        }
        return entries.sortedBy { it.label.lowercase() }
    }

    private fun applicationLabel(packageName: String): String = runCatching {
        val info = packageManager.getApplicationInfo(packageName, 0)
        packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(packageName)

    private fun packageName(): String = applicationContext.packageName

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun themeColor(attr: Int): Int {
        val ta = obtainStyledAttributes(intArrayOf(attr))
        val color = ta.getColor(0, Color.GRAY)
        ta.recycle()
        return color
    }
}
