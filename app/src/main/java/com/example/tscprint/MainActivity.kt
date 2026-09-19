package com.example.tscprint

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.InputFilter
import android.text.TextWatcher
import android.text.Editable
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.R as MaterialR
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        private const val ACTION_USB_PERMISSION = "com.example.tscprint.USB_PERMISSION"
        private const val REQ_PICK_PDF = 1001
        private const val REQ_HISTORY = 1002
        private const val STATE_URI = "selected_uri"
        private const val STATE_SHARED = "selected_shared"
        private const val STATE_MODE = "page_selection_mode"
        private const val STATE_PAGES = "page_selection_pages"
        private const val FILLED = 0
        private const val TONAL = 1
        private const val OUTLINED = 2
    }

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val printer by lazy { UsbPrinter(this) }
    private val settings by lazy { PrintSettings(this) }

    private lateinit var status: TextView
    private lateinit var printerStatus: TextView
    private lateinit var fileName: TextView
    private lateinit var preview: ImageView
    private lateinit var widthField: EditText
    private lateinit var heightField: EditText
    private lateinit var gapField: EditText
    private lateinit var densityField: EditText
    private lateinit var thresholdBar: Slider
    private lateinit var thresholdLabel: TextView
    private lateinit var coverRadio: RadioButton
    private lateinit var containRadio: RadioButton
    private lateinit var ditherCheck: CheckBox
    private lateinit var trimCheck: CheckBox
    private lateinit var pagesSummary: TextView
    private lateinit var pagesButton: MaterialButton
    private lateinit var printButton: MaterialButton
    private lateinit var pageRail: RecyclerView
    private lateinit var copiesField: EditText
    private lateinit var copyOrderGroup: RadioGroup

    private var selectedUri: Uri? = null
    private var prepared: PdfToTspl.Prepared? = null
    private var pendingBytes: ByteArray? = null
    private var pageThumbnails: List<Bitmap> = emptyList()
    private val pageSelection = PageSelection()
    private var sharePending = false
    private var pendingRestoreMode: String? = null
    private var pendingRestorePages: IntArray? = null
    private val history by lazy { PrintHistory(this) }
    private val printQueue = ArrayDeque<ByteArray>()
    private var queueTotal = 0
    private var queueCompleted = 0
    private var queueRunning = false
    private var waitingForQueuePermission = false
    private var currentHistoryTimestamp: Long? = null
    private var lastPrinterError: String? = null
    private var previewRequestId = 0L
    private lateinit var pageAdapter: PagePreviewAdapter

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            @Suppress("DEPRECATION")
            val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (!granted) {
                lastPrinterError = getString(R.string.status_usb_denied)
                status.text = getString(R.string.status_usb_denied)
                updatePrinterStatus()
                return
            }
            if (device != null) {
                settings.vendorId = device.vendorId
                settings.productId = device.productId
                status.text = getString(R.string.status_usb_granted, device.deviceName)
            }
            if (waitingForQueuePermission) {
                waitingForQueuePermission = false
                updatePrinterStatus()
                runNextQueuedPrint()
                return
            }
            val bytes = pendingBytes
            pendingBytes = null
            if (bytes != null) {
                val target = printer.findTargets().firstOrNull()
                if (target != null) {
                    doSend(target, bytes)
                } else {
                    status.text = getString(R.string.status_printer_not_found)
                }
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        setupEdgeToEdge()
        loadSettings()
        registerPermissionReceiver()
        registerUsbStatusReceiver()
        updatePrinterStatus()
        if (savedInstanceState == null) {
            handleShareIntent(intent)
        } else {
            restoreDocumentState(savedInstanceState)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onNewIntent(intent: Intent) {
        @Suppress("DEPRECATION")
        super.onNewIntent(intent)
        setIntent(intent)
        if (queueRunning) stopPrintQueue()
        handleShareIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(permissionReceiver) }
        runCatching { unregisterReceiver(usbStatusReceiver) }
        io.shutdown()
    }

    override fun onPause() {
        if (::widthField.isInitialized) persistSettings()
        super.onPause()
    }

    private val usbStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED ||
                intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED
            ) updatePrinterStatus()
        }
    }

    private fun registerUsbStatusReceiver() {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbStatusReceiver, filter)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        selectedUri?.let { outState.putString(STATE_URI, it.toString()) }
        outState.putBoolean(STATE_SHARED, sharePending)
        outState.putString(STATE_MODE, pageSelection.mode.name)
        outState.putIntArray(STATE_PAGES, pageSelection.selectedPages().toIntArray())
    }

    private fun restoreDocumentState(state: Bundle) {
        val uri = state.getString(STATE_URI)?.let(Uri::parse) ?: return
        selectedUri = uri
        sharePending = false
        pendingRestoreMode = state.getString(STATE_MODE)
        pendingRestorePages = state.getIntArray(STATE_PAGES)
        val shared = state.getBoolean(STATE_SHARED, false)
        fileName.text = getString(
            if (shared) R.string.status_shared else R.string.status_selected,
            uri.lastPathSegment ?: "PDF"
        )
        status.text = getString(R.string.status_preparing_preview)
        ensureDocumentPreview(false)
    }

    private fun setupEdgeToEdge() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                val night = (resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                it.setSystemBarsAppearance(if (night) 0 else mask, mask)
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    private fun registerPermissionReceiver() {
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(permissionReceiver, filter)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun themeColor(attr: Int): Int {
        val ta = obtainStyledAttributes(intArrayOf(attr))
        val color = ta.getColor(0, Color.GRAY)
        ta.recycle()
        return color
    }

    private fun showSettingsDialog() {
        val codes = arrayOf(LocaleHelper.ENGLISH, LocaleHelper.RUSSIAN)
        val labels = arrayOf(
            getString(R.string.language_english),
            getString(R.string.language_russian)
        )
        val current = codes.indexOf(LocaleHelper.getLanguage(this)).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_title)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                val code = codes[which]
                dialog.dismiss()
                if (code != LocaleHelper.getLanguage(this)) {
                    LocaleHelper.setLanguage(this, code)
                    recreate()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.btn_diagnostics) { _, _ -> showDiagnosticsDialog() }
            .show()
    }

    private fun buildUi(): ViewGroup {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColor(MaterialR.attr.colorSurface))
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val header = TextView(this).apply {
            text = getString(R.string.app_name)
            setTextAppearance(MaterialR.style.TextAppearance_Material3_HeadlineSmall)
            setTextColor(themeColor(MaterialR.attr.colorOnSurface))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        printerStatus = TextView(this).apply {
            text = getString(R.string.status_printer_unknown)
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(150)
            setPadding(dp(4), 0, dp(4), 0)
            contentDescription = getString(R.string.status_printer_unknown)
        }
        val historyButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_recent_history)
            imageTintList = ColorStateList.valueOf(themeColor(MaterialR.attr.colorOnSurface))
            contentDescription = getString(R.string.btn_history)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                marginEnd = dp(8)
            }
            val ta = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
            val bg = ta.getResourceId(0, 0)
            ta.recycle()
            setBackgroundResource(bg)
            setOnClickListener {
                startActivityForResult(Intent(this@MainActivity, HistoryActivity::class.java), REQ_HISTORY)
            }
        }
        val gear = ImageButton(this).apply {
            setImageResource(R.drawable.ic_settings)
            imageTintList = ColorStateList.valueOf(themeColor(MaterialR.attr.colorOnSurface))
            contentDescription = getString(R.string.settings_title)
            val ta = obtainStyledAttributes(
                intArrayOf(android.R.attr.selectableItemBackgroundBorderless)
            )
            val bg = ta.getResourceId(0, 0)
            ta.recycle()
            setBackgroundResource(bg)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }
        }
        headerRow.addView(header)
        headerRow.addView(printerStatus)
        headerRow.addView(historyButton)
        headerRow.addView(gear)
        root.addView(headerRow)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(12))
        }
        val scroll = ScrollView(this).apply { addView(content) }
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val printCard = newCard(content, getString(R.string.section_print))
        val pick = button(getString(R.string.btn_pick_pdf), FILLED)
        pick.setOnClickListener { pickPdf() }
        val fileActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = matchWrap()
        }
        fileActions.addView(pick, LinearLayout.LayoutParams(0, dp(60), 1f))

        printButton = button(getString(R.string.btn_print_short), TONAL)
        printButton.setOnClickListener {
            if (queueRunning) stopPrintQueue() else startSendPrepared()
        }
        printButton.visibility = View.GONE
        fileActions.addView(printButton, LinearLayout.LayoutParams(0, dp(60), 1f).apply {
            marginStart = dp(8)
        })
        printCard.addView(fileActions)

        fileName = TextView(this).apply {
            text = getString(R.string.status_no_file)
            setTextAppearance(MaterialR.style.TextAppearance_Material3_BodySmall)
            setTextColor(themeColor(MaterialR.attr.colorOnSurfaceVariant))
            setPadding(0, dp(8), 0, 0)
        }
        printCard.addView(fileName)

        val previewCard = MaterialCardView(this).apply {
            radius = dp(12).toFloat()
            cardElevation = 0f
            setContentPadding(0, 0, 0, 0)
        }
        preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(themeColor(MaterialR.attr.colorSurfaceVariant))
        }
        previewCard.addView(preview, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(200)
        ))
        printCard.addView(previewCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(10)
            bottomMargin = dp(10)
        })

        pagesSummary = TextView(this).apply {
            text = getString(R.string.pages_summary_empty)
            setTextAppearance(MaterialR.style.TextAppearance_Material3_BodyMedium)
            setTextColor(themeColor(MaterialR.attr.colorOnSurfaceVariant))
            setPadding(0, dp(4), 0, 0)
        }
        printCard.addView(pagesSummary)

        pageAdapter = PagePreviewAdapter { index -> togglePage(index) }
        pageRail = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity, RecyclerView.HORIZONTAL, false)
            adapter = pageAdapter
            clipToPadding = false
            setPadding(dp(2), dp(10), dp(2), dp(4))
            visibility = View.GONE
        }
        printCard.addView(pageRail, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(224)
        ))

        pagesButton = button(getString(R.string.btn_page_selection), OUTLINED)
        pagesButton.setOnClickListener { showPageSelectionDialog() }
        pagesButton.visibility = View.GONE
        printCard.addView(pagesButton, matchWrap())

        val copiesRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = matchWrap()
        }
        val copiesLayout = TextInputLayout(this).apply {
            hint = getString(R.string.label_copies)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            layoutParams = LinearLayout.LayoutParams(dp(92), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        copiesField = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("1")
            setSelectAllOnFocus(true)
            filters = arrayOf(InputFilter.LengthFilter(3))
            gravity = android.view.Gravity.CENTER
        }
        copiesLayout.addView(copiesField)
        copiesRow.addView(copiesLayout)
        val minus = button("−", OUTLINED).apply {
            contentDescription = getString(R.string.copies_decrease)
            minWidth = 0
            setPadding(dp(12), 0, dp(12), 0)
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginStart = dp(8) }
            setOnClickListener { changeCopies(-1) }
        }
        copiesRow.addView(minus)
        val plus = button("+", OUTLINED).apply {
            contentDescription = getString(R.string.copies_increase)
            minWidth = 0
            setPadding(dp(12), 0, dp(12), 0)
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginStart = dp(6) }
            setOnClickListener { changeCopies(1) }
        }
        copiesRow.addView(plus)
        printCard.addView(copiesRow)

        copyOrderGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            layoutParams = matchWrap()
        }
        copyOrderGroup.addView(MaterialRadioButton(this).apply {
            id = View.generateViewId()
            text = getString(R.string.copy_order_collated)
        })
        copyOrderGroup.addView(MaterialRadioButton(this).apply {
            id = View.generateViewId()
            text = getString(R.string.copy_order_grouped)
        })
        copyOrderGroup.visibility = View.GONE
        printCard.addView(copyOrderGroup)
        copiesField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateCopyOrderVisibility()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        val labelCard = newCard(content, getString(R.string.section_label))
        labelCard.addView(hint(getString(R.string.hint_mm)))
        widthField = textField(labelCard, getString(R.string.label_width), "58")
        heightField = textField(labelCard, getString(R.string.label_height), "30")
        gapField = textField(labelCard, getString(R.string.label_gap), "2")
        densityField = textField(labelCard, getString(R.string.label_density), "8")

        labelCard.addView(hint(getString(R.string.hint_scale)))
        val group = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        coverRadio = MaterialRadioButton(this).apply {
            id = View.generateViewId()
            text = getString(R.string.radio_cover)
        }
        containRadio = MaterialRadioButton(this).apply {
            id = View.generateViewId()
            text = getString(R.string.radio_contain)
        }
        group.addView(coverRadio)
        group.addView(containRadio)
        labelCard.addView(group)

        thresholdLabel = TextView(this).apply {
            text = getString(R.string.threshold_format, 128)
            setTextAppearance(MaterialR.style.TextAppearance_Material3_BodyMedium)
            setTextColor(themeColor(MaterialR.attr.colorOnSurface))
            setPadding(0, dp(10), 0, 0)
        }
        labelCard.addView(thresholdLabel)
        labelCard.addView(hint(getString(R.string.hint_threshold)))
        thresholdBar = Slider(this).apply {
            valueFrom = 0f
            valueTo = 255f
            stepSize = 1f
            value = 128f
            addOnChangeListener { _, value, _ ->
                thresholdLabel.text = getString(R.string.threshold_format, value.toInt())
            }
        }
        labelCard.addView(thresholdBar)

        ditherCheck = MaterialCheckBox(this).apply { text = getString(R.string.check_dither) }
        labelCard.addView(ditherCheck)

        trimCheck = MaterialCheckBox(this).apply { text = getString(R.string.check_trim) }
        labelCard.addView(trimCheck)

        val save = button(getString(R.string.btn_save), FILLED)
        save.setOnClickListener { saveSettings() }
        content.addView(save, matchWrap())

        val accessCard = newCard(content, getString(R.string.section_access))
        val authorize = button(getString(R.string.btn_allow_usb), TONAL)
        authorize.setOnClickListener { authorizeUsb() }
        accessCard.addView(authorize, matchWrap())

        val calibrate = button(getString(R.string.btn_calibrate), OUTLINED)
        calibrate.setOnClickListener { calibratePrinter() }
        accessCard.addView(calibrate, matchWrap())

        val testBtn = button(getString(R.string.btn_test_print), OUTLINED)
        testBtn.setOnClickListener { testPrint() }
        accessCard.addView(testBtn, matchWrap())

        status = TextView(this).apply {
            text = getString(R.string.status_daily_hint)
            setTextAppearance(MaterialR.style.TextAppearance_Material3_BodySmall)
            setTextColor(themeColor(MaterialR.attr.colorOnSurfaceVariant))
            setPadding(0, dp(12), 0, dp(4))
        }
        content.addView(status)

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
            headerRow.setPadding(dp(20), top + dp(14), dp(8), dp(14))
            content.setPadding(dp(12), dp(4), dp(12), dp(12) + bottom)
            insets
        }

        return root
    }

    private fun newCard(parent: LinearLayout, title: String): LinearLayout {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
            radius = dp(16).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(themeColor(MaterialR.attr.colorSurfaceContainerHigh))
            setContentPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(inner)
        parent.addView(card)
        inner.addView(TextView(this).apply {
            text = title
            setTextAppearance(MaterialR.style.TextAppearance_Material3_TitleMedium)
            setTextColor(themeColor(MaterialR.attr.colorOnSurfaceVariant))
        })
        return inner
    }

    private fun button(text: String, kind: Int): MaterialButton {
        val button = when (kind) {
            OUTLINED -> MaterialButton(this, null, MaterialR.attr.materialButtonOutlinedStyle)
            else -> MaterialButton(this)
        }
        button.text = text
        button.layoutParams = matchWrap()
        if (kind == TONAL) {
            button.backgroundTintList =
                ColorStateList.valueOf(themeColor(MaterialR.attr.colorSecondaryContainer))
            button.setTextColor(themeColor(MaterialR.attr.colorOnSecondaryContainer))
        }
        return button
    }

    private fun textField(parent: LinearLayout, label: String, value: String): EditText {
        val layout = TextInputLayout(this).apply {
            hint = label
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        val field = TextInputEditText(layout.context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(value)
        }
        layout.addView(field)
        parent.addView(layout)
        return field
    }

    private fun hint(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextAppearance(MaterialR.style.TextAppearance_Material3_BodySmall)
        setTextColor(themeColor(MaterialR.attr.colorOnSurfaceVariant))
        setPadding(0, dp(4), 0, 0)
    }

    private fun matchWrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }

    private fun loadSettings() {
        widthField.setText(settings.widthMm.toString())
        heightField.setText(settings.heightMm.toString())
        gapField.setText(settings.gapMm.toString())
        densityField.setText(settings.density.toString())
        copiesField.setText(settings.copies.toString())
        copyOrderGroup.check(copyOrderGroup.getChildAt(settings.copyOrder).id)
        thresholdBar.value = settings.threshold.toFloat().coerceIn(0f, 255f)
        thresholdLabel.text = getString(R.string.threshold_format, settings.threshold)
        ditherCheck.isChecked = settings.dither
        trimCheck.isChecked = settings.trim
        if (settings.cover) coverRadio.isChecked = true else containRadio.isChecked = true
    }

    private fun persistSettings() {
        settings.widthMm = widthField.text.toString().toIntOrNull()?.coerceIn(10, 200) ?: 58
        settings.heightMm = heightField.text.toString().toIntOrNull()?.coerceIn(5, 200) ?: 30
        settings.gapMm = gapField.text.toString().toIntOrNull()?.coerceIn(0, 20) ?: 2
        settings.density = densityField.text.toString().toIntOrNull()?.coerceIn(0, 15) ?: 8
        settings.copies = copiesField.text.toString().toIntOrNull()?.coerceIn(1, 999) ?: 1
        settings.copyOrder = copyOrderGroup.indexOfChild(copyOrderGroup.findViewById(copyOrderGroup.checkedRadioButtonId))
        settings.threshold = thresholdBar.value.toInt()
        settings.dither = ditherCheck.isChecked
        settings.trim = trimCheck.isChecked
        settings.cover = coverRadio.isChecked
        prepared = null
    }

    private fun saveSettings() {
        persistSettings()
        loadSettings()
        status.text = getString(R.string.status_saved, settings.widthMm, settings.heightMm)
    }

    private fun pickPdf() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQ_PICK_PDF)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_PDF && resultCode == RESULT_OK) {
            if (queueRunning) stopPrintQueue()
            selectedUri = data?.data
            selectedUri?.let { uri ->
                val takeFlags = (data?.flags ?: 0) and Intent.FLAG_GRANT_READ_URI_PERMISSION
                if (takeFlags != 0) {
                    runCatching { contentResolver.takePersistableUriPermission(uri, takeFlags) }
                }
            }
            prepared = null
            sharePending = false
            pendingRestoreMode = null
            pendingRestorePages = null
            resetCopiesForNewDocument()
            clearPagePreview()
            fileName.text = getString(
                R.string.status_selected, selectedUri?.lastPathSegment ?: "PDF"
            )
            status.text = getString(R.string.status_preparing_preview)
            ensureDocumentPreview(false)
        } else if (requestCode == REQ_HISTORY && resultCode == RESULT_OK) {
            data?.data?.let { openHistoryUri(it) }
        }
    }

    private fun sharedUri(intent: Intent?): Uri? {
        if (intent == null) return null
        val action = intent.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_VIEW) return null
        @Suppress("DEPRECATION")
        val stream: Uri? = intent.getParcelableExtra(Intent.EXTRA_STREAM)
        if (stream != null) return stream
        val clip = intent.clipData
        if (clip != null && clip.itemCount > 0) return clip.getItemAt(0).uri
        return intent.data
    }

    private fun handleShareIntent(intent: Intent?) {
        val uri = sharedUri(intent) ?: return
        val source = intent?.getStringExtra(ShareSource.EXTRA_SOURCE_PACKAGE)
            ?: ShareSource.packageName(this, intent ?: Intent())
        source?.let { QuickShareSettings(this).recordCandidate(it) }
        if (queueRunning) stopPrintQueue()
        selectedUri = uri
        prepared = null
        sharePending = true
        pendingRestoreMode = null
        pendingRestorePages = null
        resetCopiesForNewDocument()
        clearPagePreview()
        fileName.text = getString(R.string.status_shared, uri.lastPathSegment ?: "PDF")
        status.text = getString(R.string.status_preparing_preview)
        ensureDocumentPreview(true)
    }

    private fun clearPagePreview() {
        previewRequestId++
        pageAdapter.submit(emptyList())
        // RecyclerView may still draw a detached holder during the next frame.
        // Do not recycle these bitmaps manually: the old adapter list is released
        // and the GC will reclaim the bitmaps after all views stop referencing them.
        preview.setImageDrawable(null)
        pageThumbnails = emptyList()
        pageSelection.reset(0)
        pageRail.visibility = View.GONE
        pagesButton.visibility = View.GONE
        printButton.visibility = View.GONE
        pagesSummary.text = getString(R.string.pages_summary_empty)
    }

    private fun ensureDocumentPreview(autoPrintSinglePage: Boolean) {
        val uri = selectedUri ?: return
        val requestId = ++previewRequestId
        status.text = getString(R.string.status_preparing_preview)
        io.execute {
            try {
                val thumbnails = PdfToTspl.renderThumbnails(this, uri)
                main.post {
                    if (requestId != previewRequestId || uri != selectedUri) {
                        return@post
                    }
                    pageThumbnails = thumbnails
                    val restored = pendingRestorePages?.let { pages ->
                        pageSelection.restore(thumbnails.size, pendingRestoreMode, pages)
                    } ?: false
                    if (!restored) pageSelection.reset(thumbnails.size)
                    pendingRestoreMode = null
                    pendingRestorePages = null
                    pageAdapter.submit(thumbnails.mapIndexed { index, bitmap ->
                        PagePreviewAdapter.Item(index, bitmap, pageSelection.isSelected(index))
                    })
                    preview.setImageBitmap(thumbnails.firstOrNull())
                    pageRail.visibility = if (thumbnails.isEmpty()) View.GONE else View.VISIBLE
                    pagesButton.visibility = if (thumbnails.isEmpty()) View.GONE else View.VISIBLE
                    printButton.visibility = if (thumbnails.isEmpty()) View.GONE else View.VISIBLE
                    updatePageSelectionUi()
                    status.text = getString(R.string.status_ready_preview, thumbnails.size)

                    if (autoPrintSinglePage && thumbnails.size == 1) {
                        ensurePrepared { result ->
                            persistSettings()
                            enqueuePrint(result.jobs, copiesCount())
                        }
                    }
                }
            } catch (e: Exception) {
                main.post {
                    if (requestId == previewRequestId && uri == selectedUri) {
                        status.text = getString(R.string.status_error, e.message ?: "")
                    }
                }
            }
        }
    }

    private fun togglePage(index: Int) {
        pageSelection.toggle(index)
        prepared = null
        pageThumbnails.getOrNull(index)?.let { preview.setImageBitmap(it) }
        refreshPageAdapter()
        updatePageSelectionUi()
    }

    private fun refreshPageAdapter() {
        pageAdapter.submit(pageThumbnails.mapIndexed { index, bitmap ->
            PagePreviewAdapter.Item(index, bitmap, pageSelection.isSelected(index))
        })
    }

    private fun updatePageSelectionUi() {
        val selected = pageSelection.count()
        val total = pageSelection.total()
        pagesSummary.text = if (total == 0) {
            getString(R.string.pages_summary_empty)
        } else {
            getString(R.string.pages_summary, selected, total)
        }
        pagesButton.text = when (pageSelection.mode) {
            PageSelection.Mode.ALL -> getString(R.string.pages_all)
            PageSelection.Mode.RANGE -> getString(R.string.pages_range, pageSelection.expression())
            PageSelection.Mode.MANUAL -> getString(R.string.pages_manual)
        }
        printButton.text = if (queueRunning) {
            getString(R.string.btn_stop_print)
        } else {
            getString(R.string.btn_print_short)
        }
        printButton.isEnabled = queueRunning || selected > 0
    }

    private fun updateCopyOrderVisibility() {
        if (!::copyOrderGroup.isInitialized) return
        copyOrderGroup.visibility = if (copiesCount() > 1) View.VISIBLE else View.GONE
    }

    private fun updatePrinterStatus() {
        if (!::printerStatus.isInitialized) return
        val target = printer.findTargets().firstOrNull()
        val connected = target != null
        val allowed = connected && printer.hasPermission(target!!.device)
        fun show(label: String, color: Int) {
            printerStatus.text = "● $label"
            printerStatus.setTextColor(color)
            printerStatus.contentDescription = label
        }
        when {
            queueRunning -> {
                show(getString(R.string.status_printing), Color.rgb(70, 150, 235))
            }
            !connected -> {
                show(getString(R.string.status_printer_disconnected), Color.GRAY)
            }
            !allowed -> {
                show(getString(R.string.status_printer_permission), Color.rgb(235, 175, 55))
            }
            lastPrinterError != null -> {
                show(getString(R.string.status_printer_error), Color.rgb(220, 70, 70))
            }
            else -> {
                show(getString(R.string.status_printer_ready), Color.rgb(55, 190, 95))
            }
        }
    }

    private fun changeCopies(delta: Int) {
        val value = copiesCount()
        copiesField.setText((value + delta).coerceIn(1, 999).toString())
        persistSettings()
    }

    private fun historyFileAvailable(entry: PrintHistory.Entry): Boolean = runCatching {
        contentResolver.openAssetFileDescriptor(Uri.parse(entry.uri), "r")?.use { true } ?: false
    }.getOrDefault(false)

    private fun openHistoryUri(uri: Uri) {
        val available = runCatching {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
        }.getOrDefault(false)
        if (!available) {
            status.text = getString(R.string.history_unavailable)
            return
        }
        selectedUri = uri
        sharePending = false
        prepared = null
        resetCopiesForNewDocument()
        clearPagePreview()
        fileName.text = getString(R.string.status_selected, uri.lastPathSegment ?: "PDF")
        status.text = getString(R.string.status_preparing_preview)
        ensureDocumentPreview(false)
    }

    private fun resetCopiesForNewDocument() {
        copiesField.setText("1")
        settings.copies = 1
    }

    private fun showDiagnosticsDialog() {
        val devices = printer.listDevices()
        val body = buildString {
            val version = packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
            append(getString(R.string.diagnostics_version, version))
            append("\n")
            if (devices.isEmpty()) {
                append(getString(R.string.diagnostics_no_usb))
            } else {
                devices.forEach { device ->
                    append(getString(R.string.diagnostics_device, device.deviceName, device.vendorId, device.productId))
                    append("\n")
                }
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.btn_diagnostics)
            .setMessage(body)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showPageSelectionDialog() {
        val total = pageSelection.total()
        if (total == 0) return
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), 0, dp(24), 0)
        }
        val all = MaterialRadioButton(this).apply { text = getString(R.string.pages_all) }
        val range = MaterialRadioButton(this).apply { text = getString(R.string.pages_range_option) }
        val manual = MaterialRadioButton(this).apply { text = getString(R.string.pages_manual) }
        val rangeLayout = TextInputLayout(this).apply {
            hint = getString(R.string.pages_range_hint)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        val rangeField = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(if (pageSelection.mode == PageSelection.Mode.RANGE) {
                pageSelection.expression()
            } else "1-$total")
        }
        rangeLayout.addView(rangeField)
        column.addView(all)
        column.addView(range)
        column.addView(manual)
        column.addView(rangeLayout)

        when (pageSelection.mode) {
            PageSelection.Mode.ALL -> all.isChecked = true
            PageSelection.Mode.RANGE -> range.isChecked = true
            PageSelection.Mode.MANUAL -> manual.isChecked = true
        }
        fun select(button: MaterialRadioButton) {
            all.isChecked = button === all
            range.isChecked = button === range
            manual.isChecked = button === manual
            rangeLayout.visibility = if (button === range) View.VISIBLE else View.GONE
        }
        all.setOnClickListener { select(all) }
        range.setOnClickListener { select(range) }
        manual.setOnClickListener { select(manual) }
        select(when {
            all.isChecked -> all
            range.isChecked -> range
            else -> manual
        })

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pages_dialog_title)
            .setView(column)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.apply, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                when {
                    all.isChecked -> pageSelection.selectAll()
                    range.isChecked -> {
                        val result = pageSelection.applyRange(rangeField.text?.toString().orEmpty())
                        if (result.isFailure) {
                            rangeLayout.error = getString(R.string.pages_range_error, total)
                            return@setOnClickListener
                        }
                    }
                    manual.isChecked -> Unit
                }
                prepared = null
                refreshPageAdapter()
                updatePageSelectionUi()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun labelSize(): Pair<Int, Int> {
        val w = widthField.text.toString().toIntOrNull()?.coerceIn(10, 200) ?: 58
        val h = heightField.text.toString().toIntOrNull()?.coerceIn(5, 200) ?: 30
        return w to h
    }

    private fun ensurePrepared(onReady: (PdfToTspl.Prepared) -> Unit) {
        val ready = prepared
        if (ready != null) {
            onReady(ready)
            return
        }
        val uri = selectedUri
        if (uri == null) {
            toast(getString(R.string.toast_pick_first))
            return
        }
        val selected = pageSelection.selectedPages()
        if (selected.isEmpty()) {
            status.text = getString(R.string.pages_none_selected)
            return
        }
        val (w, h) = labelSize()
        val gap = gapField.text.toString().toIntOrNull()?.coerceIn(0, 20) ?: 2
        val density = densityField.text.toString().toIntOrNull()?.coerceIn(0, 15) ?: 8
        val cover = coverRadio.isChecked
        val dither = ditherCheck.isChecked
        val trim = trimCheck.isChecked
        val threshold = thresholdBar.value.toInt()
        status.text = getString(R.string.status_processing)
        io.execute {
            try {
                val result = PdfToTspl.prepare(
                    this, uri, w, h, cover, dither, threshold, gap, density, trim, selected
                )
                main.post {
                    prepared = result
                    preview.setImageBitmap(result.preview)
                    status.text = getString(
                        R.string.status_ready, result.pageCount, result.widthDots, result.heightDots
                    )
                    onReady(result)
                }
            } catch (e: Exception) {
                main.post { status.text = getString(R.string.status_error, e.message ?: "") }
            }
        }
    }

    private fun startSendPrepared() {
        persistSettings()
        prepared = null
        ensurePrepared { result ->
            enqueuePrint(result.jobs, copiesCount())
        }
    }

    private fun copiesCount(): Int = copiesField.text.toString().toIntOrNull()?.coerceIn(1, 999) ?: 1

    private fun enqueuePrint(jobs: List<ByteArray>, copies: Int) {
        if (jobs.isEmpty()) return
        printQueue.clear()
        if (settings.copyOrder == 0) {
            repeat(copies) { printQueue.addAll(jobs) }
        } else {
            jobs.forEach { job -> repeat(copies) { printQueue.add(job) } }
        }
        queueTotal = printQueue.size
        queueCompleted = 0
        queueRunning = true
        lastPrinterError = null
        currentHistoryTimestamp = selectedUri?.let { uri ->
            history.add(
                uri,
                uri.lastPathSegment ?: fileName.text.toString(),
                jobs.size,
                copies,
                getString(R.string.history_queued)
            ).timestamp
        }
        updatePrinterStatus()
        updatePageSelectionUi()
        runNextQueuedPrint()
    }

    private fun runNextQueuedPrint() {
        if (!queueRunning) return
        if (printQueue.isEmpty()) {
            queueRunning = false
            updatePrinterStatus()
            updatePageSelectionUi()
            currentHistoryTimestamp?.let { history.updateStatus(it, getString(R.string.history_printed)) }
            currentHistoryTimestamp = null
            status.text = getString(R.string.status_queue_done, queueCompleted)
            return
        }
        val target = printer.findTargets().firstOrNull()
        if (target == null) {
            queueRunning = false
            updatePrinterStatus()
            updatePageSelectionUi()
            status.text = getString(R.string.status_usb_not_found)
            lastPrinterError = getString(R.string.status_printer_disconnected)
            updatePrinterStatus()
            return
        }
        if (!printer.hasPermission(target.device)) {
            waitingForQueuePermission = true
            status.text = getString(R.string.status_requesting_usb)
            printer.requestPermission(target.device, ACTION_USB_PERMISSION)
            return
        }
        val bytes = printQueue.first()
        status.text = getString(R.string.status_queue_progress, queueCompleted + 1, queueTotal)
        io.execute {
            try {
                printer.send(target, bytes)
                main.post {
                    if (!queueRunning) return@post
                    printQueue.removeFirst()
                    queueCompleted++
                    runNextQueuedPrint()
                }
            } catch (e: Exception) {
                main.post {
                    queueRunning = false
                    lastPrinterError = e.message ?: getString(R.string.status_printer_error)
                    updatePrinterStatus()
                    updatePageSelectionUi()
                    currentHistoryTimestamp?.let { history.updateStatus(it, getString(R.string.history_error)) }
                    status.text = getString(R.string.status_print_error, e.message ?: "")
                }
            }
        }
    }

    private fun stopPrintQueue() {
        queueRunning = false
        waitingForQueuePermission = false
        printQueue.clear()
        currentHistoryTimestamp?.let { history.updateStatus(it, getString(R.string.history_stopped)) }
        currentHistoryTimestamp = null
        updatePrinterStatus()
        updatePageSelectionUi()
        status.text = getString(R.string.status_queue_stopped)
    }

    private fun testPrint() {
        val (w, h) = labelSize()
        val density = densityField.text.toString().toIntOrNull()?.coerceIn(0, 15) ?: 8
        val tspl = (
            "SIZE ${w} mm,${h} mm\r\n" +
                "GAP 2 mm,0 mm\r\n" +
                "DENSITY $density\r\n" +
                "DIRECTION 1\r\n" +
                "CLS\r\n" +
                "TEXT 20,20,\"3\",0,1,1,\"TSC TE200 TEST\"\r\n" +
                "BARCODE 20,80,\"128\",80,1,0,2,4,\"1234567890\"\r\n" +
                "PRINT 1,1\r\n"
            ).toByteArray(Charsets.US_ASCII)
        pendingBytes = tspl
        startSend(tspl)
    }

    private fun calibratePrinter() {
        val command = "GAPDETECT\r\n".toByteArray(Charsets.US_ASCII)
        pendingBytes = command
        status.text = getString(R.string.status_calibrating)
        startSend(command)
    }

    private fun authorizeUsb() {
        val target = printer.findTargets().firstOrNull()
        if (target == null) {
            status.text = getString(R.string.status_usb_not_found)
            return
        }
        if (printer.hasPermission(target.device)) {
            settings.vendorId = target.device.vendorId
            settings.productId = target.device.productId
            status.text = getString(R.string.status_usb_already, target.device.deviceName)
            return
        }
        status.text = getString(R.string.status_requesting_usb)
        printer.requestPermission(target.device, ACTION_USB_PERMISSION)
    }

    private fun startSend(bytes: ByteArray) {
        lastPrinterError = null
        updatePrinterStatus()
        val target = printer.findTargets().firstOrNull()
        if (target == null) {
            lastPrinterError = getString(R.string.status_printer_disconnected)
            status.text = getString(R.string.status_usb_not_found)
            updatePrinterStatus()
            return
        }
        if (!printer.hasPermission(target.device)) {
            status.text = getString(R.string.status_requesting_usb)
            printer.requestPermission(target.device, ACTION_USB_PERMISSION)
            return
        }
        doSend(target, bytes)
    }

    private fun doSend(target: UsbPrinter.Target, bytes: ByteArray) {
        status.text = getString(R.string.status_sending, bytes.size, target.device.deviceName)
        io.execute {
            try {
                val sent = printer.send(target, bytes)
                main.post { status.text = getString(R.string.status_sent, sent) }
            } catch (e: Exception) {
                main.post {
                    lastPrinterError = e.message ?: getString(R.string.status_printer_error)
                    updatePrinterStatus()
                    status.text = getString(R.string.status_print_error, e.message ?: "")
                }
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
