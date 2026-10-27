package com.vinicius.multiabas

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Executa a lista de tarefas sozinho: manda uma tarefa por vez no Claude, espera a resposta terminar,
 * baixa o ZIP entregue (sem mandar ao GitHub) e segue. Se o limite acabar, leva tudo para outra conta.
 */
class ScriptRunner(private val a: MainActivity) {
    val ui = ScriptUi(a, this)

    private enum class Phase { IDLE, SENDING, WAIT_DONE, GET_ZIP, LIMIT_WAIT, HANDOFF }

    private val h get() = a.scrHandler()
    private var phase = Phase.IDLE
    private var gen = 0

    // estado da tarefa atual (só em memória)
    private var chatStarted = false
    private var taskSent = false
    private var baseU = 0
    private var sentAt = 0L
    private var lastN = -1
    private var stable = 0
    private var askCount = 0
    private var zipGot = false
    private var dlStart = 0L
    private var noneCount = 0
    private var lastSnapAt = 0L
    private var lastUsageAt = 0L
    private val warned = HashSet<String>()
    private var limitBusy = false
    private var wake: PowerManager.WakeLock? = null

    private val total get() = Script.items.size

    /** Tarefas maiores que isso vão como arquivo .txt anexado, com uma mensagem curta no lugar. */
    private val BIG = 8000

    private fun bigFile(n: Int, text: String): File? {
        if (text.length <= BIG) return null
        return try {
            val dir = File(a.cacheDir, "script_txt").apply { mkdirs() }
            File(dir, "tarefa-$n.txt").also { it.writeText(text) }
        } catch (_: Exception) { null }
    }

    private fun taskRef(text: String, f: File?): String =
        if (f == null) text
        else "(Tarefa grande: o texto completo está no arquivo anexo ${f.name}. Leia o arquivo inteiro e faça exatamente o que está nele.)"

    // ───────────── controles ─────────────

    fun start() {
        if (Script.status == Script.RUNNING) return
        if (Script.status == Script.PAUSED) { resume(); return }
        if (Script.items.isEmpty()) { a.scrSay("Adicione pelo menos uma tarefa", true); return }
        if (Projects.current(a) == null || Projects.token(a).isBlank()) {
            a.scrSay("Defina o Projeto atual e o Token do GitHub antes de rodar o script", true)
            return
        }
        val tab = a.scrCurrentTab()
        val w = tab?.let { a.scrWeb(it) }
        if (tab == null || w == null || !(w.url ?: "").contains("claude.ai")) {
            a.scrSay("Abra o Claude na aba que vai rodar o script e tente de novo", true)
            return
        }
        Script.clearZips(a)
        Script.items.forEach { it.state = Script.PEND; it.zips = 0 }
        Script.idx = 0
        Script.chatTabId = tab.id
        Script.reason = ""
        Script.resultShown = true
        Script.status = Script.RUNNING
        Script.save(a)
        Script.clearEvents(a)
        Script.log(a, "Script iniciado: $total tarefa(s)")
        chatStarted = false
        taskSent = false
        limitBusy = false
        gen++
        acquireWake()
        KeepAliveService.start(a)
        Handoff.log(a, Projects.current(a)!!.id, "Script iniciado: $total tarefa(s)")
        beginTask()
    }

    fun pause() {
        if (Script.status != Script.RUNNING) return
        gen++
        Script.status = Script.PAUSED
        Script.reason = "Pausado por você"
        Script.log(a, "Pausado por você")
        Script.save(a)
        releaseWake()
        phase = Phase.IDLE
        notify("Script pausado", "Tarefa ${Script.idx + 1} de $total")
    }

    fun resume() {
        if (Script.status != Script.PAUSED) return
        Script.status = Script.RUNNING
        Script.reason = ""
        Script.save(a)
        Script.log(a, "Continuando a tarefa ${Script.idx + 1}")
        gen++
        limitBusy = false
        acquireWake()
        KeepAliveService.start(a)
        if (taskSent) {
            sentAt = System.currentTimeMillis()
            enterWait(false)
        } else {
            beginTask()
        }
    }

