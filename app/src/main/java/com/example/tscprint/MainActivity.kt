package com.example.tscprint

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
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
        private const val FILLED = 0
        private const val TONAL = 1
        private const val OUTLINED = 2
    }

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val printer by lazy { UsbPrinter(this) }
    private val settings by lazy { PrintSettings(this) }

    private lateinit var status: TextView
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

    private var selectedUri: Uri? = null
    private var prepared: PdfToTspl.Prepared? = null
    private var pendingBytes: ByteArray? = null

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            @Suppress("DEPRECATION")
            val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (!granted) {
                status.text = getString(R.string.status_usb_denied)
                return
            }
            if (device != null) {
                settings.vendorId = device.vendorId
                settings.productId = device.productId
                status.text = getString(R.string.status_usb_granted, device.deviceName)
            }
            val bytes = pendingBytes
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
        if (savedInstanceState == null) {
            handleShareIntent(intent)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onNewIntent(intent: Intent) {
        @Suppress("DEPRECATION")
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(permissionReceiver) }
        io.shutdown()
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
            setOnClickListener { showSettingsDialog() }
        }
        headerRow.addView(header)
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
        printCard.addView(pick, matchWrap())

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

        val printBtn = button(getString(R.string.btn_print), TONAL)
        printBtn.setOnClickListener { startSendPrepared() }
        printCard.addView(printBtn, matchWrap())

        val testBtn = button(getString(R.string.btn_test_print), OUTLINED)
        testBtn.setOnClickListener { testPrint() }
        printCard.addView(testBtn, matchWrap())

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
        thresholdBar.value = settings.threshold.toFloat().coerceIn(0f, 255f)
        thresholdLabel.text = getString(R.string.threshold_format, settings.threshold)
        ditherCheck.isChecked = settings.dither
        trimCheck.isChecked = settings.trim
        if (settings.cover) coverRadio.isChecked = true else containRadio.isChecked = true
    }

    private fun saveSettings() {
        settings.widthMm = widthField.text.toString().toIntOrNull()?.coerceIn(10, 200) ?: 58
        settings.heightMm = heightField.text.toString().toIntOrNull()?.coerceIn(5, 200) ?: 30
        settings.gapMm = gapField.text.toString().toIntOrNull()?.coerceIn(0, 20) ?: 2
        settings.density = densityField.text.toString().toIntOrNull()?.coerceIn(0, 15) ?: 8
        settings.threshold = thresholdBar.value.toInt()
        settings.dither = ditherCheck.isChecked
        settings.trim = trimCheck.isChecked
        settings.cover = coverRadio.isChecked
        loadSettings()
        status.text = getString(R.string.status_saved, settings.widthMm, settings.heightMm)
    }

    private fun pickPdf() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
        }
        startActivityForResult(intent, REQ_PICK_PDF)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_PDF && resultCode == RESULT_OK) {
            selectedUri = data?.data
            prepared = null
            preview.setImageDrawable(null)
            fileName.text = getString(
                R.string.status_selected, selectedUri?.lastPathSegment ?: "PDF"
            )
            status.text = getString(R.string.status_preparing_preview)
            ensurePrepared { }
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
        selectedUri = uri
        prepared = null
        preview.setImageDrawable(null)
        fileName.text = getString(R.string.status_shared, uri.lastPathSegment ?: "PDF")
        status.text = getString(R.string.status_preparing_print)
        ensurePrepared { result ->
            pendingBytes = result.tspl
            startSend(result.tspl)
        }
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
                    this, uri, w, h, cover, dither, threshold, gap, density, trim
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
        prepared = null
        ensurePrepared { result ->
            pendingBytes = result.tspl
            startSend(result.tspl)
        }
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
        val target = printer.findTargets().firstOrNull()
        if (target == null) {
            status.text = getString(R.string.status_usb_not_found)
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
                    status.text = getString(R.string.status_print_error, e.message ?: "")
                }
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
