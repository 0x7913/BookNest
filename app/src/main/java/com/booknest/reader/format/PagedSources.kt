package com.booknest.reader.format

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.booknest.reader.core.PagedSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

/** PDF：系统内置 PdfRenderer（底层 PDFium），零第三方依赖。 */
class PdfSource(file: File) : PagedSource {
    private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(descriptor)
    override val pageCount: Int = renderer.pageCount

    override suspend fun render(index: Int, targetWidthPx: Int, night: Boolean, crop: Float): Bitmap? =
        withContext(Dispatchers.IO) {
            if (index !in 0 until pageCount) return@withContext null
            runCatching {
                val page = renderer.openPage(index)
                val width = targetWidthPx.coerceIn(320, 2400)
                val scale = width.toFloat() / page.width.coerceAtLeast(1)
                val height = (page.height * scale).toInt().coerceIn(320, 3600)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                finish(bitmap, night, crop)
            }.getOrNull()
        }

    override fun close() {
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
    }
}

/** CBZ：ZIP 图片流 + 按页解码，支持夜间亮度映射与裁边。 */
class CbzSource(file: File) : PagedSource {
    private val zip = ZipFile(file)
    private val images = zip.entries().asSequence()
        .filter { !it.isDirectory && it.name.substringAfterLast('.', "").lowercase() in IMAGE_EXTS }
        .sortedBy { it.name }
        .map { it.name }
        .toList()
    override val pageCount: Int = images.size

    override suspend fun render(index: Int, targetWidthPx: Int, night: Boolean, crop: Float): Bitmap? =
        withContext(Dispatchers.IO) {
            val name = images.getOrNull(index) ?: return@withContext null
            val entry = zip.getEntry(name) ?: return@withContext null
            runCatching {
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / sample > targetWidthPx.coerceIn(320, 2400) * 1.4f) sample *= 2
                val options = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                if (bitmap == null) null else finish(bitmap, night, crop)
            }.getOrNull()
        }

    override fun close() = runCatching { zip.close() }.let { Unit }

    companion object {
        private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")
    }
}

private fun finish(source: Bitmap, night: Boolean, crop: Float): Bitmap {
    val cropped = if (crop > 0.001f) {
        val dx = (source.width * crop * 0.12f).toInt().coerceAtMost(source.width / 4)
        val dy = (source.height * crop * 0.10f).toInt().coerceAtMost(source.height / 4)
        Bitmap.createBitmap(
            source, dx, dy,
            (source.width - dx * 2).coerceAtLeast(1), (source.height - dy * 2).coerceAtLeast(1)
        )
    } else source
    return if (night) nightLuminanceMap(cropped) else cropped
}

/**
 * 夜间反色：基于亮度曲线的重映射 + 色度保留，而非简单的 RGB 取反。
 * 白底 → 深灰，黑字 → 亮灰；彩色插图的色相得以保留，长时间阅读不易刺眼。
 */
fun nightLuminanceMap(bitmap: Bitmap): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
    for (i in pixels.indices) {
        val c = pixels[i]
        val r = Color.red(c)
        val g = Color.green(c)
        val b = Color.blue(c)
        val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
        // gamma 曲线：中间调压缩，两端留白，避免纯黑纯白的强烈反差
        val normalized = lum / 255f
        val target = 232f - 176f * Math.pow(normalized.toDouble(), 0.82).toFloat()
        val factor = target / (lum + 1f)
        val nr = (r * factor).toInt().coerceIn(0, 255)
        val ng = (g * factor).toInt().coerceIn(0, 255)
        val nb = (b * factor).toInt().coerceIn(0, 255)
        pixels[i] = Color.argb(Color.alpha(c), nr, ng, nb)
    }
    bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    return bitmap
}
