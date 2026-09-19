package com.example.tscprint

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.hardware.usb.UsbDevice
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.R as MaterialR

class SettingsActivity : Activity() {

    private val codes = arrayOf(LocaleHelper.ENGLISH, LocaleHelper.RUSSIAN)

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle(R.string.settings_title)
        setContentView(buildUi())
    }

    private fun buildUi(): ViewGroup {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColor(MaterialR.attr.colorSurface))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }
        root.addView(content)
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
            content.setPadding(dp(16), top + dp(8), dp(16), dp(16) + bottom)
            insets
        }

        content.addView(TextView(this).apply {
            text = getString(R.string.settings_title)
            textSize = 24f
            setTextColor(themeColor(MaterialR.attr.colorOnSurface))
        })

        content.addView(sectionTitle(R.string.language_label))
        val languageGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
        }
        codes.forEachIndexed { index, code ->
            languageGroup.addView(MaterialRadioButton(this).apply {
                id = View.generateViewId()
                text = if (code == LocaleHelper.ENGLISH) {
                    getString(R.string.language_english)
                } else {
                    getString(R.string.language_russian)
                }
                isChecked = code == LocaleHelper.getLanguage(this@SettingsActivity)
            })
        }
        languageGroup.setOnCheckedChangeListener { group, checkedId ->
            val index = (0 until group.childCount).firstOrNull {
                group.getChildAt(it).id == checkedId
            } ?: return@setOnCheckedChangeListener
            val code = codes[index]
            if (code != LocaleHelper.getLanguage(this)) {
                LocaleHelper.setLanguage(this, code)
                recreate()
            }
        }
        content.addView(languageGroup)

        content.addView(sectionTitle(R.string.btn_diagnostics))
        content.addView(MaterialButton(this).apply {
            text = getString(R.string.btn_diagnostics)
            setOnClickListener { showDiagnostics() }
        }, fullButtonParams())

        content.addView(sectionTitle(R.string.btn_quick_share))
        content.addView(TextView(this).apply {
            text = getString(R.string.quick_share_explanation)
            setTextColor(themeColor(MaterialR.attr.colorOnSurfaceVariant))
        })
        content.addView(MaterialButton(this).apply {
            text = getString(R.string.btn_quick_share)
            setOnClickListener {
                startActivity(Intent(this@SettingsActivity, QuickShareSettingsActivity::class.java))
            }
        }, fullButtonParams())

        return root
    }

    private fun showDiagnostics() {
        val devices = UsbPrinter(this).listDevices()
        val body = buildString {
            val version = packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
            append(getString(R.string.diagnostics_version, version))
            append("\n")
            if (devices.isEmpty()) {
                append(getString(R.string.diagnostics_no_usb))
            } else {
                devices.forEach { device: UsbDevice ->
                    append(getString(
                        R.string.diagnostics_device,
                        device.deviceName,
                        device.vendorId,
                        device.productId
                    ))
                    append("\n")
                }
            }
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.btn_diagnostics)
            .setMessage(body)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun sectionTitle(id: Int): TextView = TextView(this).apply {
        text = getString(id)
        textSize = 16f
        setTextColor(themeColor(MaterialR.attr.colorOnSurfaceVariant))
        setPadding(0, dp(20), 0, dp(4))
    }

    private fun fullButtonParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        dp(52)
    ).apply { topMargin = dp(8) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun themeColor(attr: Int): Int {
        val ta = obtainStyledAttributes(intArrayOf(attr))
        val color = ta.getColor(0, Color.GRAY)
        ta.recycle()
        return color
    }
}
