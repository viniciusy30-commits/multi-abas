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
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Selo = foto redonda que fica no canto do avatar da aba.
 * Vem do GitHub (ícone do app ou imagem de capa do repositório) ou da galeria.
 * Chamar tudo daqui FORA da thread principal (exceto get/has/clear).
 */
object Badges {
    private const val API = "https://api.github.com"
    private const val SIZE = 160
    private const val MAX_BYTES = 8 * 1024 * 1024

    private val cache = HashMap<String, Bitmap>()

    private fun dir(ctx: Context) = File(ctx.filesDir, "badges").apply { mkdirs() }
    private fun file(ctx: Context, tabId: String) = File(dir(ctx), "$tabId.png")

    fun has(ctx: Context, tabId: String) = file(ctx, tabId).exists()

    fun get(ctx: Context, tabId: String): Bitmap? {
        cache[tabId]?.let { return it }
        val f = file(ctx, tabId)
        if (!f.exists()) return null
        return try {
            BitmapFactory.decodeFile(f.path)?.also { cache[tabId] = it }
        } catch (_: Exception) { null }
    }

    fun clear(ctx: Context, tabId: String) {
        cache.remove(tabId)
        try { file(ctx, tabId).delete() } catch (_: Exception) { }
    }

    /** Recorta no centro, deixa redondo e guarda. */
    fun save(ctx: Context, tabId: String, src: Bitmap): Boolean {
        return try {
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

    fun saveFromUri(ctx: Context, tabId: String, uri: Uri): Boolean {
        return try {
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return false
            val bmp = decode(bytes) ?: return false
            save(ctx, tabId, bmp)
        } catch (_: Exception) { false }
    }

    /**
     * cover = false → ícone do app (acha no repositório); se não houver, cai para a capa e depois o avatar do dono.
     * cover = true  → imagem de capa do repositório (social preview).
     */
    fun fetchForRepo(token: String, owner: String, repo: String, branch: String, cover: Boolean): Bitmap? {
        if (!cover) {
            findIconPath(token, owner, repo, branch)?.let { path ->
                rawFile(token, owner, repo, branch, path)?.let { decode(it) }?.let { return it }
            }
        }
        download("https://opengraph.githubassets.com/1/$owner/$repo")?.let { decode(it) }?.let { return it }
        if (!cover) return download("https://github.com/$owner.png?size=240")?.let { decode(it) }
        return null
    }

    // ───────────── internos ─────────────

    private fun decode(bytes: ByteArray): Bitmap? {
        return try {
            val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, b)
            var sample = 1
            val big = maxOf(b.outWidth, b.outHeight)
            while (big / sample > SIZE * 4) sample *= 2
            val o = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        } catch (_: Exception) { null }
    }

    private fun conn(url: String, token: String, accept: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 25000
        c.setRequestProperty("Accept", accept)
        c.setRequestProperty("User-Agent", "MultiAbas")
        if (token.isNotBlank()) c.setRequestProperty("Authorization", "Bearer $token")
        return c
    }

    private fun readLimited(c: HttpURLConnection): ByteArray? {
        if (c.responseCode !in 200..299) return null
        if (c.contentLengthLong > MAX_BYTES) return null
        val b = c.inputStream.use { it.readBytes() }
        return if (b.size > MAX_BYTES) null else b
    }

    private fun download(url: String): ByteArray? = try {
        readLimited(conn(url, "", "image/*,*/*"))
    } catch (_: Exception) { null }

    private fun rawFile(token: String, owner: String, repo: String, branch: String, path: String): ByteArray? = try {
        val ref = if (branch.isBlank()) "" else "?ref=" + Uri.encode(branch)
        val encoded = path.split('/').joinToString("/") { Uri.encode(it) }
        readLimited(conn("$API/repos/$owner/$repo/contents/$encoded$ref", token, "application/vnd.github.raw+json"))
    } catch (_: Exception) { null }

    /** Lê a árvore do repositório (1 chamada) e escolhe a melhor imagem de ícone. */
    private fun findIconPath(token: String, owner: String, repo: String, branch: String): String? {
        return try {
            val ref = if (branch.isBlank()) "HEAD" else Uri.encode(branch)
            val c = conn("$API/repos/$owner/$repo/git/trees/$ref?recursive=1", token, "application/vnd.github+json")
            if (c.responseCode !in 200..299) return null
            val arr = JSONObject(c.inputStream.bufferedReader().readText()).optJSONArray("tree") ?: return null
            var best: String? = null
            var bestScore = 0
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optString("type") != "blob") continue
                if (o.optLong("size", 0) > MAX_BYTES) continue
                val path = o.optString("path")
                val sc = score(path)
                if (sc > bestScore) { bestScore = sc; best = path }
            }
            best
        } catch (_: Exception) { null }
    }

    private fun score(path: String): Int {
        val p = path.lowercase()
        val ext = p.substringAfterLast('.', "")
        if (ext !in setOf("png", "webp", "jpg", "jpeg")) return 0
        if (p.contains("node_modules") || p.contains("/build/") || p.contains("/test") || p.contains("androidtest")) return 0
        val name = p.substringAfterLast('/').substringBeforeLast('.')

        // ícone de launcher do Android (quanto maior a densidade, melhor)
        if (name == "ic_launcher" || name == "ic_launcher_round") {
            val dens = when {
                p.contains("xxxhdpi") -> 100
                p.contains("xxhdpi") -> 95
                p.contains("xhdpi") -> 90
                p.contains("hdpi") -> 80
                p.contains("mdpi") -> 70
                else -> 60
            }
            return dens - (if (name.endsWith("round")) 3 else 0)
        }
        if (name == "ic_launcher_foreground") return 40
        val root = if (p.contains('/')) 0 else 10
        return when {
            name in setOf("icon", "app_icon", "appicon", "icone", "logo", "ícone") -> 55 + root
            name.contains("icon") || name.contains("logo") -> 45 + root
            name in setOf("capa", "cover", "banner", "preview", "thumbnail", "thumb") -> 30 + root
            name.contains("screenshot") -> 20 + root
            else -> 0
        }
    }
}
