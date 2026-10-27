package com.vinicius.multiabas

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import android.view.Gravity
import android.widget.EditText
import android.widget.FrameLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale

/** Telas do Script de tarefas: lista, controles e resultado final. */
class ScriptUi(private val a: MainActivity, private val runner: ScriptRunner) {

    private fun snippet(t: String): String {
        val one = t.replace(Regex("\\s+"), " ").trim()
        return if (one.length > 70) one.take(70) + "…" else one
    }

    private fun stateLabel(s: Int) = when (s) {
        Script.RUN -> "Em andamento"
        Script.OK -> "Concluída ✓"
        Script.FAIL -> "Falhou ✗ (sem ZIP)"
        else -> "Na fila"
    }

    private fun kb(n: Long) = String.format(Locale.US, "%.0f KB", n / 1024.0)

    // ───────────── painel principal ─────────────

    fun showPanel() {
        val running = Script.status == Script.RUNNING
        val paused = Script.status == Script.PAUSED
        val sh = Sheet(a).title("Script de tarefas", "O app manda uma tarefa por vez no Claude e guarda os ZIPs")

        if (running) {
            sh.section("Andamento")
            sh.row(R.drawable.ic_timer, "Tarefa ${Script.idx + 1} de ${Script.items.size}", Script.ui.ifBlank { "Trabalhando…" }, highlighted = true) { showPanel() }
            sh.row(R.drawable.ic_close, "Pausar", "Para depois da etapa atual; dá para continuar") { runner.pause(); showPanel() }
            sh.row(R.drawable.ic_delete, "Parar", "Encerra o script e mantém os ZIPs já baixados", danger = true) {
                a.scrConfirm("Parar o script?", "Os ZIPs já baixados ficam guardados.", "Parar") { runner.stop() }
            }
        } else if (paused) {
            sh.section("Pausado")
            sh.row(R.drawable.ic_play, "Continuar", Script.reason.ifBlank { "Tarefa ${Script.idx + 1} de ${Script.items.size}" }, highlighted = true) {
                runner.resume(); showPanel()
            }
            sh.row(R.drawable.ic_delete, "Parar", "Encerra o script e mantém os ZIPs já baixados", danger = true) {
                a.scrConfirm("Parar o script?", "Os ZIPs já baixados ficam guardados.", "Parar") { runner.stop() }
            }
        } else if (Script.items.isNotEmpty()) {
            sh.section("Rodar")
            sh.row(R.drawable.ic_play, "Play", "Roda as ${Script.items.size} tarefa(s) na aba atual", highlighted = true) {
                runner.start()
            }
        }

        if (Script.items.isNotEmpty() || Script.events.isNotEmpty()) {
            sh.section("Acompanhar")
            sh.row(R.drawable.ic_timer, "Uso das contas (0–100%)", "Quanto cada conta já usou, ao vivo") { a.usageMon.ui.show() }
            sh.row(R.drawable.ic_info, "Relatório ao vivo", "O que já foi feito, ZIPs e linha do tempo", highlighted = running) { showReport() }
        }

        sh.section("Tarefas (${Script.items.size})")
        if (Script.items.isEmpty()) sh.note("Nenhuma tarefa ainda. Adicione o que você quer pedir ao Claude, na ordem.")
        Script.items.forEachIndexed { i, item ->
            sh.row(
                if (item.state == Script.OK) R.drawable.ic_check else R.drawable.ic_edit,
                "${i + 1}. ${snippet(item.text)}",
                stateLabel(item.state) + if (item.zips > 0) " · ${item.zips} ZIP" else "",
                highlighted = running && i == Script.idx
            ) { if (running) showPanel() else taskOptions(i) }
        }

        if (!running) {
            sh.row(R.drawable.ic_add, "Adicionar tarefa", "Aceita texto grande: acima de 8 mil caracteres vai como arquivo .txt") { editDialog("Nova tarefa", "") { t -> addTasks(listOf(t)) } }
            sh.row(R.drawable.ic_add, "Adicionar várias de uma vez", "Separe com uma linha contendo só ---") {
                editDialog("Várias tarefas", "", true) { t ->
                    val parts = t.split(Regex("(?m)^\\s*---+\\s*$")).map { it.trim() }.filter { it.isNotEmpty() }
                    addTasks(parts)
                }
            }
        }

        if (Script.zips.isNotEmpty()) {
            sh.section("ZIPs")
            sh.row(R.drawable.ic_download, "Ver ZIPs guardados (${Script.zips.size})", "Enviar ao GitHub ou salvar em Downloads") { showResults() }
        }
        if (!running && (Script.items.isNotEmpty() || Script.zips.isNotEmpty())) {
            sh.row(R.drawable.ic_delete, "Limpar lista e ZIPs", null, danger = true) {
                a.scrConfirm("Limpar tudo?", "Apaga a lista de tarefas e os ZIPs guardados do script.", "Limpar") {
                    Script.items.clear()
                    Script.clearZips(a)
                    Script.clearEvents(a)
                    Script.status = Script.IDLE
                    Script.idx = 0
                    Script.resultShown = true
                    Script.save(a)
                    showPanel()
                }
            }
        }
        sh.show()
    }

