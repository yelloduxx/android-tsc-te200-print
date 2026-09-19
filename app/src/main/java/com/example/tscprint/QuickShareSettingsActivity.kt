package com.example.tscprint

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.R as MaterialR
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.concurrent.Executors

class QuickShareSettingsActivity : Activity() {

    private val quickShare by lazy { QuickShareSettings(this) }
    private val refreshExecutor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var appsContainer: LinearLayout
    private lateinit var searchField: TextInputEditText
    private var allApps: List<QuickShareSettings.AppEntry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle(R.string.quick_share_settings)
        setContentView(buildUi())
        refreshAppsInBackground()
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onDestroy() {
        refreshExecutor.shutdown()
        super.onDestroy()
    }

    private fun buildUi(): ViewGroup {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(themeColor(MaterialR.attr.colorSurface))
        }
        root.setOnApplyWindowInsetsListener { _, insets ->
            val top: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                top = bars.top
                bottom = bars.bottom
            } else {
                @Suppress("DEPRECATION")
                top = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                bottom = insets.systemWindowInsetBottom
            }
            root.setPadding(dp(16), top + dp(8), dp(16), dp(16) + bottom)
            insets
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

        root.addView(TextView(this).apply {
            text = getString(R.string.quick_share_choose_apps)
            setTextColor(themeColor(MaterialR.attr.colorOnSurfaceVariant))
            setPadding(0, dp(16), 0, dp(4))
        })
        val searchLayout = TextInputLayout(this).apply {
            hint = getString(R.string.quick_share_search)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        searchField = TextInputEditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        searchLayout.addView(searchField)
        root.addView(searchLayout)

        appsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scrollContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(appsContainer)
        }
        root.addView(ScrollView(this).apply { addView(scrollContent) }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                renderApps(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        allApps = quickShare.cachedApps()
        renderApps("")
        root.addView(MaterialButton(this).apply {
            text = getString(R.string.close)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(52)
        ).apply { topMargin = dp(12) })
        return root
    }

    private fun renderApps(query: String) {
        if (!::appsContainer.isInitialized) return
        appsContainer.removeAllViews()
        val normalized = query.trim().lowercase()
        val allowed = quickShare.allowedPackages()
        val apps = allApps
            .filter { normalized.isEmpty() ||
                it.label.lowercase().contains(normalized) || it.packageName.contains(normalized) }
            .sortedWith(compareByDescending<QuickShareSettings.AppEntry> { it.packageName in allowed }
                .thenBy { it.label.lowercase() })
        if (apps.isEmpty()) {
            appsContainer.addView(TextView(this).apply {
                text = getString(R.string.quick_share_no_apps)
                setTextColor(themeColor(MaterialR.attr.colorOnSurfaceVariant))
                setPadding(0, dp(20), 0, dp(20))
            })
            return
        }
        apps.forEach { app ->
            appsContainer.addView(MaterialCheckBox(this).apply {
                text = "${app.label}\n${app.packageName}"
                isChecked = app.packageName in allowed
                setOnCheckedChangeListener { _, checked ->
                    quickShare.setAllowed(app.packageName, checked)
                    renderApps(searchField.text?.toString().orEmpty())
                }
                contentDescription = app.packageName
            })
        }
    }

    private fun discoverApps(): List<QuickShareSettings.AppEntry> {
        return packageManager.getInstalledApplications(0)
            .asSequence()
            .filter { it.packageName != packageName() }
            .filter { it.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0 }
            .filter { it.enabled }
            .filter { packageManager.getLaunchIntentForPackage(it.packageName) != null }
            .map { QuickShareSettings.AppEntry(it.packageName, packageManager.getApplicationLabel(it).toString()) }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    private fun refreshAppsInBackground() {
        refreshExecutor.execute {
            val fresh = discoverApps()
            quickShare.saveCachedApps(fresh)
            main.post {
                if (isFinishing || isDestroyed) return@post
                if (fresh != allApps) {
                    allApps = fresh
                    renderApps(searchField.text?.toString().orEmpty())
                }
            }
        }
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
