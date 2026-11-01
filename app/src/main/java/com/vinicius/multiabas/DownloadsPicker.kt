package com.vinicius.multiabas

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.os.Environment
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/** Lista própria da pasta Downloads: sempre os mais recentes primeiro, com seleção de vários arquivos. */
object DownloadsPicker {

    fun hasAccess(): Boolean =
        Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()

    fun list(): List<File> {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val all = dir.listFiles() ?: return emptyList()
        return all.filter { it.isFile && !it.name.startsWith(".") && !it.name.endsWith(".crdownload") }
            .sortedByDescending { it.lastModified() }
            .take(300)
    }

    fun show(ctx: Context, onPick: (List<File>) -> Unit, onOther: () -> Unit, onCancel: () -> Unit) {
        val files = list()
        val checked = BooleanArray(files.size)
        val cText = Ui.color(ctx, R.color.text)
        val cMuted = Ui.color(ctx, R.color.muted)
        val cAccent = Ui.color(ctx, R.color.accent)
        val cSurface2 = Ui.color(ctx, R.color.surface2)
        var done = false

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 16), Ui.dp(ctx, 16), Ui.dp(ctx, 8))
        }
        root.addView(TextView(ctx).apply {
            text = "Downloads"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(cText)
        })
        root.addView(TextView(ctx).apply {
            text = if (files.isEmpty()) "Nenhum arquivo encontrado" else "Mais recentes primeiro. Toque para marcar vários."
            textSize = 13f
            setTextColor(cMuted)
            setPadding(0, Ui.dp(ctx, 2), 0, Ui.dp(ctx, 10))
        })

        val sendBtn = Button(ctx).apply {
            isAllCaps = false
            setTextColor(Ui.color(ctx, R.color.text))
            background = Ui.shape(ctx, cAccent, 12)
            text = "Enviar"
            isEnabled = false
            alpha = 0.5f
        }
        fun refreshBtn() {
            val n = checked.count { it }
            sendBtn.text = if (n == 0) "Enviar" else "Enviar ($n)"
            sendBtn.isEnabled = n > 0
            sendBtn.alpha = if (n > 0) 1f else 0.5f
        }

        val adapter = object : BaseAdapter() {
            override fun getCount() = files.size
            override fun getItem(i: Int) = files[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, convert: View?, parent: ViewGroup): View {
                val f = files[i]
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(Ui.dp(ctx, 4), Ui.dp(ctx, 10), Ui.dp(ctx, 4), Ui.dp(ctx, 10))
                }
                row.addView(CheckBox(ctx).apply {
                    isChecked = checked[i]
                    isClickable = false
                    isFocusable = false
                })
                val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                col.addView(TextView(ctx).apply {
                    text = f.name
                    textSize = 15f
                    setTextColor(cText)
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                })
                col.addView(TextView(ctx).apply {
                    text = Formatter.formatShortFileSize(ctx, f.length()) + " · " +
                        DateUtils.getRelativeTimeSpanString(f.lastModified(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                    textSize = 12f
                    setTextColor(cMuted)
                })
                row.addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = Ui.dp(ctx, 6) })
                return row
            }
        }

        val lv = ListView(ctx).apply {
            this.adapter = adapter
            divider = null
            setOnItemClickListener { _, _, pos, _ ->
                checked[pos] = !checked[pos]
                adapter.notifyDataSetChanged()
                refreshBtn()
            }
        }
        root.addView(lv, LinearLayout.LayoutParams(-1, (ctx.resources.displayMetrics.heightPixels * 0.5f).toInt()))

        val other = Button(ctx).apply {
            isAllCaps = false
            text = "Outros arquivos…"
            setTextColor(cText)
            background = Ui.shape(ctx, cSurface2, 12)
        }
        val cancel = Button(ctx).apply {
            isAllCaps = false
            text = "Cancelar"
            setTextColor(cMuted)
            background = null
        }
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, Ui.dp(ctx, 10), 0, 0)
        }
        bar.addView(cancel, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(other, LinearLayout.LayoutParams(0, -2, 1.3f).apply { marginStart = Ui.dp(ctx, 6) })
        bar.addView(sendBtn, LinearLayout.LayoutParams(0, -2, 1.2f).apply { marginStart = Ui.dp(ctx, 6) })
        root.addView(bar)

        val dlg = MaterialAlertDialogBuilder(ctx).setView(root).create()
        dlg.setOnDismissListener { if (!done) { done = true; onCancel() } }
        sendBtn.setOnClickListener {
            val sel = files.filterIndexed { i, _ -> checked[i] }
            done = true; dlg.dismiss(); onPick(sel)
        }
        other.setOnClickListener { done = true; dlg.dismiss(); onOther() }
        cancel.setOnClickListener { dlg.dismiss() }
        dlg.show()
    }
}
