package com.vinicius.multiabas

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.app.NotificationCompat
import java.io.File

/**
 * Instala o APK do jogo logo que o build termina, sem passar pela notificação.
 * No Android 12+, se o Multi Abas foi quem instalou o jogo, a atualização pode ser silenciosa;
 * nos outros casos o sistema mostra a tela de confirmação na hora (um toque em Instalar).
 */
object AutoInstall {
    const val ACTION = "com.vinicius.multiabas.INSTALL_RESULT"

    fun enabled(ctx: Context) = ctx.getSharedPreferences("tabs", 0).getBoolean("auto_install", true)

    /** true se a instalação foi iniciada; false se precisa do caminho antigo (permissão, erro). */
    fun install(ctx: Context, apk: File, label: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) return false
            val pi = ctx.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            if (Build.VERSION.SDK_INT >= 31) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val id = pi.createSession(params)
            pi.openSession(id).use { s ->
                apk.inputStream().use { input ->
                    s.openWrite("game.apk", 0, apk.length()).use { out ->
                        input.copyTo(out)
                        s.fsync(out)
                    }
                }
                val intent = Intent(ctx, InstallResultReceiver::class.java)
                    .setAction(ACTION)
                    .putExtra("label", label)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pend = PendingIntent.getBroadcast(ctx, id, intent, flags)
                s.commit(pend.intentSender)
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}

class InstallResultReceiver : BroadcastReceiver() {

    @Suppress("DEPRECATION")
    private fun confirmIntent(i: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        else i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)

    override fun onReceive(ctx: Context, i: Intent) {
        val status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val label = i.getStringExtra("label") ?: "Jogo"
        val msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = confirmIntent(i) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { ctx.startActivity(confirm) } catch (_: Exception) { }
                val pi = PendingIntent.getActivity(
                    ctx, 78, confirm, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                post(ctx, "Confirme a atualização — $label", "Toque para instalar", pi)
            }
            PackageInstaller.STATUS_SUCCESS -> {
                (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(ID_PENDING)
                post(ctx, "Atualizado ✓ — $label", "A nova versão já está instalada", null)
            }
            else -> {
                val conflict = status == PackageInstaller.STATUS_FAILURE_CONFLICT ||
                    msg.contains("signature", true) || msg.contains("INCONSISTENT", true)
                val text = if (conflict) "A assinatura do APK é diferente da instalada. Desinstale o jogo e instale de novo, uma vez."
                else "Não instalou: ${msg.ifBlank { "erro $status" }}"
                post(ctx, "Falha na atualização — $label", text, null)
            }
        }
    }

    private fun post(ctx: Context, title: String, text: String, pi: PendingIntent?) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CH, "Instalação do jogo", NotificationManager.IMPORTANCE_HIGH))
        }
        val b = NotificationCompat.Builder(ctx, CH)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_launcher)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        if (pi != null) b.setContentIntent(pi)
        nm.notify(if (pi != null) ID_PENDING else ID_RESULT, b.build())
    }

    companion object {
        private const val CH = "install_result"
        private const val ID_PENDING = 7701
        private const val ID_RESULT = 7702
    }
}
