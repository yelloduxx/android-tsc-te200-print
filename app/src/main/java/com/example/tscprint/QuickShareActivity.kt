package com.example.tscprint

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.ColorDrawable
import android.hardware.usb.UsbDevice
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import java.util.concurrent.Executors

class QuickShareActivity : Activity() {

    companion object {
        private const val ACTION_USB_PERMISSION = "com.example.tscprint.QUICK_USB_PERMISSION"
        private const val TAG = "QuickSharePrint"
    }

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val printer by lazy { UsbPrinter(this) }
    private val settings by lazy { PrintSettings(this) }
    private val quickShare by lazy { QuickShareSettings(this) }
    private val history by lazy { PrintHistory(this) }
    private var pendingUri: Uri? = null
    private var historyTimestamp: Long? = null

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            @Suppress("DEPRECATION")
            val device: UsbDevice? = intent.getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE)
            val granted = intent.getBooleanExtra(android.hardware.usb.UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (granted && device != null) startPrint(pendingUri)
            else {
                Log.w(TAG, "USB permission was denied")
                finish()
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(0x00000000))
        window.setWindowAnimations(0)
        window.attributes = window.attributes.apply {
            alpha = 0f
            dimAmount = 0f
        }
        setContentView(View(this))

        val uri = sharedUri(intent)
        val source = ShareSource.packageName(this, intent)
        source?.let { quickShare.recordCandidate(it) }
        if (uri == null || !quickShare.isAllowed(source)) {
            window.attributes = window.attributes.apply { alpha = 1f }
            forwardToMain(uri, source)
            return
        }
        pendingUri = uri
        registerPermissionReceiver()
        val target = printer.findTargets().firstOrNull()
        if (target == null) {
            Log.w(TAG, "No USB printer found for quick print")
            finish()
        } else if (!printer.hasPermission(target.device)) {
            printer.requestPermission(target.device, ACTION_USB_PERMISSION)
        } else {
            startPrint(uri)
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(permissionReceiver) }
        io.shutdown()
        super.onDestroy()
    }

    private fun startPrint(uri: Uri?) {
        if (uri == null) {
            finishWith(R.string.service_no_document)
            return
        }
        io.execute {
            try {
                val prepared = PdfToTspl.prepare(
                    this,
                    uri,
                    settings.widthMm,
                    settings.heightMm,
                    settings.cover,
                    settings.dither,
                    settings.threshold,
                    settings.gapMm,
                    settings.density,
                    settings.trim,
                    null
                )
                val target = printer.findTargets().firstOrNull()
                    ?: throw IllegalStateException(getString(R.string.status_usb_not_found))
                val copies = settings.copies.coerceIn(1, 999)
                val entry = history.add(
                    uri,
                    displayName(uri),
                    prepared.jobs.size,
                    copies,
                    getString(R.string.history_queued)
                )
                historyTimestamp = entry.timestamp
                if (settings.copyOrder == 0) {
                    repeat(copies) { prepared.jobs.forEach { printer.send(target, it) } }
                } else {
                    prepared.jobs.forEach { job -> repeat(copies) { printer.send(target, job) } }
                }
                history.updateStatus(entry.timestamp, getString(R.string.history_printed))
                main.post { finish() }
            } catch (e: Exception) {
                Log.e(TAG, "Quick print failed", e)
                val timestamp = historyTimestamp
                if (timestamp != null) {
                    history.updateStatus(timestamp, e.message ?: getString(R.string.status_printer_error))
                } else {
                    history.add(
                        uri,
                        displayName(uri),
                        0,
                        settings.copies.coerceIn(1, 999),
                        e.message ?: getString(R.string.status_printer_error)
                    )
                }
                main.post { finish() }
            }
        }
    }

    private fun forwardToMain(uri: Uri?, source: String?) {
        if (uri == null) {
            finish()
            return
        }
        val forward = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            source?.let { putExtra(ShareSource.EXTRA_SOURCE_PACKAGE, it) }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(forward)
        finish()
    }

    private fun sharedUri(intent: Intent?): Uri? {
        if (intent == null) return null
        @Suppress("DEPRECATION")
        val stream: Uri? = intent.getParcelableExtra(Intent.EXTRA_STREAM)
        return stream ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri ?: intent.data
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

    private fun displayName(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment ?: "PDF"

    private fun finishWith(message: Int) {
        Log.w(TAG, getString(message))
        finish()
    }
}
