package com.vinicius.multiabas

import android.content.Context
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Uso de cada conta (0–100%), lido da página Configurações → Uso do próprio Claude. */
object Usage {
    /** s = % da sessão (5 h), w = % da semana; -1 se não achou. */
    class Info(
        val s: Int = -1, val sr: String = "",
        val w: Int = -1, val wr: String = "",
        val at: Long = System.currentTimeMillis(), val err: String = ""
    )

    val map = ConcurrentHashMap<String, Info>()
    @Volatile var version = 0

    private const val PREF = "usage"

    lateinit var appCtx: Context

    fun load(ctx: Context) {
        appCtx = ctx.applicationContext
        try {
            val o = JSONObject(ctx.getSharedPreferences(PREF, 0).getString("data", "{}") ?: "{}")
            o.keys().forEach { k ->
                val x = o.getJSONObject(k)
                map[k] = Info(x.optInt("s", -1), x.optString("sr"), x.optInt("w", -1), x.optString("wr"), x.optLong("at"), x.optString("err"))
            }
        } catch (_: Exception) { }
    }

    fun put(ctx: Context, tabId: String, info: Info) {
        // se esta leitura falhou mas já havia uma boa, mantém a boa e só anota o erro
        val old = map[tabId]
        map[tabId] = if (info.err.isNotEmpty() && old != null && old.err.isEmpty())
            Info(old.s, old.sr, old.w, old.wr, old.at, "") else info
        version++
        try {
            val o = JSONObject()
            map.forEach { (k, v) -> o.put(k, JSONObject().put("s", v.s).put("sr", v.sr).put("w", v.w).put("wr", v.wr).put("at", v.at).put("err", v.err)) }
            ctx.getSharedPreferences(PREF, 0).edit().putString("data", o.toString()).apply()
        } catch (_: Exception) { }
    }

    /** Texto curto para a etiqueta da aba, ex.: " · 42%". Vazio se não há leitura recente. */
    fun chip(tabId: String): String {

        val i = map[tabId]
        if (i != null && i.err.isEmpty() && i.s >= 0 && System.currentTimeMillis() - i.at <= 20 * 60_000L) return " · ${i.s}%"
        if (!::appCtx.isInitialized || !Est.known(tabId)) return ""
        return " · ~${Est.percent(appCtx, tabId)}%"
    }

    /** Há leitura real (da página de uso) recente? */
    fun hasReal(tabId: String): Boolean {
        val i = map[tabId] ?: return false
        return i.err.isEmpty() && (i.s >= 0 || i.w >= 0)
    }

    const val JS = """(function(){try{
var t=(document.body&&document.body.innerText)||'';
var L=t.split('\n').map(function(x){return x.trim();}).filter(function(x){return x;});
function find(re){for(var i=0;i<L.length;i++){if(re.test(L[i])){var p=-1,r='';
for(var j=i;j<Math.min(i+8,L.length);j++){var m=/(\d{1,3})\s*%/.exec(L[j]);if(m&&p<0)p=parseInt(m[1],10);
if(/reset|reinici|redefin|renov/i.test(L[j])&&!r)r=L[j];}
if(p>=0)return {p:p,r:r};}}return null;}
var s=find(/current session|sess[ãa]o atual|sesi[óo]n actual/i);
var w=find(/all models|todos os modelos|weekly|semanal|esta semana|this week/i);
return JSON.stringify({ok:!!s||!!w,s:s?s.p:-1,sr:s?s.r:'',w:w?w.p:-1,wr:w?w.r:'',u:location.href});
}catch(e){return JSON.stringify({ok:false,u:''});}})();"""
}

/** Lê o uso de cada conta com uma página escondida que usa o mesmo login da aba. */
class UsageMonitor(private val a: MainActivity) {
    val ui = UsageUi(a, this)
    private val busy = java.util.Collections.synchronizedSet(HashSet<String>())

    init { Usage.load(a) }

