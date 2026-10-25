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

    data class Result(val ok: Boolean, val message: String, val htmlUrl: String? = null)

    fun deliver(ctx: Context, project: Project, fileName: String, bytes: ByteArray, onStatus: (String) -> Unit) {
        Thread {
            try {
                val token = Projects.token(ctx)
                if (token.isBlank()) {
                    onStatus("Configure o token do GitHub em Configurações")
                    notify(ctx, "Falta token", "Abra Configurações e cole o Personal Access Token", null)
                    return@Thread
                }
                onStatus("Resolvendo branch…")
                val branch = resolveBranch(token, project)
                project.branch = branch
                val list = Projects.load(ctx)
                list.firstOrNull { it.id == project.id }?.branch = branch
                Projects.save(ctx, list)

                val path = project.pathFor(fileName)
                onStatus("Enviando $fileName → ${project.fullRepo()}/$path ($branch)…")
                val put = putFile(token, project, branch, path, bytes, "Entrega automática: $fileName")
                if (!put.ok) {
                    onStatus(put.message)
                    notify(ctx, "Falha no envio", put.message, put.htmlUrl)
                    return@Thread
                }
                onStatus("ZIP enviado. Aguardando workflow…")
                notify(ctx, "ZIP enviado", "${project.name}: $fileName", put.htmlUrl)

                Thread.sleep(4000)
                val run = waitForRun(token, project, branch, onStatus)
                if (run == null) {
                    onStatus("Envio ok. Não encontrei o workflow (confira Actions).")
                    notify(ctx, "Enviado", "ZIP no repo. Confira Actions se o build rodou.", actionsUrl(project))
                    return@Thread
                }
                when (run.status) {
                    "success" -> {
                        onStatus("Build OK ✓ — buscando APK…")
                        notify(ctx, "Build OK ✓", "${project.name}: baixando APK…", run.html)
                        val apk = downloadLatestApk(ctx, token, project, onStatus)
                        if (apk != null) {
                            onStatus("APK pronto — toque na notificação para instalar")
                            notifyInstall(ctx, project, apk)
                        } else {
                            onStatus("Build OK, mas não achei APK na release")
                            notify(ctx, "Build OK", "Sem APK na release. Confira o workflow.", run.html)
                        }
                    }
                    "failure", "cancelled", "timed_out" -> {
                        onStatus("Build falhou ✗")
                        notify(ctx, "Build falhou ✗", "${project.name}: veja o log", run.html)
                    }
                    else -> {
                        onStatus("Workflow: ${run.status}")
                        notify(ctx, "Workflow ${run.status}", project.name, run.html)
                    }
                }
            } catch (e: Exception) {
                val msg = e.message ?: "erro desconhecido"
                onStatus("Erro: $msg")
                notify(ctx, "Erro na entrega", msg, null)
            }
        }.start()
    }

    fun downloadLatestApk(ctx: Context, token: String, p: Project, onStatus: (String) -> Unit): File? {
        return try {
            var assetUrl: String? = null
            var assetName = "update.apk"
            val deadline = System.currentTimeMillis() + 6 * 60 * 1000L
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
            c.inputStream.use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
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

    private data class RunInfo(val status: String, val html: String?)

    private fun resolveBranch(token: String, p: Project): String {
        if (p.branch.isNotBlank()) return p.branch
        val j = apiGet(token, "/repos/${p.fullRepo()}")
        return j?.optString("default_branch", "main") ?: "main"
    }

    private fun putFile(
        token: String, p: Project, branch: String, path: String, bytes: ByteArray, message: String
    ): Result {
        val existing = apiGet(token, "/repos/${p.fullRepo()}/contents/$path?ref=$branch")
        val sha = existing?.optString("sha")
        val body = JSONObject()
            .put("message", message)
            .put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
            .put("branch", branch)
        if (!sha.isNullOrBlank()) body.put("sha", sha)

        val url = URL("$API/repos/${p.fullRepo()}/contents/$path")
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

    private fun waitForRun(token: String, p: Project, branch: String, onStatus: (String) -> Unit): RunInfo? {
        val deadline = System.currentTimeMillis() + 12 * 60 * 1000L
        var html: String? = null
        var saw = false
        while (System.currentTimeMillis() < deadline) {
            val j = apiGet(token, "/repos/${p.fullRepo()}/actions/runs?branch=$branch&per_page=8")
            val arr = j?.optJSONArray("workflow_runs")
            if (arr != null) {
                val now = System.currentTimeMillis()
                var best: org.json.JSONObject? = null
                for (i in 0 until arr.length()) {
                    val r = arr.getJSONObject(i)
                    val created = parseIso(r.optString("created_at"))
                    if (now - created > 20 * 60 * 1000L) continue
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
                            status != "completed" -> "$name: $status…"
                            else -> "$name: $conclusion"
                        }
                    )
                    if (status == "completed") {
                        val n = name.lowercase()
                        val isBuild = n.contains("build") || n.contains("apk") || n.contains("android")
                        if (!isBuild && conclusion == "success") {
                            Thread.sleep(8000)
                            continue
                        }
                        return RunInfo(if (conclusion.isBlank()) "completed" else conclusion, html)
                    }
                }
            }
            Thread.sleep(8000)
        }
        return if (saw) RunInfo("timeout", html) else null
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