    fun stop() {
        gen++
        phase = Phase.IDLE
        limitBusy = false
        Script.items.forEach { if (it.state == Script.RUN) it.state = Script.PEND }
        Script.status = if (Script.zips.isNotEmpty()) Script.DONE else Script.IDLE
        Script.resultShown = true
        Script.reason = ""
        Script.save(a)
        Script.log(a, "Script parado por você")
        releaseWake()
        cancelNotify()
    }

    /** O app foi reaberto com o script rodando: volta a acompanhar o chat que já estava aberto. */
    fun resumeAfterRestart() {
        val tab = a.scrTab(Script.chatTabId)
        if (tab == null) {
            Script.status = Script.PAUSED
            Script.reason = "A aba do script não existe mais"
            Script.save(a)
            return
        }
        gen++
        val g = gen
        limitBusy = false
        chatStarted = true
        taskSent = true
        acquireWake()
        status("Retomando depois de reabrir o app…")
        try { a.scrWebOpen(tab) } catch (_: Exception) { }
        h.postDelayed({
            if (g != gen || Script.status != Script.RUNNING) return@postDelayed
            baseU = 0
            sentAt = System.currentTimeMillis() - 30_000L
            enterWait(false)
        }, 8000)
    }

    // ───────────── início de cada tarefa ─────────────

    private fun beginTask() {
        if (Script.idx !in Script.items.indices) { finish(); return }
        val item = Script.items[Script.idx]
        item.state = Script.RUN
        Script.log(a, "Tarefa ${Script.idx + 1} de $total iniciada: ${item.text.replace(Regex("\\s+"), " ").trim().take(60)}")
        askCount = 0
        zipGot = false
        taskSent = false
        Script.save(a)
        val g = gen
        val big = bigFile(Script.idx + 1, item.text)
        val ref = taskRef(item.text, big)
        if (big != null) Script.log(a, "Tarefa ${Script.idx + 1} é grande (${item.text.length} caracteres): vai como arquivo ${big.name}")
        if (!chatStarted) {
            status("Baixando o código atual do projeto…")
            Thread {
                val f = try { packCode { s -> status(s) } } catch (_: Exception) { null }
                a.runOnUiThread {
                    if (g != gen || Script.status != Script.RUNNING) return@runOnUiThread
                    if (f == null) { failPause("Não consegui baixar o código do projeto no GitHub"); return@runOnUiThread }
                    sendMessage(Script.firstMessage(ref, total), true, listOfNotNull(f, big))
                }
            }.start()
        } else {
            sendMessage(Script.nextMessage(Script.idx + 1, total, ref), false, listOfNotNull(big))
        }
    }

    /** Escreve e envia uma mensagem no chat da aba do script (abrindo chat novo e anexando ZIP se pedido). */
    private fun sendMessage(text: String, newChat: Boolean, attach: List<File>) {
        val tab = a.scrTab(Script.chatTabId)
        if (tab == null) { failPause("A aba do script não existe mais"); return }
        val g = gen
        phase = Phase.SENDING
        taskSent = false
        fun ok() = g == gen && Script.status == Script.RUNNING
        fun afterOpen() {
            if (!ok()) return
            val w = a.scrWeb(tab)
            if (w == null) { failPause("A aba do Claude não está aberta"); return }
            fun fill() {
                if (!ok()) return
                status("Escrevendo a tarefa ${Script.idx + 1} de $total…")
                w.evaluateJavascript(ScriptJs.FILL.replace("__TEXT__", JSONObject.quote(text))) { r ->
                    if (!ok()) return@evaluateJavascript
                    if (r?.contains("ok") != true) { failPause("Não achei a caixa de mensagem do Claude"); return@evaluateJavascript }
                    w.postDelayed({ readBase(w) { trySend(w, 0, g) } }, 1500)
                }
            }
            fun attachNext(i: Int) {
                if (!ok()) return
                if (i >= attach.size) { fill(); return }
                status("Anexando ${attach[i].name}…")
                a.scrAttach(tab, attach[i]) { done ->
                    if (!ok()) return@scrAttach
                    if (!done) failPause("Não consegui anexar ${attach[i].name} no Claude") else attachNext(i + 1)
                }
            }
            attachNext(0)
        }
        if (newChat) {
            status("Abrindo um chat novo…")
            a.scrOpenNew(tab) { ready ->
                if (!ok()) return@scrOpenNew
                if (!ready) failPause("O Claude não abriu o chat (a conta está logada?)") else afterOpen()
            }
        } else {
            a.scrWaitComposer(a.scrWebOpen(tab), 20) { ready ->
                if (!ok()) return@scrWaitComposer
                if (!ready) failPause("A caixa de mensagem do Claude não apareceu") else afterOpen()
            }
        }
    }

