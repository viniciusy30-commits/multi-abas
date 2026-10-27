package com.vinicius.multiabas

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Build falhou? Copia o erro do GitHub para um .txt, anexa no chat do Claude, pede a correção,
 * baixa o ZIP corrigido e deixa o app entregar de novo ao GitHub — sozinho, até o build passar.
 * Para depois de MAX tentativas seguidas com erro (para não gastar o limite à toa).
 */
class AutoFix(private val a: MainActivity) {
    companion object { const val MAX = 6 }

    private enum class Phase { IDLE, SEND, WAIT, ZIP }

    private val h get() = a.scrHandler()
    private var gen = 0
    private var phase = Phase.IDLE
    @Volatile var active = false
        private set
    var tabId = ""
        private set
    private var projId = ""
    private val tries = HashMap<String, Int>()
    @Volatile private var zipArrived = false
    private var baseU = 0
    private var sentAt = 0L
    private var lastLimitChk = 0L
    private var lastDismissTxt = ""

    fun enabled() = a.getSharedPreferences("tabs", 0).getBoolean("autofix", true)

    private fun alive(g: Int) = g == gen && active

    /** Qual aba usar: a mesma do ciclo em andamento, senão a que o app apontou. */
    fun tabFor(guess: String) = if (active && tabId.isNotEmpty()) tabId else guess

    fun onZipDownloaded() { zipArrived = true }

    /** Build passou: zera a contagem de tentativas e encerra o ciclo. */
    fun onBuildOk(projectId: String) {
        tries.remove(projectId)
        if (active) {
            gen++
            active = false
            phase = Phase.IDLE
            Handoff.log(a, projectId, "Correção automática: build passou ✓")
            a.runOnUiThread { a.scrSay("Build corrigido e passou ✓", true) }
        }
    }

    /** A entrega falhou por outro motivo (sem log de build): encerra o ciclo. */
    fun cancel() {
        if (!active) return
        gen++
        active = false
        phase = Phase.IDLE
    }

    private fun giveUp(why: String) {
        gen++
        active = false
        phase = Phase.IDLE
        Handoff.log(a, projId, "Correção automática parou: $why")
        a.runOnUiThread { a.scrSay("Correção automática parou: $why", true) }
    }

    fun start(proj: Project, zipName: String, log: String, runUrl: String?, tab: String) {
        if (!enabled()) return
        if (Script.status == Script.RUNNING) return
        val n = (tries[proj.id] ?: 0) + 1
        if (n > MAX) {
            tries.remove(proj.id)
            active = false
            a.scrSay("Parei de corrigir sozinho depois de $MAX tentativas seguidas. Veja o erro no GitHub.", true)
            Handoff.log(a, proj.id, "Correção automática: $MAX tentativas sem sucesso")
            return
        }
        val t = a.scrTab(tab)
        if (t == null || a.scrWeb(t) == null) {
            a.scrSay("Build falhou, mas não achei a aba do Claude para mandar o erro", true)
            return
        }
        tries[proj.id] = n
        gen++
        val g = gen
        active = true
        zipArrived = false
        tabId = t.id
        projId = proj.id
        phase = Phase.SEND

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dir = File(a.cacheDir, "handoff_out").apply { mkdirs() }
        val f = File(dir, "erro-build-$stamp.txt")
        val header = "Projeto: ${proj.fullRepo()}\nZIP entregue: $zipName\nBuild: ${runUrl ?: "-"}\nTentativa $n de $MAX\n\n"
        try { f.writeText(header + log) } catch (_: Exception) {
            giveUp("não consegui criar o arquivo do erro"); return
        }
        // também deixa o erro copiado (área de transferência)
        a.runOnUiThread {
            try {
                val cm = a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("erro do build", (header + log).take(90_000)))
            } catch (_: Exception) { }
            a.scrSay("Build falhou: mandando o erro para o Claude ($n/$MAX)…", true)
            a.scrSelect(t)
        }
        Handoff.log(a, proj.id, "Build falhou: erro enviado ao Claude para corrigir ($n/$MAX)")

