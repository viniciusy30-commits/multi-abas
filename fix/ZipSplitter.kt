package com.vinicius.multiabas

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Divide um zip grande em vários zips menores (cada parte é um zip válido, com arquivos inteiros). */
object ZipSplitter {
    const val THRESHOLD = 30L * 1024 * 1024
    const val TARGET = 24L * 1024 * 1024

    data class Info(val name: String, val size: Long)

    fun info(ctx: Context, uri: Uri): Info {
        try {
            if (uri.scheme == "file") {
                val f = File(uri.path ?: "")
                return Info(f.name, f.length())
            }
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) return Info(c.getString(0) ?: "arquivo", if (c.isNull(1)) 0L else c.getLong(1))
            }
        } catch (_: Exception) {}
        return Info("arquivo", 0L)
    }

    /** Bloqueante: chame fora da thread principal. Devolve vazio se não for possível dividir. */
    fun split(ctx: Context, uri: Uri, name: String): List<Uri> {
        val outDir = File(ctx.cacheDir, "partes").apply { deleteRecursively(); mkdirs() }
        var tmp: File? = null
        try {
            val src: File = if (uri.scheme == "file") File(uri.path ?: "") else {
                val t = File(ctx.cacheDir, "zip-origem.tmp")
                ctx.contentResolver.openInputStream(uri)?.use { i -> t.outputStream().use { o -> i.copyTo(o) } }
                tmp = t
                t
            }
            ZipFile(src).use { zf ->
                val entries = zf.entries().toList().filter { !it.isDirectory }
                val groups = ArrayList<MutableList<ZipEntry>>()
                var cur = 0L
                for (e in entries) {
                    val cs = if (e.compressedSize >= 0) e.compressedSize else maxOf(e.size, 0L)
                    val cost = cs + e.name.length * 2L + 120
                    if (groups.isEmpty() || (cur + cost > TARGET && groups.last().isNotEmpty())) {
                        groups.add(ArrayList()); cur = 0L
                    }
                    groups.last().add(e)
                    cur += cost
                }
                if (groups.size <= 1) return emptyList()
                val base = name.removeSuffix(".zip").removeSuffix(".ZIP")
                val result = ArrayList<Uri>()
                val buf = ByteArray(64 * 1024)
                groups.forEachIndexed { idx, g ->
                    val out = File(outDir, "$base-parte${idx + 1}de${groups.size}.zip")
                    ZipOutputStream(out.outputStream().buffered()).use { zo ->
                        zo.setLevel(1)
                        for (e in g) {
                            val ne = ZipEntry(e.name)
                            if (e.time > 0) ne.time = e.time
                            zo.putNextEntry(ne)
                            zf.getInputStream(e).use { ins ->
                                while (true) {
                                    val n = ins.read(buf)
                                    if (n < 0) break
                                    zo.write(buf, 0, n)
                                }
                            }
                            zo.closeEntry()
                        }
                    }
                    result.add(Uri.fromFile(out))
                }
                return result
            }
        } catch (_: Exception) {
            return emptyList()
        } finally {
            tmp?.delete()
        }
    }
}