    fun isBusy(tabId: String) = busy.contains(tabId)

    fun refresh(t: Tab, done: () -> Unit = {}) {
        if (!busy.add(t.id)) { done(); return }
        var finished = false
        val w: WebView? = try { a.scrProbe(t) } catch (_: Exception) { null }
        if (w == null) {
            busy.remove(t.id)
            Usage.put(a, t.id, Usage.Info(err = "Não consegui abrir a leitura"))
            done()
            return
        }
        fun end(info: Usage.Info) {
            if (finished) return
            finished = true
            Usage.put(a, t.id, info)
            a.scrProbeDone(w)
            busy.remove(t.id)
            done()
        }
        var tries = 0
        fun poll() {
            if (finished) return
            w.evaluateJavascript(Usage.JS) { raw ->
                if (finished) return@evaluateJavascript
                val o = try { JSONObject(Handoff.unquote(raw) ?: "{}") } catch (_: Exception) { JSONObject() }
                tries++
                val s = o.optInt("s", -1)
                val wk = o.optInt("w", -1)
                if (o.optBoolean("ok") && (wk >= 0 || tries >= 6)) {
                    end(Usage.Info(s, o.optString("sr"), wk, o.optString("wr")))
                } else if (tries >= 25) {
                    val login = o.optString("u").contains("login") || o.optString("u").contains("logout")
                    end(Usage.Info(err = if (login) "Conta não está logada" else "Não achei o uso na página"))
                } else {
                    w.postDelayed({ poll() }, 1000)
                }
            }
        }
        w.loadUrl("https://claude.ai/settings/usage")
        w.postDelayed({ poll() }, 2500)
    }

    /** Lê uma conta de cada vez, para não pesar. */
    fun refreshAll(force: Boolean = false, done: () -> Unit = {}) {
        val now = System.currentTimeMillis()
        // conta sem a página de uso (free): não fica tentando a cada minuto
        val list = a.scrTabs().filter { t ->
            val i = Usage.map[t.id]
            force || i == null || i.err.isEmpty() || now - i.at > 30 * 60_000L
        }
        fun step(i: Int) {
            if (i >= list.size) { done(); return }
            refresh(list[i]) { a.runOnUiThread { step(i + 1) } }
        }
        step(0)
    }
}

/** Painel "Uso das contas": cada aba com barra de 0 a 100%, atualizando sozinho. */
class UsageUi(private val a: MainActivity, private val mon: UsageMonitor) {

    private fun colorFor(p: Int) = when {
        p >= 90 -> Color.parseColor("#FF6B6B")
        p >= 70 -> Color.parseColor("#FBBF24")
        else -> Color.parseColor("#4ADE80")
    }

    private fun ago(ms: Long): String {
        val s = ((System.currentTimeMillis() - ms) / 1000).coerceAtLeast(0)
        return if (s < 60) "há ${s}s" else "há ${s / 60} min"
    }

