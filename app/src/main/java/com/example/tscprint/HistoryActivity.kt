package com.example.tscprint

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.format.DateFormat
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.R as MaterialR

class HistoryActivity : Activity() {

    private val history by lazy { PrintHistory(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle(R.string.btn_history)
        setContentView(buildUi())
    }

    private fun buildUi(): ViewGroup {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(themeColor(MaterialR.attr.colorSurface))
        }
        val title = TextView(this).apply {
            text = getString(R.string.btn_history)
            textSize = 24f
            setTextColor(themeColor(MaterialR.attr.colorOnSurface))
        }
        root.addView(title)

        val scrollContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val entries = history.list().asReversed()
        if (entries.isEmpty()) {
            scrollContent.addView(TextView(this).apply {
                text = getString(R.string.history_empty)
                setTextColor(themeColor(MaterialR.attr.colorOnSurfaceVariant))
                setPadding(0, dp(24), 0, dp(24))
            })
        } else {
            entries.forEach { entry -> scrollContent.addView(entryView(entry)) }
        }
        val scroll = ScrollView(this).apply { addView(scrollContent) }
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val clear = MaterialButton(this).apply {
            text = getString(R.string.history_clear)
            setOnClickListener {
                history.clear()
                recreate()
            }
        }
        actions.addView(clear, LinearLayout.LayoutParams(0, dp(52), 1f))
        val close = MaterialButton(this).apply {
            text = getString(R.string.close)
            setOnClickListener { finish() }
        }
        actions.addView(close, LinearLayout.LayoutParams(0, dp(52), 1f).apply {
            marginStart = dp(8)
        })
        root.addView(actions)
        return root
    }

    private fun entryView(entry: PrintHistory.Entry): MaterialCardView {
        val available = runCatching {
            contentResolver.openAssetFileDescriptor(Uri.parse(entry.uri), "r")?.use { true } ?: false
        }.getOrDefault(false)
        val card = MaterialCardView(this).apply {
            radius = dp(12).toFloat()
            cardElevation = 0f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
            isClickable = available
            setOnClickListener {
                if (available) {
                    setResult(RESULT_OK, Intent().setData(Uri.parse(entry.uri)))
                    finish()
                } else {
                    Toast.makeText(this@HistoryActivity, R.string.history_unavailable, Toast.LENGTH_SHORT).show()
                }
            }
        }
        val text = TextView(this).apply {
            val state = if (available) entry.status else getString(R.string.history_unavailable)
            val date = DateFormat.getMediumDateFormat(this@HistoryActivity).format(entry.timestamp)
            this.text = "${entry.name}\n$date · ${entry.pages} стр. × ${entry.copies} · $state"
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setTextColor(if (available) themeColor(MaterialR.attr.colorOnSurface) else Color.GRAY)
        }
        card.addView(text)
        return card
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun themeColor(attr: Int): Int {
        val ta = obtainStyledAttributes(intArrayOf(attr))
        val color = ta.getColor(0, Color.GRAY)
        ta.recycle()
        return color
    }
}
