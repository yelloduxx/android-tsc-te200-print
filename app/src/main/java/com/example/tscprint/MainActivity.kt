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
                status.text = "Доступ к USB отклонён"
                return
            }
            if (device != null) {
                settings.vendorId = device.vendorId
                settings.productId = device.productId
                status.text = "USB разрешён: ${device.deviceName}"
            }
            val bytes = pendingBytes
            if (bytes != null) {
                val target = printer.findTargets().firstOrNull()
                if (target != null) doSend(target, bytes) else status.text = "Принтер не найден"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        setupEdgeToEdge()
        loadSettings()
        registerPermissionReceiver()
        handleShareIntent(intent)
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

    private fun buildUi(): ViewGroup {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColor(MaterialR.attr.colorSurface))
        }

        val header = TextView(this).apply {
            text = "TSC QuickPrint"
            setTextAppearance(MaterialR.style.TextAppearance_Material3_HeadlineSmall)
            setTextColor(themeColor(MaterialR.attr.colorOnSurface))
            setPadding(dp(20), dp(14), dp(20), dp(14))
        }
        root.addView(header)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(12))
        }
        val scroll = ScrollView(this).apply { addView(content) }
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val printCard = newCard(content, "Печать из приложения")
        val pick = button("Выбрать PDF-файл", FILLED)
        pick.setOnClickListener { pickPdf() }
        printCard.addView(pick, matchWrap())

        fileName = TextView(this).apply {
            text = "Файл не выбран"
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

        val printBtn = button("Напечатать", TONAL)
        printBtn.setOnClickListener { startSendPrepared() }
        printCard.addView(printBtn, matchWrap())

        val testBtn = button(
            "Тестовая печать (текст и штрих-код)",
            OUTLINED
        )
        testBtn.setOnClickListener { testPrint() }
        printCard.addView(testBtn, matchWrap())

        val labelCard = newCard(content, "Параметры этикетки")
        labelCard.addView(hint("Размеры в миллиметрах, как в драйвере принтера."))
        widthField = textField(labelCard, "Ширина этикетки, мм", "58")
        heightField = textField(labelCard, "Высота этикетки, мм", "30")
        gapField = textField(labelCard, "Зазор между этикетками, мм", "2")
        densityField = textField(labelCard, "Плотность печати (0–15)", "8")

        labelCard.addView(hint("Масштабирование под этикетку:"))
        val group = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        coverRadio = MaterialRadioButton(this).apply {
            id = View.generateViewId()
            text = "Заполнить с обрезкой (cover)"
        }
        containRadio = MaterialRadioButton(this).apply {
            id = View.generateViewId()
            text = "Вписать целиком (без обрезки)"
        }
        group.addView(coverRadio)
        group.addView(containRadio)
        labelCard.addView(group)

        thresholdLabel = TextView(this).apply {
            text = "Порог бинаризации: 128"
            setTextAppearance(MaterialR.style.TextAppearance_Material3_BodyMedium)
            setTextColor(themeColor(MaterialR.attr.colorOnSurface))
            setPadding(0, dp(10), 0, 0)
        }
        labelCard.addView(thresholdLabel)
        labelCard.addView(hint("Для штрих-кодов оставьте ~128 и выключите дизеринг."))
        thresholdBar = Slider(this).apply {
            valueFrom = 0f
            valueTo = 255f
            stepSize = 1f
            value = 128f
            addOnChangeListener { _, value, _ ->
                thresholdLabel.text = "Порог бинаризации: ${value.toInt()}"
            }
        }
        labelCard.addView(thresholdBar)

        ditherCheck = MaterialCheckBox(this).apply { text = "Дизеринг (для фото и картинок)" }
        labelCard.addView(ditherCheck)

        trimCheck = MaterialCheckBox(this).apply {
            text = "Обрезать белые поля вокруг изображения"
        }
        labelCard.addView(trimCheck)

        val save = button("Сохранить настройки", FILLED)
        save.setOnClickListener { saveSettings() }
        content.addView(save, matchWrap())

        val accessCard = newCard(content, "Доступ к принтеру")
        val authorize = button(
            "Разрешить USB",
            TONAL
        )
        authorize.setOnClickListener { authorizeUsb() }
        accessCard.addView(authorize, matchWrap())

        status = TextView(this).apply {
            text = "Ежедневная печать: «Поделиться → Печать → TSC TE200»."
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
            header.setPadding(dp(20), top + dp(14), dp(20), dp(14))
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
        thresholdLabel.text = "Порог бинаризации: ${settings.threshold}"
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
        status.text = "Настройки сохранены: ${settings.widthMm}×${settings.heightMm} мм"
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
            fileName.text = "Выбран: ${selectedUri?.lastPathSegment ?: "PDF"}"
            status.text = "Готовим предпросмотр..."
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
        fileName.text = "Из «Поделиться»: ${uri.lastPathSegment ?: "PDF"}"
        status.text = "Подготовка к печати..."
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
            toast("Сначала выберите PDF-файл кнопкой «Выбрать PDF-файл»")
            return
        }
        val (w, h) = labelSize()
        val gap = gapField.text.toString().toIntOrNull()?.coerceIn(0, 20) ?: 2
        val density = densityField.text.toString().toIntOrNull()?.coerceIn(0, 15) ?: 8
        val cover = coverRadio.isChecked
        val dither = ditherCheck.isChecked
        val trim = trimCheck.isChecked
        val threshold = thresholdBar.value.toInt()
        status.text = "Обработка PDF..."
        io.execute {
            try {
                val result = PdfToTspl.prepare(
                    this, uri, w, h, cover, dither, threshold, gap, density, trim
                )
                main.post {
                    prepared = result
                    preview.setImageBitmap(result.preview)
                    status.text = "Готово: ${result.pageCount} стр., " +
                        "${result.widthDots}×${result.heightDots} точек"
                    onReady(result)
                }
            } catch (e: Exception) {
                main.post { status.text = "Ошибка: ${e.message}" }
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
            status.text = "USB-устройство не найдено. Подключите TE200 через OTG."
            return
        }
        if (printer.hasPermission(target.device)) {
            settings.vendorId = target.device.vendorId
            settings.productId = target.device.productId
            status.text = "Доступ уже есть: ${target.device.deviceName}"
            return
        }
        status.text = "Запрос доступа к USB..."
        printer.requestPermission(target.device, ACTION_USB_PERMISSION)
    }

    private fun startSend(bytes: ByteArray) {
        val target = printer.findTargets().firstOrNull()
        if (target == null) {
            status.text = "USB-устройство не найдено. Подключите TE200 через OTG."
            return
        }
        if (!printer.hasPermission(target.device)) {
            status.text = "Запрос доступа к USB..."
            printer.requestPermission(target.device, ACTION_USB_PERMISSION)
            return
        }
        doSend(target, bytes)
    }

    private fun doSend(target: UsbPrinter.Target, bytes: ByteArray) {
        status.text = "Отправка ${bytes.size} байт на ${target.device.deviceName}..."
        io.execute {
            try {
                val sent = printer.send(target, bytes)
                main.post { status.text = "Отправлено $sent байт" }
            } catch (e: Exception) {
                main.post { status.text = "Ошибка печати: ${e.message}" }
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
