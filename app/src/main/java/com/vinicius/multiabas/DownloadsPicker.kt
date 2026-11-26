package com.vinicius.multiabas

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.os.Environment
import android.text.TextUtils
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/**
 * Lista própria da pasta Downloads: navega entre as pastas (uma por repositório),
 * tem a pasta virtual "Favoritos" e deixa marcar vários arquivos de uma vez.
 */
object DownloadsPicker {

    private const val PREF = "dl_favs"
    private const val KEY = "paths"

    fun hasAccess(): Boolean =
        Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()

    // ── favoritos (guardados pelo caminho completo do arquivo) ──

    fun favorites(ctx: Context): Set<String> =
        HashSet(ctx.getSharedPreferences(PREF, 0).getStringSet(KEY, emptySet()) ?: emptySet())

    fun setFavorite(ctx: Context, path: String, on: Boolean) {
        val s = HashSet(favorites(ctx))
        if (on) s.add(path) else s.remove(path)
        ctx.getSharedPreferences(PREF, 0).edit().putStringSet(KEY, s).apply()
    }

    private fun visible(f: File) =
        !f.name.startsWith(".") && !f.name.endsWith(".crdownload")

    private fun filesOf(dir: File): List<File> =
        (dir.listFiles() ?: emptyArray()).filter { it.isFile && visible(it) }
            .sortedByDescending { it.lastModified() }
            .take(300)

    private fun dirsOf(dir: File, firstName: String): List<File> =
        (dir.listFiles() ?: emptyArray()).filter { it.isDirectory && visible(it) }
            .sortedWith(
                compareByDescending<File> { it.name == firstName && firstName.isNotEmpty() }
                    .thenByDescending { it.lastModified() }
            )

    private fun countIn(dir: File): Int =
        (dir.listFiles() ?: emptyArray()).count { visible(it) && (it.isFile || it.isDirectory) }

    private sealed class Row {
        object Up : Row()
        data class Favs(val count: Int) : Row()
        data class Dir(val f: File, val count: Int, val isCurrent: Boolean) : Row()
        data class Item(val f: File) : Row()
    }

