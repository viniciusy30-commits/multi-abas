package com.vinicius.multiabas

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Missão automática: tudo que precisa sobreviver a troca de conta, queda do app ou limite no meio.
 *
 * Fica em disco (filesDir/mission), com gravação atômica e cópia .bak:
 *  - PLANO-ORIGINAL.md : o pedido original, palavra por palavra. Nunca é reescrito durante a missão.
 *  - PLANO-VIVO.md     : o plano que o Claude mantém (passos [x]/[ ], decisões, próximo passo). Vem dentro de cada ZIP
 *                        entregue; o app guarda a versão mais nova e NÃO a envia ao repositório.
 *  - diario.txt        : linha do tempo da missão (contas usadas, limites, ZIPs aceitos/recusados).
 *  - base.zip          : cópia do código do repositório tirada no começo (usada se o GitHub falhar na troca de conta).
 *
 * Também confere se o ZIP entregue está "certo" antes de aceitar (ZIP válido, sem trechos omitidos, XML válido…).
 */
object Mission {
    const val MAX_ROUNDS = 60

    private val META = setOf("PLANO-VIVO.md", "CHECKPOINT.md")

    fun dir(ctx: Context): File = File(ctx.filesDir, "mission").apply { mkdirs() }

    private fun f(ctx: Context, name: String) = File(dir(ctx), name)

