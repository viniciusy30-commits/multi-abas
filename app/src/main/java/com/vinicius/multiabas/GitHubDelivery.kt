package com.vinicius.multiabas

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Sobe o ZIP em _incoming/ via GitHub Contents API, acompanha o Actions
 * e, se o build publicar release, baixa o APK e oferece instalação.
 */
object GitHubDelivery {
    private const val API = "https://api.github.com"
    private const val CHANNEL = "delivery"
    private const val NOTIF_BASE = 100

    /** Nome seguro para o GitHub: sem acentos, espaços ou símbolos; sempre termina em .zip. */
    fun safeName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        var n = java.text.Normalizer.normalize(base, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .replace(Regex("\\.{2,}"), ".")
            .trim('_', '.', '-')
        if (n.isEmpty()) n = "entrega"
        if (!n.lowercase().endsWith(".zip")) n += ".zip"
        return n
    }

    data class Result(val ok: Boolean, val message: String, val htmlUrl: String? = null)

    fun deliver(
        ctx: Context, project: Project, rawFileName: String, bytes: ByteArray,
        onStatus: (String) -> Unit,
        onSteps: (String) -> Unit = {},
        onDone: (Boolean, String?, File?) -> Unit = { _, _, _ -> },
        onProgress: (Int) -> Unit = {},
        onFailLog: (String) -> Unit = {}
    ) {
        Thread {
            try {
                val t0 = System.currentTimeMillis()
                val fileName = safeName(rawFileName)
                val token = Projects.token(ctx)
                if (token.isBlank()) {
                    onStatus("Configure o token do GitHub em Configurações")
                    notify(ctx, "Falta token", "Abra Configurações e cole o Personal Access Token", null)
                    onDone(false, null, null)
                    return@Thread
                }
                onStatus("Resolvendo branch…")
                val branch = resolveBranch(token, project)
                project.branch = branch
                val list = Projects.load(ctx)
                list.firstOrNull { it.id == project.id }?.branch = branch
                Projects.save(ctx, list)

                try { ensureWorkflow(ctx, token, project, branch, onStatus) } catch (_: Exception) { }

                val path = project.pathFor(fileName)
                onStatus("Enviando $fileName → ${project.fullRepo()}/$path ($branch)…")
                val put = putFile(token, project, branch, path, bytes, "Entrega automática: $fileName")
                if (!put.ok) {
                    onStatus(put.message + "\nCaminho: $path")
                    notify(ctx, "Falha no envio", put.message, put.htmlUrl)
                    onDone(false, put.htmlUrl, null)
                    return@Thread
                }
                onStatus("ZIP enviado. Aguardando workflow…")
                notify(ctx, "ZIP enviado", "${project.name}: $fileName", put.htmlUrl)

                Thread.sleep(4000)
                val run = waitForRun(token, project, branch, t0, onStatus, onSteps)
                if (run == null) {
                    onStatus("Envio ok. Não encontrei o workflow (confira Actions).")
                    notify(ctx, "Enviado", "ZIP no repo. Confira Actions se o build rodou.", actionsUrl(project))
                    onDone(false, actionsUrl(project), null)
                    return@Thread
                }
                when (run.status) {
                    "success" -> {
                        onStatus("Build OK ✓ — buscando APK…")
                        notify(ctx, "Build OK ✓", "${project.name}: baixando APK…", run.html)
                        val apk = downloadLatestApk(ctx, token, project, onStatus, 6 * 60 * 1000L, onProgress)
                        if (apk != null) {
                            onStatus("APK pronto ✓")
                            val started = AutoInstall.enabled(ctx) && AutoInstall.install(ctx, apk, project.name)
                            if (started) onStatus("Instalando a atualização…") else notifyInstall(ctx, project, apk)
                            onDone(true, run.html, apk)
                        } else {
                            onStatus("Build OK, mas não achei APK na release")
                            notify(ctx, "Build OK", "Sem APK na release. Confira o workflow.", run.html)
                            onDone(false, run.html, null)
                        }
                    }
                    "failure", "cancelled", "timed_out" -> {
                        onStatus("Build falhou ✗")
                        notify(ctx, "Build falhou ✗", "${project.name}: veja o log", run.html)
                        val log = try { failureLog(token, project, run.id) } catch (_: Exception) { "" }
                        if (log.isNotBlank()) onFailLog(log)
                        onDone(false, run.html, null)
                    }
                    else -> {
                        onStatus("Workflow: ${run.status}")
                        notify(ctx, "Workflow ${run.status}", project.name, run.html)
                        onDone(false, run.html, null)
                    }
                }
            } catch (e: Exception) {
                val msg = e.message ?: "erro desconhecido"
                onStatus("Erro: $msg")
                notify(ctx, "Erro na entrega", msg, null)
                onDone(false, null, null)
            }
        }.start()
    }