    fun show(ctx: Context, onPick: (List<File>) -> Unit, onOther: () -> Unit, onCancel: () -> Unit) {
        val cText = Ui.color(ctx, R.color.text)
        val cMuted = Ui.color(ctx, R.color.muted)
        val cAccent = Ui.color(ctx, R.color.accent)
        val cSurface2 = Ui.color(ctx, R.color.surface2)
        val rootDir = DownloadFolders.root()
        val currentFolder = DownloadFolders.current(ctx)
        var done = false

        // estado de navegação
        var dir: File = rootDir
        var favView = false
        val selected = LinkedHashSet<String>() // vale entre pastas
        var rows: List<Row> = emptyList()

        val title = TextView(ctx).apply {
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(cText)
        }
        val sub = TextView(ctx).apply {
            textSize = 13f
            setTextColor(cMuted)
            setPadding(0, Ui.dp(ctx, 2), 0, Ui.dp(ctx, 10))
        }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 16), Ui.dp(ctx, 16), Ui.dp(ctx, 8))
        }
        root.addView(title)
        root.addView(sub)

        val sendBtn = Button(ctx).apply {
            isAllCaps = false
            setTextColor(cText)
            background = Ui.shape(ctx, cAccent, 12)
            text = "Enviar"
            isEnabled = false
            alpha = 0.5f
        }
        fun refreshBtn() {
            val n = selected.size
            sendBtn.text = if (n == 0) "Enviar" else "Enviar ($n)"
            sendBtn.isEnabled = n > 0
            sendBtn.alpha = if (n > 0) 1f else 0.5f
        }

        fun atTop() = !favView && dir == rootDir

        fun buildRows(): List<Row> {
            val out = ArrayList<Row>()
            if (favView) {
                out.add(Row.Up)
                val all = favorites(ctx)
                all.map { File(it) }.filter { it.isFile }
                    .sortedByDescending { it.lastModified() }
                    .forEach { out.add(Row.Item(it)) }
                return out
            }
            if (dir == rootDir) {
                out.add(Row.Favs(favorites(ctx).count { File(it).isFile }))
            } else {
                out.add(Row.Up)
            }
            dirsOf(dir, if (dir == rootDir) currentFolder else "").forEach {
                out.add(Row.Dir(it, countIn(it), dir == rootDir && it.name == currentFolder && currentFolder.isNotEmpty()))
            }
            filesOf(dir).forEach { out.add(Row.Item(it)) }
            return out
        }

        lateinit var adapter: BaseAdapter
        lateinit var lv: ListView

        fun reload(scrollTop: Boolean = false) {
            rows = buildRows()
            title.text = when {
                favView -> "★ Favoritos"
                dir == rootDir -> "Downloads"
                else -> "Downloads / " + dir.relativeTo(rootDir).path.replace("/", " / ")
            }
            val hasFiles = rows.any { it is Row.Item }
            val hasDirs = rows.any { it is Row.Dir }
            sub.text = when {
                favView && !hasFiles -> "Nenhum favorito ainda. Toque na estrela de um arquivo."
                !hasFiles && !hasDirs -> "Pasta vazia"
                else -> "Toque na estrela para favoritar. Toque no arquivo para marcar vários."
            }
            adapter.notifyDataSetChanged()
            if (scrollTop) lv.setSelection(0)
        }

        fun goUp() {
            if (favView) { favView = false; dir = rootDir }
            else if (dir != rootDir) dir = dir.parentFile ?: rootDir
            reload(true)
        }

        adapter = object : BaseAdapter() {
            override fun getCount() = rows.size
            override fun getItem(i: Int) = rows[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, convert: View?, parent: ViewGroup): View {
                val r = rows[i]
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(Ui.dp(ctx, 4), Ui.dp(ctx, 10), Ui.dp(ctx, 4), Ui.dp(ctx, 10))
                }
                fun lead(icon: Int, tint: Int) = ImageView(ctx).apply {
                    setImageResource(icon)
                    setColorFilter(tint)
                    layoutParams = LinearLayout.LayoutParams(Ui.dp(ctx, 28), Ui.dp(ctx, 28)).apply {
                        marginStart = Ui.dp(ctx, 6); marginEnd = Ui.dp(ctx, 6)
                    }
                }
                fun textCol(name: String, detail: String?, bold: Boolean, color: Int): LinearLayout {
                    val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                    col.addView(TextView(ctx).apply {
                        text = name
                        textSize = 15f
                        if (bold) setTypeface(typeface, Typeface.BOLD)
                        setTextColor(color)
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.MIDDLE
                    })
                    if (detail != null) col.addView(TextView(ctx).apply {
                        text = detail
                        textSize = 12f
                        setTextColor(cMuted)
                    })
                    return col
                }
                when (r) {
                    is Row.Up -> {
                        row.addView(TextView(ctx).apply {
                            text = "‹"; textSize = 26f; setTextColor(cAccent)
                            gravity = Gravity.CENTER
                        }, LinearLayout.LayoutParams(Ui.dp(ctx, 40), -2))
                        row.addView(textCol("Voltar", null, true, cAccent), LinearLayout.LayoutParams(0, -2, 1f))
                    }
                    is Row.Favs -> {
                        row.addView(lead(R.drawable.ic_star, cAccent))
                        row.addView(
                            textCol("Favoritos", "${r.count} arquivo(s)", true, cText),
                            LinearLayout.LayoutParams(0, -2, 1f)
                        )
                        row.addView(TextView(ctx).apply { text = "›"; textSize = 22f; setTextColor(cMuted) })
                    }
                    is Row.Dir -> {
                        row.addView(lead(R.drawable.ic_folder, cAccent))
                        val d = (if (r.isCurrent) "Projeto atual · " else "") + "${r.count} item(ns)"
                        row.addView(textCol(r.f.name, d, true, cText), LinearLayout.LayoutParams(0, -2, 1f))
                        row.addView(TextView(ctx).apply { text = "›"; textSize = 22f; setTextColor(cMuted) })
                    }
                    is Row.Item -> {
                        val f = r.f
                        row.addView(CheckBox(ctx).apply {
                            isChecked = selected.contains(f.absolutePath)
                            isClickable = false
                            isFocusable = false
                        })
                        val where = if (favView) {
                            val p = f.parentFile
                            (if (p != null && p != rootDir) p.name + " · " else "")
                        } else ""
                        val detail = where + Formatter.formatShortFileSize(ctx, f.length()) + " · " +
                            DateUtils.getRelativeTimeSpanString(f.lastModified(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                        row.addView(
                            textCol(f.name, detail, false, cText),
                            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = Ui.dp(ctx, 6) }
                        )
                        val fav = favorites(ctx).contains(f.absolutePath)
                        row.addView(ImageView(ctx).apply {
                            setImageResource(if (fav) R.drawable.ic_star else R.drawable.ic_star_outline)
                            setColorFilter(if (fav) cAccent else cMuted)
                            val pad = Ui.dp(ctx, 10)
                            setPadding(pad, pad, pad, pad)
                            layoutParams = LinearLayout.LayoutParams(Ui.dp(ctx, 46), Ui.dp(ctx, 46))
                            setOnClickListener {
                                setFavorite(ctx, f.absolutePath, !fav)
                                reload()
                            }
                        })
                    }
                }
                return row
            }
        }

        lv = ListView(ctx).apply {
            this.adapter = adapter
            divider = null
            setOnItemClickListener { _, _, pos, _ ->
                when (val r = rows.getOrNull(pos) ?: return@setOnItemClickListener) {
                    is Row.Up -> goUp()
                    is Row.Favs -> { favView = true; reload(true) }
                    is Row.Dir -> { dir = r.f; reload(true) }
                    is Row.Item -> {
                        val p = r.f.absolutePath
                        if (!selected.remove(p)) selected.add(p)
                        adapter.notifyDataSetChanged()
                        refreshBtn()
                    }
                }
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

        reload()

        val dlg = MaterialAlertDialogBuilder(ctx).setView(root).create()
        dlg.setOnDismissListener { if (!done) { done = true; onCancel() } }
        // Botão voltar do Android sobe uma pasta antes de fechar a janela
        dlg.setOnKeyListener { _, keyCode, ev ->
            if (keyCode == KeyEvent.KEYCODE_BACK && ev.action == KeyEvent.ACTION_UP && !atTop()) {
                goUp(); true
            } else false
        }
        sendBtn.setOnClickListener {
            val sel = selected.map { File(it) }.filter { it.isFile }
            done = true; dlg.dismiss(); onPick(sel)
        }
        other.setOnClickListener { done = true; dlg.dismiss(); onOther() }
        cancel.setOnClickListener { dlg.dismiss() }
        dlg.show()
    }
}