        val msg = "O build no GitHub FALHOU depois que o ZIP \"$zipName\" foi entregue (projeto ${proj.fullRepo()}). " +
            "O log com os erros está no arquivo anexo ${f.name}. Leia o arquivo inteiro, ache a causa e corrija o código. " +
            "Entregue UM ZIP corrigido para eu baixar, com só os arquivos alterados/novos na estrutura do projeto, " +
            "sem refazer o que já está pronto e nunca como código para copiar e colar. (tentativa $n de $MAX)"
        guardLoop(g)
        send(g, t, msg, f, 0)
    }

    // ───────────── guarda: fecha avisos e vigia o limite ─────────────

    private fun guardLoop(g: Int) {
        h.postDelayed({
            if (!alive(g)) return@postDelayed
            try { guardTick(g) } catch (_: Exception) { }
            guardLoop(g)
        }, 1200)
    }

    private fun guardTick(g: Int) {
        a.scrDismissAppDialogs()
        val tab = a.scrTab(tabId) ?: return
        val w = a.scrWeb(tab) ?: return
        w.evaluateJavascript(ScriptJs.CLEAR) { raw ->
            if (!alive(g)) return@evaluateJavascript
            val o = try { JSONObject(Handoff.unquote(raw) ?: "{}") } catch (_: Exception) { JSONObject() }
            val t = o.optString("t")
            if (o.optInt("n", 0) > 0 && t != lastDismissTxt) {
                lastDismissTxt = t
                Handoff.log(a, projId, "Correção automática: fechei um aviso do Claude: ${t.ifBlank { "janela" }}")
            }
        }
        val now = System.currentTimeMillis()
        if ((phase == Phase.SEND || phase == Phase.WAIT) && now - lastLimitChk > 6000L) {
            lastLimitChk = now
            a.scrSnap(tab) { snap, limited ->
                if (alive(g) && snap != null && snap.limit && limited) {
                    giveUp("a conta do Claude está em limite (o erro ficou copiado; cole no chat de outra conta)")
                }
            }
        }
    }

    // ───────────── mandar a mensagem (com o arquivo do erro) ─────────────

    private fun send(g: Int, tab: Tab, text: String, file: File?, asks: Int) {
        if (!alive(g)) return
        phase = Phase.SEND
        val w = a.scrWebOpen(tab)

        fun confirm(n: Int) {
            if (!alive(g)) return
            h.postDelayed({
                if (!alive(g)) return@postDelayed
                w.evaluateJavascript(ScriptJs.STATE) { raw ->
                    if (!alive(g)) return@evaluateJavascript
                    val o = try { JSONObject(Handoff.unquote(raw) ?: "{}") } catch (_: Exception) { JSONObject() }
                    if (o.optInt("u", -1) > baseU || o.optBoolean("s", false)) {
                        sentAt = System.currentTimeMillis()
                        waitDone(g, tab, asks, 0, -1, 0)
                    } else if (n < 10) confirm(n + 1)
                    else trySend(g, w, ::confirm)
                }
            }, 1000)
        }

        fun fill() {
            if (!alive(g)) return
            w.evaluateJavascript(ScriptJs.FILL.replace("__TEXT__", JSONObject.quote(text))) { r ->
                if (!alive(g)) return@evaluateJavascript
                if (r?.contains("ok") != true) { h.postDelayed({ fill() }, 800); return@evaluateJavascript }
                h.postDelayed({
                    if (!alive(g)) return@postDelayed
                    w.evaluateJavascript(ScriptJs.STATE) { raw ->
                        baseU = try { JSONObject(Handoff.unquote(raw) ?: "{}").optInt("u", 0).coerceAtLeast(0) } catch (_: Exception) { 0 }
                        trySend(g, w, ::confirm)
                    }
                }, 300)
            }
        }

        fun verify(n: Int, st: Int) {
            if (!alive(g) || file == null) return
            a.scrAttachedOk(w, listOf(file.name)) { good ->
                if (!alive(g)) return@scrAttachedOk
                val s = if (good) st + 1 else 0
                if (s >= 2) { fill(); return@scrAttachedOk }
                if (n > 0 && n % 40 == 0) {
                    a.scrAttachedOk(w, listOf(file.name), false) { has ->
                        if (!alive(g)) return@scrAttachedOk
                        if (has) verify(n + 1, 0)
                        else a.scrAttach(tab, file) { _ -> h.postDelayed({ verify(n + 1, 0) }, 500) }
                    }
                    return@scrAttachedOk
                }
                h.postDelayed({ verify(n + 1, s) }, 500)
            }
        }

        fun attach() {
            if (!alive(g) || file == null) return
            a.scrAttach(tab, file) { done ->
                if (!alive(g)) return@scrAttach
                if (!done) h.postDelayed({ attach() }, 800) else verify(0, 0)
            }
        }

        a.scrWaitReady(w, null, { alive(g) }) { _ ->
            if (alive(g)) { if (file != null) attach() else fill() }
        }
    }

    private fun trySend(g: Int, w: android.webkit.WebView, confirm: (Int) -> Unit) {
        if (!alive(g)) return
        w.evaluateJavascript(ScriptJs.SEND) { raw ->
            if (!alive(g)) return@evaluateJavascript
            if (raw?.contains("sent") == true) confirm(0)
            else h.postDelayed({ trySend(g, w, confirm) }, 500)
        }
    }

    // ───────────── esperar a resposta e baixar o ZIP ─────────────

    private fun waitDone(g: Int, tab: Tab, asks: Int, stable: Int, lastN: Int, tick: Int) {
        phase = Phase.WAIT
        h.postDelayed({
            if (!alive(g)) return@postDelayed
            val w = a.scrWeb(tab)
            if (w == null) { waitDone(g, tab, asks, stable, lastN, tick + 1); return@postDelayed }
            if (System.currentTimeMillis() - sentAt > 50 * 60_000L) { giveUp("o Claude demorou demais para responder"); return@postDelayed }
            w.evaluateJavascript(ScriptJs.STATE) { raw ->
                if (!alive(g)) return@evaluateJavascript
                val o = try { JSONObject(Handoff.unquote(raw) ?: "{}") } catch (_: Exception) { JSONObject() }
                val u = o.optInt("u", -1)
                val s = o.optBoolean("s", false)
                val n = o.optInt("n", 0)
                if (u < 0) { waitDone(g, tab, asks, stable, lastN, tick + 1); return@evaluateJavascript }
                val st = if (s) 0 else if (n == lastN) stable + 1 else 0
                val waited = System.currentTimeMillis() - sentAt
                if (!s && st >= 4 && waited >= 8000L && u >= baseU + 1) getZip(g, tab, asks, System.currentTimeMillis(), 0)
                else waitDone(g, tab, asks, st, n, tick + 1)
            }
        }, 3000)
    }

    private fun getZip(g: Int, tab: Tab, asks: Int, start: Long, none: Int) {
        phase = Phase.ZIP
        h.postDelayed({
            if (!alive(g)) return@postDelayed
            if (zipArrived) { done(); return@postDelayed }
            val w = a.scrWeb(tab)
            if (w == null) { getZip(g, tab, asks, start, none); return@postDelayed }
            val elapsed = System.currentTimeMillis() - start
            if (elapsed > 45_000L) { noZip(g, tab, asks); return@postDelayed }
            w.evaluateJavascript(ScriptJs.CLICK_DL) { raw ->
                if (!alive(g)) return@evaluateJavascript
                val o = try { JSONObject(Handoff.unquote(raw) ?: "{}") } catch (_: Exception) { JSONObject() }
                val nn = when (o.optString("a")) { "dl", "open" -> 0; else -> none + 1 }
                if (zipArrived) { done(); return@evaluateJavascript }
                if (nn >= 4 && elapsed > 10_000L) noZip(g, tab, asks) else getZip(g, tab, asks, start, nn)
            }
        }, 2500)
    }

    private fun noZip(g: Int, tab: Tab, asks: Int) {
        if (!alive(g)) return
        if (asks < 2) {
            Handoff.log(a, projId, "Correção automática: sem ZIP, pedindo de novo (${asks + 1}/2)")
            send(g, tab, "Não consegui baixar o ZIP corrigido. Entregue o ZIP com os arquivos corrigidos como arquivo para download.", null, asks + 1)
        } else {
            giveUp("o Claude não entregou o ZIP corrigido")
        }
    }

    /** ZIP corrigido baixado: o app já o entrega ao GitHub (maybeDeliverZip). Se o build falhar de novo, recomeça. */
    private fun done() {
        phase = Phase.IDLE
        Handoff.log(a, projId, "Correção automática: ZIP corrigido recebido; entregando ao GitHub")
    }
}