    private fun readBase(w: android.webkit.WebView, then: () -> Unit) {
        w.evaluateJavascript(ScriptJs.STATE) { raw ->
            baseU = try { JSONObject(Handoff.unquote(raw) ?: "{}").optInt("u", 0).coerceAtLeast(0) } catch (_: Exception) { 0 }
            then()
        }
    }

    private fun trySend(w: android.webkit.WebView, n: Int, g: Int) {
        if (g != gen || Script.status != Script.RUNNING) return
        w.evaluateJavascript(ScriptJs.SEND) { raw ->
            if (g != gen || Script.status != Script.RUNNING) return@evaluateJavascript
            if (raw?.contains("sent") == true) {
                confirmSent(w, 0, g)
            } else if (n < 14) {
                w.postDelayed({ trySend(w, n + 1, g) }, 1500)
            } else {
                failPause("Não consegui apertar o botão de enviar do Claude")
            }
        }
    }

    private fun confirmSent(w: android.webkit.WebView, n: Int, g: Int) {
        w.postDelayed({
            if (g != gen || Script.status != Script.RUNNING) return@postDelayed
            w.evaluateJavascript(ScriptJs.STATE) { raw ->
                if (g != gen || Script.status != Script.RUNNING) return@evaluateJavascript
                val o = try { JSONObject(Handoff.unquote(raw) ?: "{}") } catch (_: Exception) { JSONObject() }
                val u = o.optInt("u", -1)
                val s = o.optBoolean("s", false)
                if (u > baseU || s) {
                    chatStarted = true
                    taskSent = true
                    sentAt = System.currentTimeMillis()
                    Script.log(a, "Mensagem enviada ao Claude (tarefa ${Script.idx + 1})")
                    enterWait(true)
                } else if (n < 12) {
                    confirmSent(w, n + 1, g)
                } else {
                    // a mensagem não saiu: tenta apertar enviar de novo
                    trySend(w, 8, g)
                }
            }
        }, 1200)
    }

    // ───────────── esperar a resposta terminar ─────────────

    private fun enterWait(fresh: Boolean) {
        phase = Phase.WAIT_DONE
        lastN = -1
        stable = 0
        lastSnapAt = System.currentTimeMillis()
        if (fresh) status("Claude trabalhando na tarefa ${Script.idx + 1} de $total…")
        else status("Acompanhando a tarefa ${Script.idx + 1} de $total…")
        poll(gen)
    }

    private fun poll(g: Int) {
        h.postDelayed({ pollNow(g) }, 3000)
    }

    private fun pollNow(g: Int) {
        if (g != gen || Script.status != Script.RUNNING || phase != Phase.WAIT_DONE) return
        val tab = a.scrTab(Script.chatTabId)
        val w = tab?.let { a.scrWeb(it) }
        if (tab == null || w == null) { poll(g); return }
        val now = System.currentTimeMillis()
        // uso das contas: relê a cada 4 min e avisa no relatório quando passa de 90%
        if (now - lastUsageAt > 240_000L) {
            lastUsageAt = now
            a.usageMon.refreshAll {
                a.scrTabs().forEach { t ->
                    val i = Usage.map[t.id]
                    if (i != null && i.s >= 90 && warned.add(t.id)) Script.log(a, "Conta \"${t.name}\" em ${i.s}% da sessão")
                    if (i != null && i.s in 0..60) warned.remove(t.id)
                }
            }
        }
        // limite?
        if (now - lastSnapAt > 20_000L) {
            lastSnapAt = now
            a.scrSnap(tab) { snap, active ->
                if (g == gen && snap != null && snap.limit && active) onLimit()
            }
        }
        if (now - sentAt > 50 * 60_000L) {
            Script.items[Script.idx].state = Script.FAIL
            Script.save(a)
            Script.log(a, "Tarefa ${Script.idx + 1} passou de 50 min: marcada como falha ✗")
            status("Tarefa ${Script.idx + 1} passou de 50 min: marcada como falha")
            nextTask()
            return
        }
        w.evaluateJavascript(ScriptJs.STATE) { raw ->
            if (g != gen || Script.status != Script.RUNNING || phase != Phase.WAIT_DONE) return@evaluateJavascript
            val o = try { JSONObject(Handoff.unquote(raw) ?: "{}") } catch (_: Exception) { JSONObject() }
            val u = o.optInt("u", -1)
            val s = o.optBoolean("s", false)
            val n = o.optInt("n", 0)
            if (u < 0) { poll(g); return@evaluateJavascript }
            if (s) {
                stable = 0
                lastN = n
            } else {
                if (n == lastN) stable++ else { stable = 0; lastN = n }
            }
            val waited = System.currentTimeMillis() - sentAt
            if (!s && stable >= 4 && waited >= 8000L && u >= baseU + 1) {
                enterGetZip(g)
            } else {
                poll(g)
            }
        }
    }