    fun downloadLatestApk(
        ctx: Context, token: String, p: Project, onStatus: (String) -> Unit,
        waitMs: Long = 6 * 60 * 1000L, onProgress: (Int) -> Unit = {}
    ): File? {
        return try {
            var assetUrl: String? = null
            var assetName = "update.apk"
            val deadline = System.currentTimeMillis() + waitMs
            while (System.currentTimeMillis() < deadline && assetUrl == null) {
                val rel = apiGet(token, "/repos/${p.fullRepo()}/releases/latest")
                if (rel != null) {
                    val assets = rel.optJSONArray("assets")
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val a = assets.getJSONObject(i)
                            val name = a.optString("name")
                            if (name.endsWith(".apk", true)) {
                                assetUrl = a.optString("url")
                                assetName = name
                                break
                            }
                        }
                    }
                    if (assetUrl == null) {
                        onStatus("Aguardando APK na release…")
                        Thread.sleep(8000)
                        continue
                    }
                } else {
                    onStatus("Aguardando release…")
                    Thread.sleep(8000)
                    continue
                }
            }
            if (assetUrl == null) return null

            onStatus("Baixando $assetName…")
            val dir = File(ctx.cacheDir, "game_apks").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val out = File(dir, assetName)

            val c = URL(assetUrl).openConnection() as HttpURLConnection
            c.connectTimeout = 30000
            c.readTimeout = 120000
            c.setRequestProperty("Accept", "application/octet-stream")
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            if (c.responseCode !in 200..299) return null
            val total = c.contentLengthLong
            c.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(32 * 1024)
                    var done = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                                onStatus("Baixando $assetName… $pct%")
                            }
                        }
                    }
                }
            }
            out
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Baixa o ZIP do código-fonte (branch) do repositório.
     * Usa a API zipball (funciona em repo privado com token).
     */
    fun downloadRepoZip(ctx: Context, token: String, p: Project, branch: String): File? {
        return try {
            val ref = branch.ifBlank { "main" }
            val url = URL("$API/repos/${p.fullRepo()}/zipball/$ref")
            val c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = true
            c.connectTimeout = 30000
            c.readTimeout = 180000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            if (token.isNotBlank()) c.setRequestProperty("Authorization", "Bearer $token")
            // seguir redirects manualmente se precisar (GitHub devolve 302)
            var conn = c
            var code = conn.responseCode
            var hops = 0
            while (code in 300..399 && hops < 5) {
                val loc = conn.getHeaderField("Location") ?: break
                conn.disconnect()
                conn = URL(loc).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 30000
                conn.readTimeout = 180000
                if (token.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $token")
                code = conn.responseCode
                hops++
            }
            if (code !in 200..299) return null
            val dir = File(ctx.cacheDir, "repo_zips").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val out = File(dir, "${p.repo}-$ref.zip")
            conn.inputStream.use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
            if (out.length() < 100) {
                out.delete()
                return null
            }
            out
        } catch (_: Exception) {
            null
        }
    }

    fun installApk(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", apk)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            val settings = Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + ctx.packageName)
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(settings)
            return
        }
        ctx.startActivity(i)
    }

    fun openGame(ctx: Context, p: Project): Boolean {
        val pkg = p.packageName.trim()
        if (pkg.isEmpty()) return false
        val launch = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(launch)
        return true
    }

    private data class RunInfo(val status: String, val html: String?, val id: Long = 0L)

    private fun resolveBranch(token: String, p: Project): String {
        if (p.branch.isNotBlank()) return p.branch
        val j = apiGet(token, "/repos/${p.fullRepo()}")
        return j?.optString("default_branch", "main") ?: "main"
    }

    private fun putFile(
        token: String, p: Project, branch: String, path: String, bytes: ByteArray, message: String
    ): Result {
        val existing = apiGet(token, "/repos/${p.fullRepo()}/contents/${Uri.encode(path, "/")}?ref=${Uri.encode(branch)}")
        val sha = existing?.optString("sha")
        val body = JSONObject()
            .put("message", message)
            .put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
            .put("branch", branch)
        if (!sha.isNullOrBlank()) body.put("sha", sha)

        val url = URL("$API/repos/${p.fullRepo()}/contents/${Uri.encode(path, "/")}")
        val c = url.openConnection() as HttpURLConnection
        c.requestMethod = "PUT"
        c.connectTimeout = 30000
        c.readTimeout = 60000
        c.doOutput = true
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
        val code = c.responseCode
        val text = try {
            (if (code in 200..299) c.inputStream else c.errorStream).bufferedReader().readText()
        } catch (_: Exception) { "" }
        return if (code in 200..299) {
            val j = try { JSONObject(text) } catch (_: Exception) { JSONObject() }
            val html = j.optJSONObject("content")?.optString("html_url")
                ?: j.optJSONObject("commit")?.optString("html_url")
            Result(true, "OK", html)
        } else {
            val msg = try { JSONObject(text).optString("message", text) } catch (_: Exception) { text }
            Result(false, "HTTP $code: $msg")
        }
    }

    private fun waitForRun(
        token: String, p: Project, branch: String, since: Long,
        onStatus: (String) -> Unit, onSteps: (String) -> Unit
    ): RunInfo? {
        val deadline = System.currentTimeMillis() + 12 * 60 * 1000L
        var html: String? = null
        var saw = false
        while (System.currentTimeMillis() < deadline) {
            val j = apiGet(token, "/repos/${p.fullRepo()}/actions/runs?branch=${Uri.encode(branch)}&per_page=8")
            val arr = j?.optJSONArray("workflow_runs")
            if (arr != null) {
                var best: JSONObject? = null
                for (i in 0 until arr.length()) {
                    val r = arr.getJSONObject(i)
                    val created = parseIso(r.optString("created_at"))
                    // só runs criados depois do envio (com folga de 60s para diferença de relógio)
                    if (created < since - 60 * 1000L) continue
                    val name = r.optString("name").lowercase()
                    val isBuild = name.contains("build") || name.contains("apk") || name.contains("android")
                    if (best == null) best = r
                    else if (isBuild) {
                        best = r
                        break
                    }
                }
                if (best != null) {
                    saw = true
                    html = best.optString("html_url")
                    val status = best.optString("status")
                    val conclusion = best.optString("conclusion", "")
                    val name = best.optString("name")
                    onStatus(
                        when {
                            status != "completed" -> "$name: ${statusPt(status)}…"
                            else -> "$name: ${statusPt(conclusion)}"
                        }
                    )
                    onSteps(stepsText(token, p, best.optLong("id"), name))
                    if (status == "completed") {
                        val n = name.lowercase()
                        val isBuild = n.contains("build") || n.contains("apk") || n.contains("android")
                        if (!isBuild && conclusion == "success") {
                            Thread.sleep(4000)
                            continue
                        }
                        return RunInfo(if (conclusion.isBlank()) "completed" else conclusion, html, best.optLong("id"))
                    }
                }
            }
            Thread.sleep(4000)
        }
        return if (saw) RunInfo("timeout", html) else null
    }

    private fun statusPt(s: String) = when (s) {
        "queued" -> "na fila"
        "in_progress" -> "rodando"
        "pending", "waiting", "requested" -> "aguardando"
        "success" -> "sucesso ✓"
        "failure" -> "falhou ✗"
        "cancelled" -> "cancelado"
        "timed_out" -> "tempo esgotado"
        "skipped" -> "pulado"
        else -> s
    }

    /** Monta a lista de jobs/passos do run, com ícone de estado, para o popup. */
    private fun stepsText(token: String, p: Project, runId: Long, runName: String): String {
        val sb = StringBuilder("▸ $runName\n")
        val jobs = apiGet(token, "/repos/${p.fullRepo()}/actions/runs/$runId/jobs")?.optJSONArray("jobs")
        if (jobs == null || jobs.length() == 0) {
            sb.append("  ○ aguardando um runner…")
            return sb.toString()
        }
        for (i in 0 until jobs.length()) {
            val job = jobs.getJSONObject(i)
            sb.append("\n").append(icon(job.optString("status"), job.optString("conclusion")))
                .append(" ").append(job.optString("name")).append("\n")
            val steps = job.optJSONArray("steps") ?: continue
            for (k in 0 until steps.length()) {
                val st = steps.getJSONObject(k)
                sb.append("   ").append(icon(st.optString("status"), st.optString("conclusion")))
                    .append(" ").append(st.optString("name")).append("\n")
            }
        }
        return sb.toString().trimEnd()
    }

    private fun icon(status: String, conclusion: String): String = when {
        status == "completed" -> when (conclusion) {
            "success" -> "✓"
            "failure", "timed_out" -> "✗"
            "cancelled" -> "⊘"
            "skipped" -> "–"
            else -> "•"
        }
        status == "in_progress" -> "⏳"
        else -> "○"
    }

    private fun parseIso(s: String): Long {
        return try {
            java.time.Instant.parse(s).toEpochMilli()
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
    }

    private fun apiGet(token: String, path: String): JSONObject? {
        return try {
            val c = URL("$API$path").openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            if (c.responseCode !in 200..299) return null
            JSONObject(c.inputStream.bufferedReader().readText())
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Garante que o workflow que descompacta o ZIP aguenta duas entregas seguidas
     * (sem isso o 2º "push" é recusado e o ZIP fica parado). Só troca o workflow padrão do app;
     * se o token não tiver permissão "workflow", segue sem mexer.
     */
    private fun ensureWorkflow(ctx: Context, token: String, p: Project, branch: String, onStatus: (String) -> Unit) {
        val path = ".github/workflows/unpack-and-commit.yml"
        val cur = apiGet(token, "/repos/${p.fullRepo()}/contents/$path?ref=${Uri.encode(branch)}") ?: return
        val sha = cur.optString("sha")
        val old = try {
            String(Base64.decode(cur.optString("content").replace("\n", ""), Base64.DEFAULT), StandardCharsets.UTF_8)
        } catch (_: Exception) { return }
        if (!old.contains("entrega-bot") || old.contains("pull --rebase")) return   // não é o padrão do app, ou já está corrigido
        val fresh = ctx.assets.open("unpack-and-commit.yml").readBytes()
        onStatus("Atualizando o workflow de entrega (evita ZIP preso)…")
        val body = JSONObject()
            .put("message", "Workflow de entrega: aguenta entregas seguidas")
            .put("content", Base64.encodeToString(fresh, Base64.NO_WRAP))
            .put("branch", branch)
            .put("sha", sha)
        val c = URL("$API/repos/${p.fullRepo()}/contents/$path").openConnection() as HttpURLConnection
        c.requestMethod = "PUT"
        c.connectTimeout = 30000
        c.readTimeout = 60000
        c.doOutput = true
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
        if (c.responseCode !in 200..299) onStatus("Não consegui atualizar o workflow (o token precisa da permissão \"workflow\"); sigo com o antigo")
    }

    /** Texto simples de um endereço da API (segue o redirecionamento do log sem mandar o token adiante). */
    private fun apiGetText(token: String, path: String): String? {
        return try {
            val c = URL("$API$path").openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            var code = c.responseCode
            var conn = c
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: return null
                c.disconnect()
                conn = URL(loc).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                code = conn.responseCode
            }
            if (code !in 200..299) return null
            conn.inputStream.bufferedReader().readText()
        } catch (_: Exception) {
            null
        }
    }

    /** Pega do log do build só o que importa (erros do compilador / Gradle), pronto para mandar ao Claude. */
    fun failureLog(token: String, p: Project, runId: Long): String {
        if (runId <= 0L) return ""
        val jobs = apiGet(token, "/repos/${p.fullRepo()}/actions/runs/$runId/jobs")?.optJSONArray("jobs") ?: return ""
        val out = StringBuilder()
        for (i in 0 until jobs.length()) {
            val j = jobs.getJSONObject(i)
            val concl = j.optString("conclusion")
            if (concl != "failure" && concl != "cancelled" && concl != "timed_out") continue
            val steps = j.optJSONArray("steps")
            var failedStep = ""
            if (steps != null) for (k in 0 until steps.length()) {
                val s = steps.getJSONObject(k)
                if (s.optString("conclusion") == "failure") { failedStep = s.optString("name"); break }
            }
            val raw = apiGetText(token, "/repos/${p.fullRepo()}/actions/jobs/${j.optLong("id")}/logs") ?: continue
            out.append("=== Job: ").append(j.optString("name")).append(if (failedStep.isNotBlank()) " · passo que falhou: $failedStep" else "").append(" ===\n")
            out.append(extractErrors(raw)).append("\n\n")
        }
        return out.toString().trim()
    }

    private fun extractErrors(raw: String): String {
        val lines = raw.lines().map { it.replace(Regex("^\\d{4}-\\d{2}-\\d{2}T[\\d:.]+Z\\s?"), "") }
        val keep = java.util.LinkedHashSet<String>()
        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            val low = l.lowercase()
            val hit = l.startsWith("e: ") || l.contains("error:") || l.contains("FAILURE:") || l.contains("Execution failed") ||
                l.contains("What went wrong") || l.contains(" FAILED") || low.contains("exception") && !low.contains("at ") ||
                l.contains("##[error]") || l.contains("Process completed with exit code")
            if (hit) {
                keep.add(l.trim())
                if (l.contains("What went wrong") || l.contains("Execution failed")) {
                    for (k in 1..4) { if (i + k < lines.size && lines[i + k].isNotBlank()) keep.add(lines[i + k].trim()) }
                }
            }
            i++
        }
        val picked = if (keep.isEmpty()) lines.takeLast(120).joinToString("\n")
        else keep.take(260).joinToString("\n") + (if (keep.size > 260) "\n… (mais ${keep.size - 260} linhas de erro)" else "")
        return picked.take(30_000)
    }

    private fun actionsUrl(p: Project) = "https://github.com/${p.fullRepo()}/actions"

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(CHANNEL, "Entregas GitHub", NotificationManager.IMPORTANCE_DEFAULT)
        nm.createNotificationChannel(ch)
    }

    private fun notify(ctx: Context, title: String, body: String, url: String?) {
        ensureChannel(ctx)
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val b = NotificationCompat.Builder(ctx, CHANNEL)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(R.drawable.ic_launcher)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        if (!url.isNullOrBlank()) {
            val pi = PendingIntent.getActivity(
                ctx, url.hashCode(),
                Intent(Intent.ACTION_VIEW, Uri.parse(url)),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            b.setContentIntent(pi)
        }
        nm.notify(NOTIF_BASE + (System.currentTimeMillis() % 50).toInt(), b.build())
    }

    private fun notifyInstall(ctx: Context, p: Project, apk: File) {
        ensureChannel(ctx)
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val installIntent = Intent(ctx, InstallReceiverActivity::class.java)
            .putExtra(InstallReceiverActivity.EXTRA_APK, apk.absolutePath)
            .putExtra(InstallReceiverActivity.EXTRA_PKG, p.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            ctx, 77, installIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = NotificationCompat.Builder(ctx, CHANNEL)
            .setContentTitle("Atualização pronta — ${p.name}")
            .setContentText("Toque para instalar o APK")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "Instalar", pi)
        nm.notify(NOTIF_BASE + 1, b.build())
    }
}
