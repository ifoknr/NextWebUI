package io.github.a13e300.ksuwebui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.LruCache
import android.widget.ImageView
import com.topjohnwu.superuser.nio.FileSystemManager
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Calm generated banner used when a module ships no image. */
class FallbackBannerDrawable(seed: String, private val glyph: String) : Drawable() {
    private val colors = PALETTES[(seed.hashCode() and 0x7fffffff) % PALETTES.size]
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val circle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(34, 255, 255, 255) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(225, 255, 255, 255)
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        bg.shader = LinearGradient(
            bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat(),
            colors[0], colors[1], Shader.TileMode.CLAMP
        )
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        canvas.drawRect(b, bg)
        canvas.drawCircle(b.left + w * 0.12f, b.top + h * 0.05f, h * 0.75f, circle)
        canvas.drawCircle(b.left + w * 0.62f, b.bottom + h * 0.15f, h * 0.5f, circle)
        text.textSize = h * 0.42f
        val y = b.exactCenterY() - (text.descent() + text.ascent()) / 2
        canvas.drawText(glyph, b.exactCenterX(), y, text)
    }

    override fun setAlpha(alpha: Int) {
        bg.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        bg.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE

    companion object {
        private val PALETTES = arrayOf(
            intArrayOf(0xFF5B4B8A.toInt(), 0xFFC9B8F0.toInt()),
            intArrayOf(0xFF1F6F5C.toInt(), 0xFFA8E6CF.toInt()),
            intArrayOf(0xFF8A4B2E.toInt(), 0xFFF6D5B8.toInt()),
            intArrayOf(0xFF2E4E7A.toInt(), 0xFFC5DBF5.toInt()),
            intArrayOf(0xFF7A2E55.toInt(), 0xFFF2C4DA.toInt()),
            intArrayOf(0xFF3E5F2B.toInt(), 0xFFD3EBB8.toInt()),
            intArrayOf(0xFF2B5D66.toInt(), 0xFFB9E4EA.toInt()),
        )
    }
}

fun String.avatarGlyph(): String {
    val i = indexOfFirst { it.isLetterOrDigit() }
    if (i < 0) return "•"
    return String(Character.toChars(codePointAt(i))).uppercase()
}

object BannerLoader {
    private const val MAX_BYTES = 8 * 1024 * 1024
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 16).toInt().coerceAtLeast(4 * 1024 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val failed = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    fun bind(context: Context, view: ImageView, module: Module, fs: FileSystemManager?) {
        val src = module.banner
        view.tag = src
        val cached = src?.let { cache.get(it) }
        if (cached != null) {
            view.setImageBitmap(cached)
            return
        }
        view.setImageDrawable(FallbackBannerDrawable(module.id, module.name.avatarGlyph()))
        if (src == null || src in failed) return
        val targetWidth = context.resources.displayMetrics.widthPixels
        App.executor.submit {
            val bmp = runCatching { decode(context, src, fs, targetWidth) }.getOrNull()
            if (bmp == null) {
                failed.add(src)
                return@submit
            }
            cache.put(src, bmp)
            view.post { if (view.tag == src) view.setImageBitmap(bmp) }
        }
    }

    private fun decode(context: Context, src: String, fs: FileSystemManager?, targetWidth: Int): Bitmap? {
        val bytes = if (src.isHttpUrl()) download(context, src) else {
            fs ?: return null
            fs.getFile(src).newInputStream().use { it.readCapped() }
        } ?: return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        if (opts.outWidth <= 0) return null
        var sample = 1
        while (opts.outWidth / (sample * 2) >= targetWidth) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        })
    }

    private fun download(context: Context, url: String): ByteArray? {
        val dir = File(context.cacheDir, "banners").apply { mkdirs() }
        val key = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        val file = File(dir, key)
        if (file.isFile && System.currentTimeMillis() - file.lastModified() < 7L * 24 * 3600 * 1000) {
            return file.readBytes()
        }
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        return try {
            if (conn.responseCode !in 200..299) return null
            val bytes = conn.inputStream.use { it.readCapped() } ?: return null
            file.writeBytes(bytes)
            bytes
        } finally {
            conn.disconnect()
        }
    }

    private fun InputStream.readCapped(): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_BYTES) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    fun clear() {
        cache.evictAll()
        failed.clear()
    }
}