    private fun bar(box: LinearLayout, label: String, pct: Int, reset: String) {
        val dp = { v: Int -> Ui.dp(a, v) }
        val row = TextView(a).apply {
            text = if (pct >= 0) "$label: $pct%" else "$label: —"
            textSize = 13f
            setTextColor(Ui.color(a, R.color.text))
        }
        box.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val pb = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = pct.coerceIn(0, 100)
            progressTintList = ColorStateList.valueOf(if (pct >= 0) colorFor(pct) else Color.GRAY)
        }
        box.addView(pb, LinearLayout.LayoutParams(-1, dp(10)).apply { topMargin = dp(4) })
        if (reset.isNotBlank()) {
            box.addView(TextView(a).apply {
                text = reset
                textSize = 11.5f
                setTextColor(Ui.color(a, R.color.muted))
            })
        }
    }

    private fun render(box: LinearLayout) {
        box.removeAllViews()
        val dp = { v: Int -> Ui.dp(a, v) }
        val now = System.currentTimeMillis()
        val tabs = a.scrTabs()
        if (tabs.isEmpty()) {
            box.addView(TextView(a).apply { text = "Nenhuma aba aberta."; setTextColor(Ui.color(a, R.color.muted)) })
            return
        }
        tabs.forEach { t ->
            val info = Usage.map[t.id]
            val card = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = Ui.shape(a, Ui.color(a, R.color.surface2), 16)
            }
            val limited = t.until > now
            card.addView(TextView(a).apply {
                text = t.name + if (limited) "  ·  em limite (volta ${a.scrReturnLabel(t.until)})" else ""
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (limited) Color.parseColor("#E0A58F") else Ui.color(a, R.color.text))
            })
            if (info != null && Usage.hasReal(t.id)) {
                bar(card, "Sessão (5 h)", info.s, info.sr)
                bar(card, "Semana", info.w, info.wr)
                card.addView(TextView(a).apply {
                    text = (if (mon.isBusy(t.id)) "Atualizando… " else "") + "Lido ${ago(info.at)}" +
                        (if (info.err.isNotEmpty()) " · última tentativa falhou: ${info.err}" else "")
                    textSize = 11f
                    setTextColor(Ui.color(a, R.color.muted))
                    setPadding(0, dp(6), 0, 0)
                })
            } else {
                // conta free: sem barra de uso na página → estimativa que aprende sozinha
                val pct = if (limited) 100 else if (Est.known(t.id)) Est.percent(a, t.id) else -1
                bar(card, "Sessão (estimada)", pct, "")
                val n = Est.learned(t.id)
                val g = Est.resetGuess(t.id)
                val parts = ArrayList<String>()
                if (g > now && !limited) parts.add("janela reinicia ~${a.scrReturnLabel(g)}")
                parts.add(if (n == 0) "ainda sem calibrar (chute inicial)" else "calibrada $n× pelos limites")
                card.addView(TextView(a).apply {
                    text = parts.joinToString(" · ") + "\nToque para corrigir"
                    textSize = 11.5f
                    setTextColor(Ui.color(a, R.color.muted))
                    setPadding(0, dp(4), 0, 0)
                })
                card.setOnClickListener { recalibrate(t) }
            }
            box.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
    }

    private fun recalibrate(t: Tab) {
        val opts = arrayOf("Está em 25%", "Está em 50%", "Está em 75%", "Está em 90%", "Zerar (janela nova agora)", "Esquecer estimativa")
        MaterialAlertDialogBuilder(a)
            .setTitle(t.name)
            .setItems(opts) { _, w ->
                when (w) {
                    0 -> Est.setPercent(a, t.id, 25)
                    1 -> Est.setPercent(a, t.id, 50)
                    2 -> Est.setPercent(a, t.id, 75)
                    3 -> Est.setPercent(a, t.id, 90)
                    4 -> Est.zero(a, t.id)
                    5 -> Est.forget(a, t.id)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    fun show() {
        val pad = Ui.dp(a, 16)
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val sc = ScrollView(a).apply {
            setPadding(pad, Ui.dp(a, 8), pad, 0)
            addView(box)
        }
        val d = MaterialAlertDialogBuilder(a)
            .setTitle("Uso das contas")
            .setView(sc)
            .setPositiveButton("Fechar", null)
            .setNeutralButton("Atualizar", null)
            .create()
        val handler = a.scrHandler()
        var next = 0L
        val tick = object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                if (now >= next) { next = now + 60_000L; mon.refreshAll { } }
                render(box)
                handler.postDelayed(this, 1000)
            }
        }
        d.setOnDismissListener { handler.removeCallbacks(tick) }
        d.show()
        d.getButton(DialogInterface.BUTTON_NEUTRAL)?.setOnClickListener {
            next = System.currentTimeMillis() + 60_000L
            mon.refreshAll(true) { }
        }
        tick.run()
    }
}