    // ───────────── baixar o ZIP ─────────────

    private fun enterGetZip(g: Int) {
        phase = Phase.GET_ZIP
        Script.log(a, "Claude terminou a resposta da tarefa ${Script.idx + 1}; buscando o ZIP")
        dlStart = System.currentTimeMillis()
        noneCount = 0
        status("Resposta pronta. Procurando o ZIP da tarefa ${Script.idx + 1}…")
        h.postDelayed({ getZipNow(g) }, 1200)
    }

    private fun getZipNow(g: Int) {
        if (g != gen || Script.status != Script.RUNNING || phase != Phase.GET_ZIP) return
        if (zipGot) { taskDone(Script.OK); return }
        val tab = a.scrTab(Script.chatTabId)
        val w = tab?.let { a.scrWeb(it) }
        if (w == null) { h.postDelayed({ getZipNow(g) }, 2500); return }
        val elapsed = System.currentTimeMillis() - dlStart
        if (elapsed > 45_000L) { noZip(g); return }
        w.evaluateJavascript(ScriptJs.CLICK_DL) { raw ->
            if (g != gen || Script.status != Script.RUNNING || phase != Phase.GET_ZIP) return@evaluateJavascript
            val o = try { JSONObject(Handoff.unquote(raw) ?: "{}") } catch (_: Exception) { JSONObject() }
            when (o.optString("a")) {
                "dl" -> { noneCount = 0; status("Baixando ${o.optString("n")}…") }
                "open" -> { noneCount = 0 }
                else -> noneCount++
            }
            if (zipGot) { taskDone(Script.OK); return@evaluateJavascript }
            if (noneCount >= 4 && elapsed > 10_000L) noZip(g)
            else h.postDelayed({ getZipNow(g) }, 2500)
        }
    }

    private fun noZip(g: Int) {
        if (g != gen) return
        if (zipGot) { taskDone(Script.OK); return }
        if (askCount < 2) {
            askCount++
            Script.log(a, "Sem ZIP na tarefa ${Script.idx + 1}: pedindo de novo ($askCount/2)")
            status("Claude não entregou o ZIP: pedindo de novo ($askCount/2)…")
            sendMessage(Script.askZip(Script.idx + 1), false, emptyList())
        } else {
            Script.items[Script.idx].state = Script.FAIL
            Script.save(a)
            Script.log(a, "Sem ZIP na tarefa ${Script.idx + 1}: marcada como falha ✗")
            status("Sem ZIP na tarefa ${Script.idx + 1}: marcada como falha")
            nextTask()
        }
    }

    /** Chamado pelo app quando um ZIP é baixado. Devolve true se o script ficou com ele. */
    fun onZip(name: String, bytes: ByteArray): Boolean {
        if (Script.status != Script.RUNNING) return false
        val item = Script.idx
        val z = Script.addZip(a, item, name, bytes)
        a.runOnUiThread {
            if (z != null) {
                Script.items.getOrNull(item)?.let { it.zips++ }
                Script.save(a)
                Script.log(a, "ZIP recebido na tarefa ${item + 1}: $name (${bytes.size / 1024} KB)")
                status("ZIP recebido: $name")
            }
            zipGot = true
            a.scrSay("Script: ZIP guardado (${Script.zips.size})")
        }
        return true
    }

