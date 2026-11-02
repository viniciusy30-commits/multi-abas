package com.vinicius.multiabas

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Deixa o ZIP no formato que o workflow da pasta _incoming espera:
 * arquivos com caminho a partir da raiz do repositório (sem pasta externa tipo "repo-main/"),
 * sem lixo (__MACOSX, .DS_Store, .git) e com "/" nos caminhos.
 */
object ZipNormalizer {
    class Result(val bytes: ByteArray, val notes: List<String>)

    private val MARKER_DIRS = setOf("app", "gradle", "src", ".github", "_incoming")
    private val MARKER_FILES = setOf(
        "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
        "package.json", "index.html", "gradlew"
    )

    private fun clean(raw: String): String {
        var n = raw.replace('\\', '/')
        while (n.startsWith("./")) n = n.substring(2)
        n = n.trimStart('/')
        return n
    }

    private fun isJunk(n: String): Boolean {
        val parts = n.split('/')
        if (parts.any { it == ".." }) return true
        if (parts.any { it == "__MACOSX" || it == ".git" }) return true
        val last = parts.last()
        return last == ".DS_Store" || last == "Thumbs.db"
    }

    fun normalize(bytes: ByteArray, repo: String): Result {
        try {
            // 1ª passada: só os nomes
            val names = ArrayList<String>()
            var junk = 0
            var backslash = false
            ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (e.isDirectory) continue
                    if (e.name.contains('\\')) backslash = true
                    val n = clean(e.name)
                    if (n.isEmpty()) continue
                    if (isJunk(n)) { junk++; continue }
                    names.add(n)
                }
            }
            if (names.isEmpty()) return Result(bytes, emptyList())

            // pasta externa única?
            var strip = ""
            val roots = names.map { it.substringBefore('/') }.toSet()
            if (roots.size == 1 && names.all { it.contains('/') }) {
                val root = roots.first()
                val inside = names.map { it.substringAfter('/') }
                val hasMarker = inside.any { it in MARKER_FILES || it.substringBefore('/') in MARKER_DIRS && it.contains('/') }
                val looksWrapper = root.endsWith("-main") || root.endsWith("-master") || root.equals(repo, true)
                if (hasMarker || looksWrapper) strip = "$root/"
            }

            val notes = ArrayList<String>()
            if (strip.isNotEmpty()) notes.add("tirei a pasta externa \"${strip.trimEnd('/')}\"")
            if (junk > 0) notes.add("removi $junk arquivo(s) lixo")
            if (backslash) notes.add("corrigi caminhos com \\")
            if (notes.isEmpty()) return Result(bytes, emptyList())

            // 2ª passada: reescreve
            val out = ByteArrayOutputStream(bytes.size)
            val seen = HashSet<String>()
            ZipOutputStream(out).use { zo ->
                ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
                    while (true) {
                        val e = z.nextEntry ?: break
                        if (e.isDirectory) continue
                        var n = clean(e.name)
                        if (n.isEmpty() || isJunk(n)) continue
                        if (strip.isNotEmpty() && n.startsWith(strip)) n = n.substring(strip.length)
                        if (n.isEmpty() || !seen.add(n)) continue
                        val ne = ZipEntry(n)
                        if (e.time > 0) ne.time = e.time
                        zo.putNextEntry(ne)
                        z.copyTo(zo)
                        zo.closeEntry()
                    }
                }
            }
            return Result(out.toByteArray(), notes)
        } catch (_: Exception) {
            return Result(bytes, emptyList())
        }
    }
}
