package com.vinicius.multiabas

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.ProgressBar
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object Updater {
    const val REPO = "viniciusy30-commits/multi-abas"

    fun autoCheck(a: Activity) {
        val p = a.getSharedPreferences("upd", 0)
        val now = System.currentTimeMillis()
        if (now - p.getLong("last", 0) < 20 * 60 * 1000L) return
        p.edit().putLong("last", now).apply()
        check(a, false)
    }

    fun check(a: Activity, manual: Boolean) {
        if (manual) Toast.makeText(a, "Procurando atualização…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
                c.connectTimeout = 10000; c.readTimeout = 10000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                val j = JSONObject(c.inputStream.bufferedReader().readText())
                val v = j.getString("tag_name").removePrefix("v").toIntOrNull() ?: 0
                val assets = j.getJSONArray("assets")
                var apk: String? = null
                for (i in 0 until assets.length()) {
                    val o = assets.getJSONObject(i)
                    if (o.getString("name").endsWith(".apk")) { apk = o.getString("browser_download_url"); break }
                }
                a.runOnUiThread {
                    if (apk != null && v > BuildConfig.VERSION_CODE) ask(a, v, apk)
                    else if (manual) Toast.makeText(a, "Você já está na versão mais nova", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                if (manual) a.runOnUiThread { Toast.makeText(a, "Sem internet ou não foi possível verificar", Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    private fun ask(a: Activity, v: Int, url: String) {
        MaterialAlertDialogBuilder(a).setTitle("Nova versão disponível")
            .setMessage("Versão 1.$v pronta para instalar.")
            .setPositiveButton("Atualizar") { _, _ -> download(a, url) }
            .setNegativeButton("Depois", null).show().also { (a as? MainActivity)?.trackDlg(it) }
    }

    private fun download(a: Activity, url: String) {
        val pb = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal)
        pb.max = 100; pb.setPadding(48, 32, 48, 16)
        val d = MaterialAlertDialogBuilder(a).setTitle("Baixando atualização…").setView(pb).setCancelable(false).show()
        Thread {
            try {
                val dir = File(a.cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val f = File(dir, "update.apk")
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 15000
                val total = c.contentLength.toLong()
                c.inputStream.use { i ->
                    f.outputStream().use { o ->
                        val b = ByteArray(16384); var n: Int; var s = 0L
                        while (i.read(b).also { n = it } > 0) {
                            o.write(b, 0, n); s += n
                            if (total > 0) { val p = (s * 100 / total).toInt(); a.runOnUiThread { pb.progress = p } }
                        }
                    }
                }
                a.runOnUiThread { d.dismiss(); install(a, f) }
            } catch (e: Exception) {
                a.runOnUiThread { d.dismiss(); Toast.makeText(a, "Falha ao baixar a atualização", Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    private fun install(a: Activity, f: File) {
        if (Build.VERSION.SDK_INT >= 26 && !a.packageManager.canRequestPackageInstalls()) {
            MaterialAlertDialogBuilder(a).setTitle("Permitir instalação")
                .setMessage("Para atualizar, libere \"Instalar apps desconhecidos\" para o Multi Abas. Depois volte e toque em Verificar atualização.")
                .setPositiveButton("Abrir configuração") { _, _ ->
                    a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.packageName)))
                }.setNegativeButton("Cancelar", null).show()
            return
        }
        val uri = FileProvider.getUriForFile(a, a.packageName + ".fileprovider", f)
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        a.startActivity(i)
    }
}