    private fun addTasks(list: List<String>) {
        val clean = list.map { it.trim() }.filter { it.isNotEmpty() }
        if (clean.isEmpty()) { showPanel(); return }
        if (Script.status == Script.DONE) {
            Script.status = Script.IDLE
            Script.idx = 0
        }
        clean.forEach { Script.items.add(ScriptItem(java.util.UUID.randomUUID().toString(), it)) }
        Script.save(a)
        showPanel()
    }

    private fun taskOptions(i: Int) {
        val labels = arrayOf("Editar", "Subir", "Descer", "Excluir")
        MaterialAlertDialogBuilder(a)
            .setTitle("Tarefa ${i + 1}")
            .setItems(labels) { _, which ->
                when (labels[which]) {
                    "Editar" -> editDialog("Editar tarefa", Script.items[i].text) { t ->
                        if (t.isNotBlank()) { Script.items[i].text = t.trim(); Script.save(a) }
                        showPanel()
                    }
                    "Subir" -> { if (i > 0) { val x = Script.items.removeAt(i); Script.items.add(i - 1, x); Script.save(a) }; showPanel() }
                    "Descer" -> { if (i < Script.items.size - 1) { val x = Script.items.removeAt(i); Script.items.add(i + 1, x); Script.save(a) }; showPanel() }
                    "Excluir" -> { Script.items.removeAt(i); Script.save(a); showPanel() }
                    else -> { }
                }
            }
            .setNegativeButton("Fechar") { _, _ -> showPanel() }
            .show()
    }

