package com.vinicius.multiabas

import android.app.Activity
import android.os.Bundle
import java.io.File

/**
 * Activity transparente: ao tocar na notificação "Instalar", abre o instalador do APK
 * e, se já estiver instalado com o package certo, permite abrir o jogo depois.
 */
class InstallReceiverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_APK)
        if (path.isNullOrBlank()) {
            finish()
            return
        }
        val f = File(path)
        if (f.exists()) {
            GitHubDelivery.installApk(this, f)
        }
        finish()
    }

    companion object {
        const val EXTRA_APK = "apk_path"
        const val EXTRA_PKG = "pkg"
    }
}
