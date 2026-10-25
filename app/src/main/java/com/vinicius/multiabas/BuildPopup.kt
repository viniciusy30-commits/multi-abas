package com.vinicius.multiabas

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/**
 * Popup que acompanha a entrega em tempo real: envio do ZIP, workflows e passos do build.
 * Pode ser minimizado a qualquer momento; a entrega continua e as notificações seguem funcionando.
 */
class BuildPopup(private val act: Activity, projectName: String, title: String = "Entrega") {
    private val status = TextView(act)
    private val steps = TextView(act)
    private val bar = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal)
    private val btnOpen = MaterialButton(act, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
    private val btnInstall = MaterialButton(act)
    private val scroll = ScrollView(act)
    private var dialog: AlertDialog? = null

    private val cText = Ui.color(act, R.color.text)
    private val cMuted = Ui.color(act, R.color.muted)
    private val cAccent = Ui.color(act, R.color.accent)
    private val cOk = Ui.color(act, R.color.ok)
    private val cDanger = Ui.color(act, R.color.danger)

    private fun dp(v: Int) = Ui.dp(act, v)

    init {
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(6), dp(22), dp(2))
        }
        bar.isIndeterminate = true
        bar.progressTintList = android.content.res.ColorStateList.valueOf(cAccent)
        bar.indeterminateTintList = android.content.res.ColorStateList.valueOf(cAccent)
        root.addView(bar, LinearLayout.LayoutParams(-1, dp(4)).apply { bottomMargin = dp(14) })

        status.apply {
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(cText)
            text = "Iniciando…"
        }
        root.addView(status)

        steps.apply {
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(cMuted)
            setLineSpacing(0f, 1.15f)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        scroll.background = Ui.shape(act, Color.parseColor("#12151B"), 16, Ui.color(act, R.color.outline), 1)
        scroll.addView(steps)
        root.addView(scroll, LinearLayout.LayoutParams(-1, dp(250)).apply { topMargin = dp(12); bottomMargin = dp(12) })

        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        btnOpen.apply {
            text = "Ver no GitHub"
            visibility = View.GONE
        }
        btnInstall.apply {
            text = "Instalar"
            visibility = View.GONE
        }
        row.addView(btnOpen)
        row.addView(btnInstall, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        root.addView(row)

        dialog = MaterialAlertDialogBuilder(act)
            .setTitle("$title — $projectName")
            .setView(root)
            .setNegativeButton("Minimizar", null)
            .setCancelable(true)
            .create()
    }

    private fun alive() = !act.isFinishing && !act.isDestroyed

    fun show() {
        act.runOnUiThread { if (alive()) dialog?.show() }
    }

    fun status(text: String) {
        act.runOnUiThread { if (alive()) status.text = text }
    }

    /** Percentual de um download (troca a barra indeterminada por uma determinada). */
    fun progress(pct: Int) {
        act.runOnUiThread {
            if (!alive()) return@runOnUiThread
            bar.isIndeterminate = false
            bar.max = 100
            bar.progress = pct.coerceIn(0, 100)
        }
    }

    fun steps(text: String) {
        act.runOnUiThread {
            if (!alive()) return@runOnUiThread
            val atBottom = scroll.getChildAt(0).bottom - (scroll.height + scroll.scrollY) < dp(40)
            steps.text = colorize(text)
            if (atBottom) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    /** Pinta o ícone de cada linha: ✓ verde, ✗ vermelho, ⏳ laranja, título em negrito. */
    private fun colorize(text: String): CharSequence {
        val sb = SpannableStringBuilder()
        text.lines().forEachIndexed { i, line ->
            if (i > 0) sb.append('\n')
            val start = sb.length
            sb.append(line)
            val idx = line.indexOfFirst { !it.isWhitespace() }
            if (idx < 0) return@forEachIndexed
            val len = Character.charCount(line.codePointAt(idx))
            val icon = line.substring(idx, idx + len)
            val color = when (icon) {
                "✓" -> cOk
                "✗" -> cDanger
                "⏳" -> cAccent
                "▸" -> cText
                else -> 0
            }
            if (color != 0) {
                sb.setSpan(ForegroundColorSpan(color), start + idx, start + idx + len, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (icon == "▸") {
                sb.setSpan(StyleSpan(Typeface.BOLD), start, start + line.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(ForegroundColorSpan(cText), start, start + line.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else if (icon == "✗") {
                sb.setSpan(ForegroundColorSpan(cDanger), start + idx, start + line.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else if (icon == "⏳") {
                sb.setSpan(ForegroundColorSpan(cText), start + idx, start + line.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return sb
    }

    fun done(ok: Boolean, url: String?, apk: File?) {
        act.runOnUiThread {
            if (!alive()) return@runOnUiThread
            bar.isIndeterminate = false
            bar.max = 100
            bar.progress = 100
            bar.progressTintList = android.content.res.ColorStateList.valueOf(if (ok) cOk else cDanger)
            status.setTextColor(if (ok) cOk else cDanger)
            if (!url.isNullOrBlank()) {
                btnOpen.visibility = View.VISIBLE
                btnOpen.setOnClickListener {
                    try { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) { }
                }
            }
            if (apk != null) {
                btnInstall.visibility = View.VISIBLE
                btnInstall.setOnClickListener { GitHubDelivery.installApk(act, apk) }
            }
        }
    }
}