    private fun taskDone(state: Int) {
        Script.items.getOrNull(Script.idx)?.state = state
        Script.save(a)
        Script.log(a, "Tarefa ${Script.idx + 1} concluída ✓")
        nextTask()
    }

    private fun nextTask() {
        val next = (Script.idx + 1 until Script.items.size).firstOrNull { Script.items[it].state == Script.PEND }
        if (next == null) { finish(); return }
        Script.idx = next
        Script.save(a)
        beginTask()
    }

    private fun finish() {
        gen++
        phase = Phase.IDLE
        Script.status = Script.DONE
        Script.resultShown = false
        Script.reason = ""
        Script.save(a)
        releaseWake()
        val ok = Script.items.count { it.state == Script.OK }
        val fail = Script.items.count { it.state == Script.FAIL }
        Script.log(a, "Script terminou: $ok ok, $fail falha(s), ${Script.zips.size} ZIP(s)")
        Projects.current(a)?.let { Handoff.log(a, it.id, "Script terminou: $ok ok, $fail falha(s), ${Script.zips.size} ZIP(s)") }
        notify("Script concluído", "$ok de $total tarefa(s) ok · ${Script.zips.size} ZIP(s) para enviar")
        a.scrShowPending()
    }

    // ───────────── limite acabou ─────────────

    private fun onLimit() {
        if (limitBusy || Script.status != Script.RUNNING) return
        limitBusy = true
        gen++
        val g = gen
        phase = Phase.LIMIT_WAIT
        Script.log(a, "Limite atingido; procurando outra conta liberada")
        status("Limite atingido. Procurando outra conta liberada…")
        limitLoop(g)
    }

    private fun limitLoop(g: Int) {
        if (g != gen || Script.status != Script.RUNNING) return
        val from = a.scrTab(Script.chatTabId)
        if (from == null) { failPause("A aba do script não existe mais"); return }
        val now = System.currentTimeMillis()
        val tabs = a.scrTabs()
        val free = tabs.filter { it.id != from.id && it.until <= now }.minByOrNull { it.until }
        if (free != null) { doHandoff(from, free, g); return }
        if (from.until <= now) {
            // a mesma conta voltou: confere e segue no mesmo chat
            a.scrSnap(from) { snap, active ->
                if (g != gen || Script.status != Script.RUNNING) return@scrSnap
                if (snap != null && snap.limit && active) {
                    h.postDelayed({ limitLoop(g) }, 20_000)
                } else {
                    resumeSameChat(from, g)
                }
            }
            return
        }
        val first = tabs.minByOrNull { if (it.until > now) it.until else Long.MAX_VALUE }
        val until = first?.until ?: 0L
        val txt = if (until > now) "Todas as contas em limite. Volta ${a.scrReturnLabel(until)}" else "Aguardando o limite liberar…"
        status(txt)
        notify("Script esperando o limite", txt)
        h.postDelayed({ limitLoop(g) }, 20_000)
    }

    private fun resumeSameChat(tab: Tab, g: Int) {
        Script.log(a, "Limite liberou; continuando no mesmo chat")
        status("Limite liberou. Continuando no mesmo chat…")
        val w = a.scrWeb(tab)
        tab.holdUntil = System.currentTimeMillis() + 15_000L
        w?.reload()
        limitBusy = false
        h.postDelayed({
            if (g != gen || Script.status != Script.RUNNING) return@postDelayed
            sendMessage(Script.CONTINUE_MSG, false, emptyList())
        }, 5000)
    }

    private fun doHandoff(from: Tab, to: Tab, g: Int) {
        phase = Phase.HANDOFF
        Script.log(a, "Levando o chat e o código para a conta \"${to.name}\"")
        val proj = Projects.current(a)
        val token = Projects.token(a)
        status("Salvando o chat completo de \"${from.name}\"…")
        a.scrCaptureFull(a.scrWeb(from), { s -> status(s) }) { dir, unfinished, _ ->
            if (g != gen || Script.status != Script.RUNNING) return@scrCaptureFull
            Thread {
                val f = try {
                    Handoff.build(a, from.name, from.id, proj, token, unfinished, dir, "continuar", Script.overlay(a)) { s -> status(s) }
                } catch (_: Exception) { null }
                a.runOnUiThread {
                    if (g != gen || Script.status != Script.RUNNING) return@runOnUiThread
                    if (f == null) { failPause("Não consegui montar o pacote para a outra conta"); return@runOnUiThread }
                    try { a.scrSave(f.readBytes(), f.name) } catch (_: Exception) { }
                    openTarget(to, f)
                }
            }.start()
        }
    }

