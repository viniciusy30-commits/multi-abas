package com.vinicius.multiabas

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Confere o zip baixado e, se passar de 30 MB, faz uma versão enxuta (sem imagens/binários pesados). */
object LeanZip {
    class Out(val file: File, val omitted: List<String>, val kept: Int)

    private val HEAVY = setOf(
        "png", "jpg", "jpeg", "webp", "gif", "ogg", "mp3", "wav", "mp4", "webm",
        "apk", "aab", "jar", "so", "ttf", "otf", "zip", "7z", "glb", "obj", "psd"
    )
    private const val TARGET = 24L * 1024 * 1024

    /** Número de arquivos do zip, ou -1 se estiver corrompido/cortado. */
    fun count(f: File): Int = try {
        ZipFile(f).use { z -> z.entries().toList().count { !it.isDirectory } }
    } catch (_: Exception) { -1 }

    fun sizeOk(f: File) = f.length() <= ZipSplitter.THRESHOLD

    /** Devolve null se não foi possível ficar abaixo do limite só tirando os arquivos pesados. */
    fun make(src: File, dst: File): Out? {
        try {
            ZipFile(src).use { z ->
                val all = z.entries().toList().filter { !it.isDirectory }
                fun cost(e: ZipEntry) = (if (e.compressedSize >= 0) e.compressedSize else e.size) + e.name.length * 2L + 120
                var total = all.sumOf { cost(it) }
                val heavy = all.filter { it.name.substringAfterLast('.', "").lowercase() in HEAVY }
                    .sortedByDescending { cost(it) }
                val drop = HashSet<String>()
                for (e in heavy) {
                    if (total <= TARGET) break
                    drop.add(e.name); total -= cost(e)
                }
                if (total > TARGET) return null
                dst.parentFile?.mkdirs()
                if (dst.exists()) dst.delete()
                val buf = ByteArray(64 * 1024)
                var kept = 0
                ZipOutputStream(dst.outputStream().buffered()).use { zo ->
                    for (e in all) {
                        if (e.name in drop) continue
                        val ne = ZipEntry(e.name)
                        if (e.time > 0) ne.time = e.time
                        zo.putNextEntry(ne)
                        z.getInputStream(e).use { ins ->
                            while (true) { val n = ins.read(buf); if (n < 0) break; zo.write(buf, 0, n) }
                        }
                        zo.closeEntry()
                        kept++
                    }
                    zo.putNextEntry(ZipEntry("_ARQUIVOS_OMITIDOS.txt"))
                    val txt = "Arquivos pesados (imagens/binários) deixados de fora para o zip caber em 30 MB:\n" +
                        drop.sorted().joinToString("\n") + "\n"
                    zo.write(txt.toByteArray())
                    zo.closeEntry()
                }
                return Out(dst, drop.sorted(), kept)
            }
        } catch (_: Exception) {
            return null
        }
    }
}
