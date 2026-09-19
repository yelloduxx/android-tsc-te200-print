package com.example.tscprint

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.R as MaterialR

class HistoryActivity : Activity() {

    private val history by lazy { PrintHistory(this) }
    private val clearHandler = Handler(Looper.getMainLooper())
    private var clearCountdown = 0
    private var clearButton: MaterialButton? = null
    private var defaultClearTint: ColorStateList? = null
    private var defaultClearTextColor: ColorStateList? = null

    private val clearReset = object : Runnable {
        override fun run() {
            if (clearCountdown <= 1) {
                resetClearConfirmation()
            } else {
                clearCountdown -= 1
                clearButton?.text = getString(R.string.history_clear_confirm, clearCountdown)
                clearHandler.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle(R.string.btn_history)
        setContentView(buildUi())
    }

    override fun onDestroy() {
        clearHandler.removeCallbacks(clearReset)
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
            setOnClickListener { handleClearClick(this) }
        }
        clearButton = clear
        defaultClearTint = clear.backgroundTintList
        defaultClearTextColor = clear.textColors
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

    private fun handleClearClick(button: MaterialButton) {
        if (clearCountdown > 0) {
            clearHandler.removeCallbacks(clearReset)
            history.clear()
            recreate()
            return
        }
        clearCountdown = 5
        button.backgroundTintList = ColorStateList.valueOf(themeColor(MaterialR.attr.colorError))
        button.setTextColor(themeColor(MaterialR.attr.colorOnError))
        button.text = getString(R.string.history_clear_confirm, clearCountdown)
        clearHandler.postDelayed(clearReset, 1000)
    }

    private fun resetClearConfirmation() {
        clearCountdown = 0
        clearButton?.backgroundTintList = defaultClearTint
        clearButton?.setTextColor(defaultClearTextColor)
        clearButton?.text = getString(R.string.history_clear)
    }

    private fun entryView(entry: PrintHistory.Entry): FrameLayout {
        val available = runCatching {
            contentResolver.openAssetFileDescriptor(Uri.parse(entry.uri), "r")?.use { true } ?: false
        }.getOrDefault(false)
        val wrapper = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        val delete = MaterialButton(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(84), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END)
            icon = getDrawable(android.R.drawable.ic_menu_delete)
            iconTint = ColorStateList.valueOf(themeColor(MaterialR.attr.colorOnError))
            text = ""
            contentDescription = getString(R.string.history_delete)
            backgroundTintList = ColorStateList.valueOf(themeColor(MaterialR.attr.colorError))
            setOnClickListener {
                history.remove(entry.timestamp)
                recreate()
            }
        }
        wrapper.addView(delete)
        val card = MaterialCardView(this).apply {
            radius = dp(12).toFloat()
            cardElevation = 0f
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
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
        var downX = 0f
        var swiping = false
        val revealWidth = dp(84).toFloat()
        card.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    swiping = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val delta = event.rawX - downX
                    if (!swiping && delta < -dp(8)) swiping = true
                    if (swiping) {
                        view.translationX = delta.coerceIn(-revealWidth, 0f)
                        true
                    } else {
                        true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (swiping) {
                        val target = if (view.translationX <= -revealWidth / 2) -revealWidth else 0f
                        view.animate().translationX(target).setDuration(160).start()
                    } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                        view.performClick()
                    }
                    true
                }
                else -> true
            }
        }
        val text = TextView(this).apply {
            val state = if (available) entry.status else getString(R.string.history_unavailable)
            val date = DateFormat.getMediumDateFormat(this@HistoryActivity).format(entry.timestamp)
            val time = DateFormat.getTimeFormat(this@HistoryActivity).format(entry.timestamp)
            this.text = "${entry.name}\n$date, $time · ${entry.pages} стр. × ${entry.copies} · $state"
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setTextColor(if (available) themeColor(MaterialR.attr.colorOnSurface) else Color.GRAY)
        }
        card.addView(text)
        wrapper.addView(card)
        return wrapper
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun themeColor(attr: Int): Int {
        val ta = obtainStyledAttributes(intArrayOf(attr))
        val color = ta.getColor(0, Color.GRAY)
        ta.recycle()
        return color
    }
}
