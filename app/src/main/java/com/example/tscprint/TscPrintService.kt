package com.example.tscprint

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

class TscPrintService : PrintService() {

    private val main = Handler(Looper.getMainLooper())

    private fun str(resId: Int): String = LocaleHelper.wrap(this).getString(resId)

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession = Session()

    override fun onPrintJobQueued(job: PrintJob) {
        if (!job.start()) return
        val pfd: ParcelFileDescriptor? = try {
            job.document?.data
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось получить документ задания", e)
            null
        }
        if (pfd == null) {
            job.fail(str(R.string.service_no_document))
            return
        }
        val settings = PrintSettings(this)
        Thread {
            try {
                val tspl = processDocument(pfd, settings)
                val printer = UsbPrinter(this)
                val target = findPrinter(printer)
                if (target == null) {
                    main.post { job.fail(str(R.string.service_printer_not_connected)) }
                } else {
                    printer.send(target, tspl)
                    main.post { job.complete() }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка печати", e)
                main.post { job.fail(str(R.string.service_print_failed)) }
            }
        }.start()
    }

    private fun processDocument(pfd: ParcelFileDescriptor, settings: PrintSettings): ByteArray {
        val temp = File.createTempFile("print", ".pdf", cacheDir)
        try {
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                FileOutputStream(temp).use { output -> input.copyTo(output) }
            }
            try {
                temp.copyTo(File(cacheDir, "last_job.pdf"), overwrite = true)
            } catch (e: Exception) {
                Log.e(TAG, "Не удалось сохранить last_job.pdf", e)
            }
            val filePfd = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY)
            val prepared = filePfd.use {
                PdfToTspl.prepare(
                    it,
                    settings.widthMm,
                    settings.heightMm,
                    settings.cover,
                    settings.dither,
                    settings.threshold,
                    settings.gapMm,
                    settings.density,
                    settings.trim
                )
            }
            try {
                FileOutputStream(File(cacheDir, "last_preview.png")).use {
                    prepared.preview.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Не удалось сохранить last_preview.png", e)
            }
            val copies = settings.copies.coerceIn(1, 999)
            if (copies == 1) return prepared.tspl
            return ByteArrayOutputStream(prepared.tspl.size * copies).use { output ->
                repeat(copies) { output.write(prepared.tspl) }
                output.toByteArray()
            }
        } finally {
            temp.delete()
        }
    }

    override fun onRequestCancelPrintJob(job: PrintJob) {
        job.cancel()
    }

    private fun findPrinter(printer: UsbPrinter): UsbPrinter.Target? {
        val settings = PrintSettings(this)
        return printer.findTarget(settings.vendorId, settings.productId)
    }

    private inner class Session : PrinterDiscoverySession() {

        override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) {
            publish()
        }

        override fun onValidatePrinters(printerIds: MutableList<PrinterId>) {
            publish()
        }

        override fun onStartPrinterStateTracking(printerId: PrinterId) {
            publish()
        }

        override fun onStopPrinterDiscovery() {}

        override fun onStopPrinterStateTracking(printerId: PrinterId) {}

        override fun onDestroy() {}

        private fun publish() {
            val settings = PrintSettings(this@TscPrintService)
            val widthMils = (settings.widthMm * 1000.0 / 25.4).toInt()
            val heightMils = (settings.heightMm * 1000.0 / 25.4).toInt()
            val media = PrintAttributes.MediaSize(
                "tsc_label", "TSC Label",
                widthMils, heightMils
            )
            val resolution = PrintAttributes.Resolution("203dpi", "203 dpi", 203, 203)

            val id = generatePrinterId("TSC TE200")
            val caps = PrinterCapabilitiesInfo.Builder(id)
                .addMediaSize(media, true)
                .addResolution(resolution, true)
                .setColorModes(
                    PrintAttributes.COLOR_MODE_MONOCHROME,
                    PrintAttributes.COLOR_MODE_MONOCHROME
                )
                .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                .build()

            val connected = findPrinter(UsbPrinter(this@TscPrintService)) != null
            val status = if (connected) {
                PrinterInfo.STATUS_IDLE
            } else {
                PrinterInfo.STATUS_UNAVAILABLE
            }

            val info = PrinterInfo.Builder(id, "TSC TE200", status)
                .setCapabilities(caps)
                .build()
            addPrinters(listOf(info))
        }
    }

    companion object {
        private const val TAG = "TscPrintService"
    }
}
