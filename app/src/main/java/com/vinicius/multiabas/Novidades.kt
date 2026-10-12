package com.vinicius.multiabas

import android.app.Activity
import androidx.appcompat.app.AlertDialog

object Novidades {
    fun show(a: Activity, force: Boolean) {
        try {
            val txt = a.assets.open("novidades.txt").bufferedReader().readText()
            val sec = txt.split("#id ").firstOrNull { it.isNotBlank() } ?: return
            val lines = sec.lines()
            val id = lines[0].trim()
            val p = a.getSharedPreferences("nov", 0)
            val first = p.getString("seen", null) == null
            if (!force && (first || p.getString("seen", null) == id)) { p.edit().putString("seen", id).apply(); return }
            p.edit().putString("seen", id).apply()
            val title = lines.firstOrNull { it.startsWith("#titulo ") }?.removePrefix("#titulo ") ?: "O que mudou"
            val body = lines.filter { it.startsWith("- ") }.joinToString("\n") { "• " + it.removePrefix("- ") }
            AlertDialog.Builder(a).setTitle(title).setMessage(body).setPositiveButton("Legal", null).show()
        } catch (e: Exception) { }
    }
}
