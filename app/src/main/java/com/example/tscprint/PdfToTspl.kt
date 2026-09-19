package com.example.tscprint

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

object PdfToTspl {

    const val DOTS_PER_MM = 8
    const val DPI = 203
    private const val MAX_RENDER_PX = 4000

    data class Prepared(
        val tspl: ByteArray,
        val preview: Bitmap,
        val widthDots: Int,
        val heightDots: Int,
        val pageCount: Int,
        val jobs: List<ByteArray> = listOf(tspl)
    )

    fun prepare(
        context: Context,
        uri: Uri,
        widthMm: Int,
        heightMm: Int,
        cover: Boolean,
        dither: Boolean,
        threshold: Int,
        gapMm: Int = 2,
        density: Int = 8,
        trim: Boolean = true,
        selectedPages: Set<Int>? = null
    ): Prepared {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalStateException("Could not open file")
        return pfd.use {
            prepare(it, widthMm, heightMm, cover, dither, threshold, gapMm, density, trim, selectedPages)
        }
    }

    fun prepare(
        pfd: ParcelFileDescriptor,
        widthMm: Int,
        heightMm: Int,
        cover: Boolean,
        dither: Boolean,
        threshold: Int,
        gapMm: Int = 2,
        density: Int = 8,
        trim: Boolean = true,
        selectedPages: Set<Int>? = null
    ): Prepared {
        val targetW = Math.round(widthMm * DOTS_PER_MM.toFloat())
        val targetH = Math.round(heightMm * DOTS_PER_MM.toFloat())

        val renderer = PdfRenderer(pfd)
        renderer.use { r ->
            if (r.pageCount < 1) throw IllegalStateException("PDF has no pages")
            val pages = selectedPages ?: (0 until r.pageCount).toSet()
            if (pages.none { it in 0 until r.pageCount }) {
                throw IllegalStateException("No pages selected")
            }
            val output = ByteArrayOutputStream()
            val jobs = mutableListOf<ByteArray>()
            var preview: Bitmap? = null
            for (index in 0 until r.pageCount) {
                if (index !in pages) continue
                val page = r.openPage(index)
                page.use { p ->
                    val renderW = Math.ceil(p.width / 72.0 * DPI)
                        .toInt().coerceIn(1, MAX_RENDER_PX)
                    val renderH = Math.ceil(p.height / 72.0 * DPI)
                        .toInt().coerceIn(1, MAX_RENDER_PX)
                    Log.d(
                        "PdfToTspl",
                        "page ${index + 1}/${r.pageCount} ${p.width}x${p.height}pt " +
                            "render=${renderW}x${renderH} target=${targetW}x${targetH} " +
                            "cover=$cover trim=$trim thr=$threshold"
                    )
                    var src = Bitmap.createBitmap(renderW, renderH, Bitmap.Config.ARGB_8888)
                    src.eraseColor(Color.WHITE)
                    p.render(src, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

                    if (trim) {
                        val cropped = cropToContent(src, threshold)
                        if (cropped !== src) {
                            src.recycle()
                            src = cropped
                        }
                    }

                    val fitted = fit(src, targetW, targetH, cover)
                    if (fitted !== src) src.recycle()

                    val mono = toMono(fitted, dither, threshold)
                    fitted.recycle()
                    val packed = pack(mono, targetW, targetH)
                    val job = buildTspl(packed, targetW, targetH, widthMm, heightMm, gapMm, density)
                    jobs += job
                    output.write(job)
                    if (preview == null) preview = toPreview(mono, targetW, targetH)
                }
            }
            return Prepared(
                output.toByteArray(),
                preview ?: throw IllegalStateException("Could not render pages"),
                targetW,
                targetH,
                r.pageCount,
                jobs
            )
        }
    }

    fun renderThumbnails(
        context: Context,
        uri: Uri,
        maxWidth: Int = 180,
        maxHeight: Int = 240
    ): List<Bitmap> {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalStateException("Could not open file")
        return pfd.use { renderThumbnails(it, maxWidth, maxHeight) }
    }

    fun renderThumbnails(
        pfd: ParcelFileDescriptor,
        maxWidth: Int = 180,
        maxHeight: Int = 240
    ): List<Bitmap> {
        val renderer = PdfRenderer(pfd)
        renderer.use { r ->
            if (r.pageCount < 1) throw IllegalStateException("PDF has no pages")
            return (0 until r.pageCount).map { index ->
                val page = r.openPage(index)
                page.use { p ->
                    val scale = minOf(
                        maxWidth.toFloat() / p.width,
                        maxHeight.toFloat() / p.height
                    )
                    val width = (p.width * scale).roundToInt().coerceAtLeast(1)
                    val height = (p.height * scale).roundToInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(maxWidth, maxHeight, Bitmap.Config.RGB_565)
                    bitmap.eraseColor(Color.WHITE)
                    val left = (maxWidth - width) / 2
                    val top = (maxHeight - height) / 2
                    p.render(
                        bitmap,
                        Rect(left, top, left + width, top + height),
                        null,
                        PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                    )
                    bitmap
                }
            }
        }
    }

    private fun cropToContent(src: Bitmap, threshold: Int): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = pixels[y * w + x]
                val lum = 0.299f * ((c shr 16) and 0xFF) +
                    0.587f * ((c shr 8) and 0xFF) +
                    0.114f * (c and 0xFF)
                if (lum < threshold) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < minX || maxY < minY) return src
        val pad = 4
        val left = (minX - pad).coerceAtLeast(0)
        val top = (minY - pad).coerceAtLeast(0)
        val right = (maxX + pad).coerceAtMost(w - 1)
        val bottom = (maxY + pad).coerceAtMost(h - 1)
        if (left == 0 && top == 0 && right == w - 1 && bottom == h - 1) return src
        return Bitmap.createBitmap(src, left, top, right - left + 1, bottom - top + 1)
    }

    private fun fit(src: Bitmap, targetW: Int, targetH: Int, cover: Boolean): Bitmap {
        val sw = src.width
        val sh = src.height
        val scale = if (cover) {
            maxOf(targetW.toFloat() / sw, targetH.toFloat() / sh)
        } else {
            minOf(targetW.toFloat() / sw, targetH.toFloat() / sh)
        }
        val dst = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        dst.eraseColor(Color.WHITE)
        val canvas = Canvas(dst)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val matrix = Matrix()
        matrix.postScale(scale, scale)
        matrix.postTranslate((targetW - sw * scale) / 2f, (targetH - sh * scale) / 2f)
        canvas.drawBitmap(src, matrix, paint)
        return dst
    }

    private fun toMono(src: Bitmap, dither: Boolean, threshold: Int): BooleanArray {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val gray = FloatArray(w * h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val luminance = 0.299f * r + 0.587f * g + 0.114f * b
            val colorSpread = maxOf(r, g, b) - minOf(r, g, b)
            // Saturated logos (for example the pink WB mark) can be brighter
            // than the binary threshold while still being visible ink.
            gray[i] = if (colorSpread >= 40 && minOf(r, g, b) < 220) {
                0f
            } else {
                luminance
            }
        }
        val out = BooleanArray(w * h)
        if (!dither) {
            for (i in out.indices) out[i] = gray[i] < threshold
        } else {
            val buf = gray.copyOf()
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val i = y * w + x
                    val old = buf[i]
                    val black = old < threshold
                    out[i] = black
                    val nv = if (black) 0f else 255f
                    val err = old - nv
                    if (x + 1 < w) buf[i + 1] += err * 7f / 16f
                    if (y + 1 < h) {
                        if (x > 0) buf[i + w - 1] += err * 3f / 16f
                        buf[i + w] += err * 5f / 16f
                        if (x + 1 < w) buf[i + w + 1] += err * 1f / 16f
                    }
                }
            }
        }
        return out
    }

    private fun pack(mono: BooleanArray, w: Int, h: Int): ByteArray {
        val bytesPerRow = (w + 7) / 8
        val out = ByteArray(bytesPerRow * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (!mono[y * w + x]) {
                    val idx = y * bytesPerRow + (x shr 3)
                    out[idx] = (out[idx].toInt() or (0x80 shr (x and 7))).toByte()
                }
            }
        }
        return out
    }

    private fun toPreview(mono: BooleanArray, w: Int, h: Int): Bitmap {
        val pixels = IntArray(w * h)
        for (i in pixels.indices) {
            pixels[i] = if (mono[i]) Color.BLACK else Color.WHITE
        }
        val base = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        base.setPixels(pixels, 0, w, 0, 0, w, h)
        val scale = 4
        val big = Bitmap.createScaledBitmap(base, w * scale, h * scale, false)
        if (big !== base) base.recycle()
        return big
    }

    private fun buildTspl(
        packed: ByteArray,
        widthDots: Int,
        heightDots: Int,
        widthMm: Int,
        heightMm: Int,
        gapMm: Int,
        density: Int
    ): ByteArray {
        val bytesPerRow = (widthDots + 7) / 8
        val header = (
            "SIZE ${widthMm} mm,${heightMm} mm\r\n" +
                "GAP ${gapMm} mm,0 mm\r\n" +
                "DENSITY ${density.coerceIn(0, 15)}\r\n" +
                "DIRECTION 1\r\n" +
                "REFERENCE 0,0\r\n" +
                "CLS\r\n"
            ).toByteArray(Charsets.US_ASCII)
        val bitmapHead = "BITMAP 0,0,${bytesPerRow},${heightDots},0,"
            .toByteArray(Charsets.US_ASCII)
        val tail = "\r\nPRINT 1,1\r\n".toByteArray(Charsets.US_ASCII)
        val out = ByteArrayOutputStream()
        out.write(header)
        out.write(bitmapHead)
        out.write(packed)
        out.write(tail)
        return out.toByteArray()
    }
}
