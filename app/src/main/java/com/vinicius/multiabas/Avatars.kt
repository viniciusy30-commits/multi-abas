package com.vinicius.multiabas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.net.Uri
import java.io.File

/** Foto da conta: aparece no lugar da letra inicial da aba. Chamar save/saveFromUri fora da thread principal. */
object Avatars {
    private const val SIZE = 192
    private val cache = HashMap<String, Bitmap>()

    private fun file(ctx: Context, tabId: String) =
        File(File(ctx.filesDir, "avatars").apply { mkdirs() }, "$tabId.png")

    fun has(ctx: Context, tabId: String) = file(ctx, tabId).exists()

    fun get(ctx: Context, tabId: String): Bitmap? {
        cache[tabId]?.let { return it }
        val f = file(ctx, tabId)
        if (!f.exists()) return null
        return try { BitmapFactory.decodeFile(f.path)?.also { cache[tabId] = it } } catch (_: Exception) { null }
    }

    fun clear(ctx: Context, tabId: String) {
        cache.remove(tabId)
        try { file(ctx, tabId).delete() } catch (_: Exception) { }
    }

    fun saveFromUri(ctx: Context, tabId: String, uri: Uri): Boolean {
        return try {
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return false
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > SIZE * 4) sample *= 2
            val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return false
            val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            val side = minOf(src.width, src.height)
            val sx = (src.width - side) / 2
            val sy = (src.height - side) / 2
            val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            c.drawCircle(SIZE / 2f, SIZE / 2f, SIZE / 2f, p)
            p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            c.drawBitmap(src, Rect(sx, sy, sx + side, sy + side), Rect(0, 0, SIZE, SIZE), p)
            file(ctx, tabId).outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
            cache[tabId] = out
            true
        } catch (_: Exception) { false }
    }
}