    private fun editDialog(title: String, initial: String, big: Boolean = false, onOk: (String) -> Unit) {
        val input = EditText(a).apply {
            hint = "Escreva aqui"
            setText(initial)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = if (big) 8 else 4
            maxLines = 14
            gravity = Gravity.TOP or Gravity.START
            isVerticalScrollBarEnabled = true
        }
        val pad = (20 * a.resources.displayMetrics.density).toInt()
        val box = FrameLayout(a).apply {
            setPadding(pad, 8, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(a)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("Salvar") { _, _ -> onOk(input.text?.toString().orEmpty()) }
            .setNegativeButton("Cancelar") { _, _ -> showPanel() }
            .show()
    }

    // ───────────── resultado: ZIPs para enviar ─────────────

    fun showResults() {
        Script.resultShown = true
        Script.save(a)
        val ok = Script.items.count { it.state == Script.OK }
        val fail = Script.items.count { it.state == Script.FAIL }
        val title = if (Script.status == Script.DONE) "Script concluído" else "ZIPs do script"
        val sub = "$ok tarefa(s) ok" + (if (fail > 0) " · $fail sem ZIP" else "") + " · ${Script.zips.size} ZIP(s)"
        val sh = Sheet(a).title(title, sub)
        if (Script.zips.isEmpty()) {
            sh.note("Nenhum ZIP foi guardado.")
        } else {
            sh.section("ZIPs na ordem")
            Script.zips.forEachIndexed { i, z ->
                sh.note("${i + 1}. ${z.name} (${kb(z.size)}) — tarefa ${z.item + 1}")
            }
            sh.section("O que fazer")
            sh.row(R.drawable.ic_send, "Enviar tudo ao GitHub", "Junta tudo em um ZIP: um único build", highlighted = true) { sendMerged() }
            sh.row(R.drawable.ic_bolt, "Um por um", "Envia na ordem, com um build para cada ZIP") { sendOneByOne() }
            sh.row(R.drawable.ic_download, "Salvar em Downloads", "Um ZIP único com tudo") { saveMerged() }
        }
        sh.row(R.drawable.ic_edit, "Voltar à lista", null) { showPanel() }
        sh.show()
    }

    private fun saveMerged() {
        Thread {
            val f = try { Script.merged(a) } catch (_: Exception) { null }
            a.runOnUiThread {
                if (f == null) { a.scrSay("Não consegui juntar os ZIPs", true); return@runOnUiThread }
                a.scrSave(f.readBytes(), f.name)
                a.scrSay("ZIP único salvo em Downloads", true)
            }
        }.start()
    }

    private fun sendMerged() {
        val proj = Projects.current(a)
        if (proj == null || Projects.token(a).isBlank()) {
            a.scrSay("Defina o Projeto atual e o Token do GitHub primeiro", true)
            return
        }
        val popup = BuildPopup(a, proj.name, "Enviar tudo")
        popup.show()
        popup.status("Juntando os ZIPs…")
        Thread {
            val f = try { Script.merged(a) } catch (_: Exception) { null }
            if (f == null) {
                popup.status("Não consegui juntar os ZIPs ✗")
                popup.done(false, null, null)
                return@Thread
            }
            val bytes = f.readBytes()
            Handoff.log(a, proj.id, "ZIP enviado: ${f.name} (script, ${Script.zips.size} ZIP juntos)")
            GitHubDelivery.deliver(
                a, proj, f.name, bytes,
                onStatus = { popup.status(it) },
                onSteps = { popup.steps(it) },
                onDone = { ok, url, apk ->
                    Handoff.log(a, proj.id, f.name + " → " + if (ok) "build OK, APK pronto" else "build falhou ou sem APK")
                    popup.done(ok, url, apk)
                }
            )
        }.start()
    }

    private fun sendOneByOne() {
        val proj = Projects.current(a)
        if (proj == null || Projects.token(a).isBlank()) {
            a.scrSay("Defina o Projeto atual e o Token do GitHub primeiro", true)
            return
        }
        val list = Script.zips.toList()
        val popup = BuildPopup(a, proj.name, "Enviar um por um")
        popup.show()
        var okCount = 0
        fun next(i: Int) {
            if (i >= list.size) {
                popup.status("Terminou: $okCount de ${list.size} build(s) ok")
                popup.done(okCount == list.size, null, null)
                return
            }
            val z = list[i]
            val bytes = try { Script.zipFile(a, z).readBytes() } catch (_: Exception) { null }
            if (bytes == null) { next(i + 1); return }
            popup.status("Enviando ${i + 1} de ${list.size}: ${z.name}…")
            Handoff.log(a, proj.id, "ZIP enviado: ${z.name}")
            GitHubDelivery.deliver(
                a, proj, z.name, bytes,
                onStatus = { popup.status("(${i + 1}/${list.size}) $it") },
                onSteps = { popup.steps(it) },
                onDone = { ok, _, _ ->
                    Handoff.log(a, proj.id, z.name + " → " + if (ok) "build OK, APK pronto" else "build falhou ou sem APK")
                    if (ok) okCount++
                    next(i + 1)
                }
            )
        }
        Thread { next(0) }.start()
    }

    // ───────────── relatório ao vivo ─────────────

    private val zipCache = HashMap<String, List<Pair<String, Long>>>()

    private fun sizeLabel(n: Long) = if (n >= 1024) "${n / 1024} KB" else "$n B"

    private fun stateName() = when (Script.status) {
        Script.RUNNING -> "Rodando"
        Script.PAUSED -> "Pausado"
        Script.DONE -> "Concluído"
        else -> "Parado"
    }

    private fun buildReport(): String {
        val hm = SimpleDateFormat("HH:mm:ss", Locale.US)
        val total = Script.items.size
        val ok = Script.items.count { it.state == Script.OK }
        val fail = Script.items.count { it.state == Script.FAIL }
        val sb = StringBuilder()
        sb.append("ESTADO: ").append(stateName())
        if (Script.status == Script.RUNNING || Script.status == Script.PAUSED) sb.append(" — tarefa ${Script.idx + 1} de $total")
        sb.append('\n')
        if (Script.status == Script.RUNNING && Script.ui.isNotBlank()) sb.append("AGORA: ").append(Script.ui).append('\n')
        if (Script.status == Script.PAUSED && Script.reason.isNotBlank()) sb.append("MOTIVO: ").append(Script.reason).append('\n')
        sb.append("Concluídas: $ok · Falhas: $fail · ZIPs guardados: ${Script.zips.size}\n\n")

        sb.append("TAREFAS\n")
        Script.items.forEachIndexed { i, item ->
            val icon = when (item.state) { Script.OK -> "✓"; Script.FAIL -> "✗"; Script.RUN -> "⏳"; else -> "•" }
            sb.append("$icon ${i + 1}. ${snippet(item.text)}\n   ${stateLabel(item.state)}\n")
            Script.zips.filter { it.item == i }.forEach { z ->
                val files = zipCache.getOrPut(z.file) { Script.zipFiles(a, z) }
                sb.append("   ZIP ${z.name} (${kb(z.size)}) — ${files.size} arquivo(s):\n")
                files.forEach { (n, sz) -> sb.append("     • $n (${sizeLabel(sz)})\n") }
            }
            sb.append('\n')
        }

        sb.append("LINHA DO TEMPO (mais recente primeiro)\n")
        val ev = Script.eventsSnapshot()
        if (ev.isEmpty()) sb.append("(nada ainda)\n")
        ev.asReversed().forEach { sb.append(hm.format(Date(it.t))).append("  ").append(it.text).append('\n') }
        return sb.toString()
    }

    fun showReport() {
        val pad = Ui.dp(a, 16)
        val bar = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val head = TextView(a).apply {
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Ui.color(a, R.color.text))
        }
        val tv = TextView(a).apply {
            textSize = 12.5f
            setTextColor(Ui.color(a, R.color.text))
            setTextIsSelectable(true)
        }
        val sc = ScrollView(a).apply { addView(tv) }
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, Ui.dp(a, 8), pad, 0)
            addView(head)
            addView(bar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(a, 8); bottomMargin = Ui.dp(a, 8) })
            addView(sc, LinearLayout.LayoutParams(-1, Ui.dp(a, 420)))
        }
        val d = MaterialAlertDialogBuilder(a)
            .setTitle("Relatório ao vivo")
            .setView(box)
            .setPositiveButton("Fechar", null)
            .setNeutralButton("Copiar", null)
            .create()
        val handler = a.scrHandler()
        var report = ""
        val tick = object : Runnable {
            override fun run() {
                val total = Script.items.size
                val done = Script.items.count { it.state == Script.OK || it.state == Script.FAIL }
                head.text = "${stateName()} · $done de $total tarefa(s) · ${Script.zips.size} ZIP(s)"
                bar.progress = if (total == 0) 0 else done * 100 / total
                report = buildReport()
                tv.text = report
                handler.postDelayed(this, 1000)
            }
        }
        d.setOnDismissListener { handler.removeCallbacks(tick) }
        d.show()
        d.getButton(android.content.DialogInterface.BUTTON_NEUTRAL)?.setOnClickListener {
            try {
                val cm = a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("relatorio", report))
                a.scrSay("Relatório copiado")
            } catch (_: Exception) { }
        }
        tick.run()
    }
}