    private fun openTarget(to: Tab, f: File) {
        Script.chatTabId = to.id
        Script.save(a)
        limitBusy = false
        a.scrSelect(to)
        val item = Script.items[Script.idx]
        val big = bigFile(Script.idx + 1, item.text)
        val msg = Handoff.STARTER + "\n\n" + Script.handoffNote(Script.idx + 1, total, taskRef(item.text, big))
        chatStarted = true
        status("Continuando na conta \"${to.name}\"…")
        gen++
        sendMessage(msg, true, listOfNotNull(f, big))
    }

    // ───────────── utilidades ─────────────

    private fun failPause(msg: String) {
        gen++
        phase = Phase.IDLE
        limitBusy = false
        Script.status = Script.PAUSED
        Script.reason = msg
        Script.save(a)
        Script.log(a, "Parou: $msg")
        releaseWake()
        status(msg)
        notify("Script parou", msg)
        a.scrSay("Script parou: $msg", true)
    }

    private fun status(s: String) {
        Script.ui = s
        notify("Script: tarefa ${(Script.idx + 1).coerceAtMost(total.coerceAtLeast(1))} de $total", s)
    }

    private fun skipPath(n: String, folder: String): Boolean =
        n.startsWith(".git/") || n.startsWith("build/") || n.startsWith("app/build/") ||
            n.startsWith("$folder/") || n.startsWith("__MACOSX/")

    /** Monta o ZIP "codigo/…" com o repositório atual + os arquivos das tarefas já feitas. */
    private fun packCode(onStatus: (String) -> Unit): File? {
        val proj = Projects.current(a) ?: return null
        val token = Projects.token(a)
        if (token.isBlank()) return null
        val branch = proj.branch.ifBlank { "main" }
        val rz = GitHubDelivery.downloadRepoZip(a, token, proj, branch) ?: return null
        if (!rz.exists()) return null
        val overlay = Script.overlay(a)
        val dir = File(a.cacheDir, "script_code").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "codigo-${proj.repo}.zip")
        onStatus("Empacotando o código…")
        ZipOutputStream(out.outputStream().buffered()).use { z ->
            ZipInputStream(rz.inputStream().buffered()).use { zi ->
                var e = zi.nextEntry
                while (e != null) {
                    val n = e.name.substringAfter('/', "")
                    if (n.isNotEmpty() && !e.isDirectory && !skipPath(n, proj.folder) && !overlay.containsKey(n)) {
                        z.putNextEntry(ZipEntry("codigo/$n"))
                        zi.copyTo(z)
                        z.closeEntry()
                    }
                    e = zi.nextEntry
                }
            }
            overlay.forEach { (n, b) ->
                z.putNextEntry(ZipEntry("codigo/$n"))
                z.write(b)
                z.closeEntry()
            }
        }
        return out
    }

    // ───────────── notificação e wake lock ─────────────

    private val chId = "script"
    private val notifId = 43

    private fun notify(title: String, text: String) {
        try {
            val nm = a.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(NotificationChannel(chId, "Script de tarefas", NotificationManager.IMPORTANCE_LOW))
            }
            val open = PendingIntent.getActivity(
                a, 2,
                Intent(a, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val running = Script.status == Script.RUNNING
            val n = NotificationCompat.Builder(a, chId)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(open)
                .setOngoing(running)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            nm.notify(notifId, n)
        } catch (_: Exception) { }
    }

    private fun cancelNotify() {
        try { (a.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(notifId) } catch (_: Exception) { }
    }

    private fun acquireWake() {
        try {
            if (wake?.isHeld == true) return
            val pm = a.getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "multiabas:script").apply {
                setReferenceCounted(false)
                acquire(8 * 60 * 60 * 1000L)
            }
        } catch (_: Exception) { }
    }

    private fun releaseWake() {
        try { if (wake?.isHeld == true) wake?.release() } catch (_: Exception) { }
        wake = null
    }
}