    private fun now() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    /** Grava por arquivo temporário + renomeia; guarda a versão anterior em .bak. */
    fun writeAtomic(file: File, text: String) {
        try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            if (file.exists()) {
                try { file.copyTo(File(file.parentFile, file.name + ".bak"), true) } catch (_: Exception) { }
            }
            if (!tmp.renameTo(file)) {
                file.writeText(text)
                tmp.delete()
            }
        } catch (_: Exception) { }
    }

    private fun readSafe(file: File): String {
        try {
            if (file.exists()) {
                val t = file.readText()
                if (t.isNotBlank()) return t
            }
        } catch (_: Exception) { }
        try {
            val bak = File(file.parentFile, file.name + ".bak")
            if (bak.exists()) return bak.readText()
        } catch (_: Exception) { }
        return ""
    }

    // ───────────── começo / plano ─────────────

    /** Nova missão: guarda o pedido original e zera o resto. */
    fun startRun(ctx: Context, text: String) {
        try {
            dir(ctx).listFiles()?.forEach { it.delete() }
        } catch (_: Exception) { }
        writeAtomic(f(ctx, "PLANO-ORIGINAL.md"),
            "# Pedido original (plano principal)\n\nIniciado em ${now()}\n\n" +
                "O objetivo abaixo não muda durante a missão.\n\n---\n\n" + text.trim() + "\n")
        journal(ctx, "Missão iniciada")
    }

    fun original(ctx: Context): String = readSafe(f(ctx, "PLANO-ORIGINAL.md"))

    fun living(ctx: Context): String = readSafe(f(ctx, "PLANO-VIVO.md"))

    fun diary(ctx: Context): String = readSafe(f(ctx, "diario.txt"))

    @Synchronized
    fun journal(ctx: Context, line: String) {
        try {
            val file = f(ctx, "diario.txt")
            file.appendText("${now()} | ${line.replace('\n', ' ')}\n")
            if (file.length() > 220_000L) {
                val t = file.readText()
                file.writeText(t.takeLast(150_000))
            }
        } catch (_: Exception) { }
    }

    /** O plano vivo não tem mais nenhum passo pendente "[ ]" e tem pelo menos um "[x]". */
    fun livingDone(ctx: Context): Boolean {
        val t = living(ctx)
        if (t.isBlank()) return false
        val open = Regex("""(?m)^\s*[-*]?\s*\[\s\]""").containsMatchIn(t)
        val done = Regex("""(?mi)^\s*[-*]?\s*\[x\]""").containsMatchIn(t)
        return done && !open
    }

    /** O Claude escreveu a linha final MISSAO-CONCLUIDA sozinha (e não só citou a regra no meio do texto). */
    fun isComplete(text: String): Boolean {
        val last = text.lines().lastOrNull { it.isNotBlank() } ?: return false
        return Regex("""(?i)^\W*MISS[ÃA]O[-_ ]CONCLU[ÍI]DA\W*$""").matches(last.trim())
    }

    // ───────────── cópia base do código ─────────────

    fun baseFile(ctx: Context): File = f(ctx, "base.zip")

    fun saveBase(ctx: Context, zip: File) {
        try { zip.copyTo(baseFile(ctx), true) } catch (_: Exception) { }
        cacheKey = ""
    }

    private var cacheKey = ""
    private var cacheSizes: Map<String, Long> = emptyMap()

    /** caminho (sem "codigo/") → tamanho, do código guardado no começo da missão. */
    fun baseIndex(ctx: Context): Map<String, Long> {
        val bf = baseFile(ctx)
        if (!bf.exists()) return emptyMap()
        val key = bf.length().toString() + ":" + bf.lastModified()
        if (key == cacheKey) return cacheSizes
        val m = HashMap<String, Long>()
        try {
            ZipInputStream(bf.inputStream().buffered()).use { zi ->
                var e = zi.nextEntry
                while (e != null) {
                    if (!e.isDirectory && e.name.startsWith("codigo/")) {
                        m[e.name.removePrefix("codigo/")] = zi.readBytes().size.toLong()
                    }
                    e = zi.nextEntry
                }
            }
        } catch (_: Exception) { }
        cacheKey = key
        cacheSizes = m
        return m
    }

    /** Conteúdo de alguns arquivos do código base (só os pedidos). */
    private fun baseTexts(ctx: Context, want: Set<String>): Map<String, String> {
        val out = HashMap<String, String>()
        if (want.isEmpty()) return out
        val bf = baseFile(ctx)
        if (!bf.exists()) return out
        try {
            ZipInputStream(bf.inputStream().buffered()).use { zi ->
                var e = zi.nextEntry
                while (e != null) {
                    if (!e.isDirectory && e.name.startsWith("codigo/")) {
                        val n = e.name.removePrefix("codigo/")
                        if (n in want) out[n] = String(zi.readBytes(), Charsets.UTF_8)
                    }
                    e = zi.nextEntry
                }
            }
        } catch (_: Exception) { }
        return out
    }

    // ───────────── ler / normalizar ZIP ─────────────

    fun isMeta(name: String) = name in META

    /** Lê o ZIP inteiro. null = corrompido. Ignora pastas e lixo de macOS. */
    fun readZip(bytes: ByteArray): List<Pair<String, ByteArray>>? {
        val out = ArrayList<Pair<String, ByteArray>>()
        var total = 0L
        try {
            ZipInputStream(bytes.inputStream()).use { zi ->
                var e = zi.nextEntry
                while (e != null) {
                    if (!e.isDirectory) {
                        val n = e.name.replace('\\', '/').trimStart('/')
                        if (n.isNotEmpty() && !n.startsWith("__MACOSX/") && !n.endsWith(".DS_Store")) {
                            val bo = ByteArrayOutputStream()
                            zi.copyTo(bo)
                            val b = bo.toByteArray()
                            total += b.size
                            if (total > 150L * 1024 * 1024 || out.size > 8000) return null
                            out.add(n to b)
                        }
                    }
                    e = zi.nextEntry
                }
            }
        } catch (_: Exception) {
            return null
        }
        return out
    }

    /**
     * Se o Claude embrulhou tudo numa pasta (ex.: "projeto/app/src/…"), devolve "projeto/" para tirar.
     * Só tira quando a pasta NÃO existe no repositório e o que sobra bate com arquivos que existem lá.
     */
    fun wrapperPrefix(ctx: Context, names: List<String>): String {
        if (names.isEmpty()) return ""
        val first = names[0].substringBefore('/', "")
        if (first.isEmpty() || !names[0].contains('/')) return ""
        val pre = "$first/"
        if (!names.all { it.startsWith(pre) }) return ""
        val idx = baseIndex(ctx)
        if (idx.isEmpty()) return ""
        if (idx.keys.any { it.startsWith(pre) }) return ""
        val hit = names.any { n ->
            val rest = n.removePrefix(pre)
            idx.containsKey(rest) || idx.keys.any { k -> k.startsWith(rest.substringBefore('/') + "/") && rest.contains('/') }
        }
        return if (hit) pre else ""
    }

    /** Guarda o PLANO-VIVO.md (e CHECKPOINT.md) que vem dentro do ZIP entregue. */
    fun captureMeta(ctx: Context, bytes: ByteArray) {
        try {
            val entries = readZip(bytes) ?: return
            val prefix = wrapperPrefix(ctx, entries.map { it.first })
            var got = false
            for ((raw, b) in entries) {
                val n = raw.removePrefix(prefix)
                if (n == "PLANO-VIVO.md" || n == "CHECKPOINT.md") {
                    val text = String(b, Charsets.UTF_8)
                    if (text.isNotBlank()) {
                        writeAtomic(f(ctx, "PLANO-VIVO.md"), text)
                        got = true
                    }
                }
            }
            if (!got) journal(ctx, "Aviso: o ZIP não trouxe PLANO-VIVO.md")
        } catch (_: Exception) { }
    }

    // ───────────── o ZIP está certo? ─────────────

    class Check(val hard: List<String>, val soft: List<String>) {
        fun all(): List<String> = hard + soft
    }

    private val ellipsisRe = Regex("""(?m)^\s*(//|/\*|#|<!--)\s*(\.{3}|…)""")
    private val phraseRe = Regex(
        """(?i)(//|/\*|^\s*\*|#|<!--)[^\n]{0,30}(resto d[oa]s? (c[oó]digo|arquivo|fun[cç][aã]o)|rest of (the )?(code|file|function|class)|c[oó]digo (existente|anterior|original|inalterado)|existing code|unchanged|inalterad[oa]|igual ao anterior)"""
    )

    private fun hasPlaceholder(t: String): Boolean =
        ellipsisRe.containsMatchIn(t) || phraseRe.containsMatchIn(t)

    private fun xmlOk(t: String): Boolean {
        return try {
            val p = android.util.Xml.newPullParser()
            p.setInput(java.io.StringReader(t))
            while (p.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) { }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** hard = tem que ser corrigido; soft = suspeito (o app pede uma confirmação e aceita se o Claude mantiver). */
    fun check(ctx: Context, bytes: ByteArray): Check {
        val hard = ArrayList<String>()
        val soft = ArrayList<String>()
        val entries = readZip(bytes)
        if (entries == null) {
            hard.add("o arquivo não é um ZIP válido (corrompido ou incompleto)")
            return Check(hard, soft)
        }
        val prefix = wrapperPrefix(ctx, entries.map { it.first })
        val idx = baseIndex(ctx)
        val codeNames = HashSet<String>()
        entries.forEach { (raw, _) ->
            val n = raw.removePrefix(prefix).lowercase()
            if (n.endsWith(".kt") || n.endsWith(".java") || n.endsWith(".kts") || n.endsWith(".xml")) codeNames.add(raw.removePrefix(prefix))
        }
        val baseTxt = baseTexts(ctx, codeNames)
        var files = 0
        for ((raw, b) in entries) {
            val n = raw.removePrefix(prefix)
            if (n.isEmpty() || isMeta(n) || n.startsWith(".github/")) continue
            if (n.contains("..")) { hard.add("caminho inválido: $n"); continue }
            files++
            val low = n.lowercase()
            val isCode = low.endsWith(".kt") || low.endsWith(".java") || low.endsWith(".kts")
            val isXml = low.endsWith(".xml")
            if (b.isEmpty() && !low.endsWith(".keep") && !low.endsWith(".gitkeep")) soft.add("$n está vazio")
            if (isCode || isXml) {
                val text = String(b, Charsets.UTF_8)
                if (hasPlaceholder(text) && !hasPlaceholder(baseTxt[n] ?: "")) {
                    hard.add("$n tem trecho omitido (\"...\" ou \"resto do código\"): mande o arquivo COMPLETO")
                }
                val bs = idx[n] ?: 0L
                if (bs >= 2500L && b.size < bs / 2) {
                    soft.add("$n encolheu de $bs para ${b.size} bytes (pode estar cortado)")
                }
                if (isXml && !xmlOk(text)) hard.add("$n não é um XML válido")
                if (isCode && !Lex(text).balanced()) soft.add("$n parece ter chaves ou parênteses sem fechar")
            }
        }
        if (files == 0) hard.add("o ZIP não traz nenhum arquivo do projeto (vazio ou só com PLANO-VIVO.md)")
        return Check(hard, soft)
    }

    /** Confere chaves/parênteses/colchetes de código Kotlin/Java, ignorando strings e comentários. */
    private class Lex(val s: String) {
        var i = 0
        var ok = true

        fun balanced(): Boolean {
            try { code(false) } catch (_: Exception) { return true }
            return ok
        }

        private fun nextIs(c: Char) = i + 1 < s.length && s[i + 1] == c

        private fun code(inTemplate: Boolean) {
            val stack = ArrayList<Char>()
            while (i < s.length && ok) {
                val c = s[i]
                if (c == '/' && nextIs('/')) {
                    while (i < s.length && s[i] != '\n') i++
                    continue
                }
                if (c == '/' && nextIs('*')) { blockComment(); continue }
                if (c == '"') {
                    if (s.startsWith("\"\"\"", i)) rawString() else string()
                    continue
                }
                if (c == '\'') { charLit(); continue }
                if (c == '{' || c == '(' || c == '[') { stack.add(c); i++; continue }
                if (c == '}' || c == ')' || c == ']') {
                    if (c == '}' && stack.isEmpty() && inTemplate) { i++; return }
                    val open = if (c == '}') '{' else if (c == ')') '(' else '['
                    if (stack.isEmpty() || stack.removeAt(stack.size - 1) != open) { ok = false; return }
                    i++
                    continue
                }
                i++
            }
            if (inTemplate || stack.isNotEmpty()) ok = false
        }

        private fun blockComment() {
            var depth = 1
            i += 2
            while (i < s.length && depth > 0) {
                if (s[i] == '/' && nextIs('*')) { depth++; i += 2 }
                else if (s[i] == '*' && nextIs('/')) { depth--; i += 2 }
                else i++
            }
        }

        private fun charLit() {
            i++
            if (i < s.length && s[i] == '\\') i += 2 else i++
            while (i < s.length && s[i] != '\'' && s[i] != '\n') i++
            if (i < s.length && s[i] == '\'') i++
        }

        private fun string() {
            i++
            while (i < s.length && ok) {
                val c = s[i]
                if (c == '\\') { i += 2; continue }
                if (c == '"') { i++; return }
                if (c == '$' && nextIs('{')) { i += 2; code(true); continue }
                if (c == '\n') return
                i++
            }
        }

        private fun rawString() {
            i += 3
            while (i < s.length && ok) {
                if (s.startsWith("\"\"\"", i)) {
                    i += 3
                    while (i < s.length && s[i] == '"') i++
                    return
                }
                if (s[i] == '$' && nextIs('{')) { i += 2; code(true); continue }
                i++
            }
        }
    }
}
