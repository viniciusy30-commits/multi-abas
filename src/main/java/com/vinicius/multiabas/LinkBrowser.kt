package com.vinicius.multiabas

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.MutableContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Environment
import android.os.Message
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** Uma aba do navegador flutuante. */
class LTab(val id: String, var title: String, var url: String) {
    var used: Long = System.currentTimeMillis()
}

/**
 * Estado do navegador flutuante. Vive no processo (não na tela), então as abas continuam abertas
 * mesmo que a tela do app seja fechada e reaberta enquanto o app segue rodando em segundo plano.
 * A lista de abas também é salva no aparelho e volta quando o app abre de novo.
 */
object LinkStore {
    val tabs = mutableListOf<LTab>()
    val webs = HashMap<String, WebView>()
    var cur = 0
    var loaded = false
}

class LinkBrowser(private val act: MainActivity) {
    private val app: Context = act.applicationContext
    private val prefs = act.getSharedPreferences("linkbrowser", 0)
    private val root: ViewGroup = act.findViewById(android.R.id.content)
    private val tabs get() = LinkStore.tabs
    private val webs get() = LinkStore.webs

    private val cBg = Ui.color(act, R.color.bg)
    private val cSurface = Ui.color(act, R.color.surface)
    private val cSurface2 = Ui.color(act, R.color.surface2)
    private val cOutline = Ui.color(act, R.color.outline)
    private val cAccent = Ui.color(act, R.color.accent)
    private val cText = Ui.color(act, R.color.text)
    private val cMuted = Ui.color(act, R.color.muted)

    private lateinit var bubble: FrameLayout
    private lateinit var badge: TextView
    private lateinit var panel: FrameLayout
    private lateinit var host: FrameLayout
    private lateinit var urlField: EditText
    private lateinit var progress: ProgressBar
    private lateinit var countBox: TextView
    private lateinit var tabsView: FrameLayout
    private lateinit var tabsList: LinearLayout
    private lateinit var tabsTitle: TextView
    private var panelOpen = false
    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null

    init {
        if (!LinkStore.loaded) {
            LinkStore.loaded = true
            try {
                val a = JSONArray(prefs.getString("tabs", "[]"))
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    tabs.add(LTab(o.getString("id"), o.optString("title", ""), o.optString("url", "")))
                }
            } catch (_: Exception) { }
            LinkStore.cur = prefs.getInt("cur", 0)
        }
        LinkStore.cur = LinkStore.cur.coerceIn(0, maxOf(0, tabs.size - 1))
        buildBubble()
        buildPanel()
        refreshBubble()
    }

    // ───────────────────────── regras de quais links vêm para cá ─────────────────────────

    // ───────────────────────── interface pública ─────────────────────────

    /** Abre o link numa aba nova do navegador flutuante e mostra o painel. */
    fun open(url: String) {
        val t = LTab(newId(), "", url)
        tabs.add(t)
        show(tabs.size - 1)
        openPanel(false)
    }

    /** Abre o painel (se não houver aba nenhuma, cria uma em branco). */
    fun openPanel(createIfEmpty: Boolean = true) {
        if (tabs.isEmpty() && createIfEmpty) {
            tabs.add(LTab(newId(), "", ""))
            show(0)
        }
        if (tabs.isEmpty()) return
        if (host.childCount == 0) show(LinkStore.cur)
        panelOpen = true
        panel.alpha = 0f
        panel.visibility = View.VISIBLE
        panel.animate().alpha(1f).setDuration(150).start()
        refreshBubble()
    }

    private fun minimize() {
        hideKeyboard()
        panelOpen = false
        panel.visibility = View.GONE
        hideTabs()
        refreshBubble()
    }

    /** Botão voltar do celular. Devolve true se o navegador flutuante tratou. */
    fun onBack(): Boolean {
        if (customView != null) { hideCustom(); return true }
        if (!panelOpen) return false
        if (tabsView.visibility == View.VISIBLE) { hideTabs(); return true }
        val w = currentWeb()
        if (w != null && w.canGoBack()) w.goBack() else minimize()
        return true
    }

    /** A tela do app vai ser destruída: solta as WebViews (elas continuam vivas) e tira as views. */
    fun detach() {
        try { host.removeAllViews() } catch (_: Exception) { }
        for (w in webs.values) (w.context as? MutableContextWrapper)?.setBaseContext(app)
        try { root.removeView(bubble); root.removeView(panel) } catch (_: Exception) { }
    }

    // ───────────────────────── abas ─────────────────────────

    private fun newId() = "l" + System.currentTimeMillis() + (Math.random() * 1000).toInt()

    private fun currentTab(): LTab? = tabs.getOrNull(LinkStore.cur)
    private fun currentWeb(): WebView? = currentTab()?.let { webs[it.id] }
    private fun tabOf(v: WebView): LTab? = tabs.firstOrNull { it.id == v.tag }

    private fun show(i: Int) {
        if (i < 0 || i >= tabs.size) return
        LinkStore.cur = i
        val t = tabs[i]
        t.used = System.currentTimeMillis()
        val w = webFor(t)
        host.removeAllViews()
        (w.parent as? ViewGroup)?.removeView(w)
        (w.context as? MutableContextWrapper)?.setBaseContext(act)
        host.addView(w, FrameLayout.LayoutParams(-1, -1))
        progress.visibility = View.GONE
        updateUi()
        save()
        trimWebs()
    }

    private fun closeTab(i: Int) {
        if (i < 0 || i >= tabs.size) return
        val t = tabs.removeAt(i)
        webs.remove(t.id)?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            try { it.stopLoading(); it.destroy() } catch (_: Exception) { }
        }
        if (tabs.isEmpty()) {
            LinkStore.cur = 0
            host.removeAllViews()
            save()
            minimize()
            return
        }
        val next = if (i < LinkStore.cur) LinkStore.cur - 1 else minOf(LinkStore.cur, tabs.size - 1)
        show(next)
        if (tabsView.visibility == View.VISIBLE) renderTabs()
    }

    private fun closeAll() {
        tabs.toList().forEach { t ->
            webs.remove(t.id)?.let { (it.parent as? ViewGroup)?.removeView(it); try { it.destroy() } catch (_: Exception) { } }
        }
        tabs.clear()
        LinkStore.cur = 0
        host.removeAllViews()
        save()
        minimize()
    }

    /** Mantém no máximo 6 páginas carregadas; as mais antigas voltam a carregar quando abertas. */
    private fun trimWebs() {
        val live = tabs.filter { webs.containsKey(it.id) && it.id != currentTab()?.id }.sortedBy { it.used }
        var extra = webs.size - 6
        for (t in live) {
            if (extra <= 0) break
            webs.remove(t.id)?.let {
                (it.parent as? ViewGroup)?.removeView(it)
                try { it.stopLoading(); it.destroy() } catch (_: Exception) { }
            }
            extra--
        }
    }

    private fun save() {
        val a = JSONArray()
        tabs.forEach { a.put(JSONObject().put("id", it.id).put("title", it.title).put("url", it.url)) }
        prefs.edit().putString("tabs", a.toString()).putInt("cur", LinkStore.cur).apply()
    }

    // ───────────────────────── WebView ─────────────────────────

    private val START_HTML = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'>" +
        "<style>body{background:#0D0F13;color:#ECEEF3;font-family:sans-serif;display:flex;flex-direction:column;" +
        "align-items:center;justify-content:center;height:80vh;margin:0}input{width:82%;max-width:420px;padding:14px 18px;" +
        "border-radius:26px;border:1px solid #2B303B;background:#1F232C;color:#ECEEF3;font-size:16px;outline:none}</style></head>" +
        "<body><div style='font-size:44px'>🌐</div><p style='color:#8B92A1'>Pesquise ou digite um endereço</p>" +
        "<form action='https://www.google.com/search' method='get'><input name='q' placeholder='Pesquisar no Google'></form></body></html>"

    private fun webFor(t: LTab): WebView = webs.getOrPut(t.id) { makeWeb(t, true) }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun makeWeb(t: LTab, load: Boolean): WebView {
        val w = WebView(MutableContextWrapper(app))
        w.tag = t.id
        w.setBackgroundColor(Color.parseColor("#0D0F13"))
        val s = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = false
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.setSupportMultipleWindows(true)
        s.javaScriptCanOpenWindowsAutomatically = true
        s.mediaPlaybackRequiresUserGesture = false
        s.userAgentString = s.userAgentString.replace("; wv", "").replace(Regex("Version/\\d+\\.\\d+ "), "")
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val sc = r.url.scheme ?: ""
                if (sc == "http" || sc == "https" || sc == "about" || sc == "data" || sc == "blob") return false
                try {
                    val it = if (sc == "intent") Intent.parseUri(r.url.toString(), Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, r.url)
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    act.startActivity(it)
                } catch (_: Exception) { }
                return true
            }
            override fun onPageStarted(v: WebView, u: String?, f: Bitmap?) { upd(v, u) }
            override fun onPageFinished(v: WebView, u: String?) { upd(v, u) }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView, p: Int) {
                if (v.tag == currentTab()?.id) {
                    progress.progress = p
                    progress.visibility = if (p < 100) View.VISIBLE else View.GONE
                }
            }
            override fun onReceivedTitle(v: WebView, title: String?) {
                val tb = tabOf(v) ?: return
                if (!title.isNullOrBlank() && !title.startsWith("data:")) { tb.title = title; save(); if (tabsView.visibility == View.VISIBLE) renderTabs() }
            }
            override fun onCreateWindow(v: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                val nt = LTab(newId(), "", "")
                tabs.add(nt)
                val nw = makeWeb(nt, false)
                webs[nt.id] = nw
                val tr = resultMsg.obj as WebView.WebViewTransport
                tr.webView = nw
                resultMsg.sendToTarget()
                show(tabs.size - 1)
                return true
            }
            override fun onCloseWindow(v: WebView) {
                val i = tabs.indexOfFirst { it.id == v.tag }
                if (i >= 0) closeTab(i)
            }
            override fun onShowFileChooser(v: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean =
                act.browserPick(cb, p)
            override fun onShowCustomView(view: View, cb: CustomViewCallback) {
                if (customView != null) { cb.onCustomViewHidden(); return }
                customView = view; customCb = cb
                view.setBackgroundColor(Color.BLACK)
                panel.addView(view, FrameLayout.LayoutParams(-1, -1))
            }
            override fun onHideCustomView() { hideCustom() }
        }
        w.setDownloadListener { u, ua, cd, mime, _ ->
            if (u.startsWith("blob:") || u.startsWith("data:")) {
                Toast.makeText(act, "Esse tipo de download não é suportado aqui", Toast.LENGTH_LONG).show()
                return@setDownloadListener
            }
            try {
                val name = URLUtil.guessFileName(u, cd, mime)
                val req = DownloadManager.Request(Uri.parse(u)).apply {
                    addRequestHeader("Cookie", CookieManager.getInstance().getCookie(u) ?: "")
                    addRequestHeader("User-Agent", ua)
                    setMimeType(mime)
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                }
                (act.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                Toast.makeText(act, "Baixando $name", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(act, "Não consegui baixar: " + (e.message ?: ""), Toast.LENGTH_LONG).show()
            }
        }
        if (load) {
            if (t.url.isBlank()) w.loadData(START_HTML, "text/html", "utf-8") else w.loadUrl(t.url)
        }
        return w
    }

    private fun hideCustom() {
        val v = customView ?: return
        panel.removeView(v)
        customView = null
        customCb?.onCustomViewHidden()
        customCb = null
    }

    private fun upd(v: WebView, u: String?) {
        val tb = tabOf(v) ?: return
        if (u == null || u.startsWith("data:") || u.startsWith("about:")) return
        tb.url = u
        if (tb.id == currentTab()?.id) {
            if (!urlField.hasFocus()) urlField.setText(shown(u))
            updateNavButtons()
        }
        save()
    }

    // ───────────────────────── bolinha ─────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun buildBubble() {
        val size = Ui.dp(act, 58)
        bubble = FrameLayout(act)
        val ball = TextView(act).apply {
            text = "🌐"
            textSize = 26f
            gravity = Gravity.CENTER
            background = Ui.shape(act, cAccent, 0, 0x55FFFFFF, 2, oval = true)
        }
        bubble.addView(ball, FrameLayout.LayoutParams(size, size))
        badge = TextView(act).apply {
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = Ui.shape(act, Color.parseColor("#1F232C"), 0, cAccent, 1, oval = true)
        }
        bubble.addView(badge, FrameLayout.LayoutParams(Ui.dp(act, 22), Ui.dp(act, 22), Gravity.TOP or Gravity.END))
        bubble.elevation = Ui.dp(act, 12).toFloat()
        bubble.visibility = View.GONE
        root.addView(bubble, FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.START))

        root.post {
            val bx = prefs.getFloat("bx", -1f)
            val by = prefs.getFloat("by", -1f)
            bubble.x = if (bx >= 0f) bx else (root.width - size - Ui.dp(act, 6)).toFloat()
            bubble.y = if (by >= 0f) by else root.height * 0.55f
            clampBubble()
        }

        var dx = 0f; var dy = 0f; var sx = 0f; var sy = 0f; var moved = false
        val slop = Ui.dp(act, 8)
        bubble.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dx = v.x - e.rawX; dy = v.y - e.rawY; sx = e.rawX; sy = e.rawY; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.rawX - sx) > slop || abs(e.rawY - sy) > slop) moved = true
                    if (moved) { v.x = e.rawX + dx; v.y = e.rawY + dy; clampBubble() }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) openPanel() else snapBubble()
                    true
                }
                else -> false
            }
        }
    }

    private fun clampBubble() {
        val maxX = (root.width - bubble.width).toFloat().coerceAtLeast(0f)
        val maxY = (root.height - bubble.height).toFloat().coerceAtLeast(0f)
        bubble.x = bubble.x.coerceIn(0f, maxX)
        bubble.y = bubble.y.coerceIn(0f, maxY)
    }

    private fun snapBubble() {
        val margin = Ui.dp(act, 6).toFloat()
        val target = if (bubble.x + bubble.width / 2f < root.width / 2f) margin else root.width - bubble.width - margin
        bubble.animate().x(target).setDuration(160).withEndAction {
            prefs.edit().putFloat("bx", bubble.x).putFloat("by", bubble.y).apply()
        }.start()
    }

    private fun refreshBubble() {
        badge.text = tabs.size.toString()
        bubble.visibility = if (tabs.isNotEmpty() && !panelOpen) View.VISIBLE else View.GONE
        if (::countBox.isInitialized) countBox.text = tabs.size.toString()
    }

    // ───────────────────────── painel ─────────────────────────

    private fun btn(label: String, sizeSp: Float = 20f, onClick: (View) -> Unit): TextView = TextView(act).apply {
        text = label
        textSize = sizeSp
        gravity = Gravity.CENTER
        setTextColor(cText)
        val tv = TypedValue()
        act.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
        setBackgroundResource(tv.resourceId)
        setOnClickListener(onClick)
    }

    private fun buildPanel() {
        panel = FrameLayout(act).apply { setBackgroundColor(cBg); visibility = View.GONE; isClickable = true }
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }

        // topo: minimizar · endereço · menu
        val top = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(act, 6), Ui.dp(act, 6), Ui.dp(act, 6), Ui.dp(act, 6))
            setBackgroundColor(cSurface)
        }
        top.addView(btn("▾", 22f) { minimize() }.apply { contentDescription = "Minimizar para a bolinha" }, LinearLayout.LayoutParams(Ui.dp(act, 44), Ui.dp(act, 44)))
        urlField = EditText(act).apply {
            setSingleLine()
            textSize = 14f
            setTextColor(cText)
            setHintTextColor(cMuted)
            hint = "Pesquisar ou digitar endereço"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            background = Ui.shape(act, cSurface2, 22)
            setPadding(Ui.dp(act, 16), 0, Ui.dp(act, 12), 0)
            setOnFocusChangeListener { _, has ->
                val u = currentTab()?.url ?: ""
                if (has) { setText(u); selectAll() } else setText(shown(u))
            }
            setOnEditorActionListener { v, id, _ ->
                if (id == EditorInfo.IME_ACTION_GO) { go(v.text.toString()); true } else false
            }
        }
        top.addView(urlField, LinearLayout.LayoutParams(0, Ui.dp(act, 42), 1f))
        top.addView(btn("⋮", 22f) { showMenu(it) }.apply { contentDescription = "Menu" }, LinearLayout.LayoutParams(Ui.dp(act, 44), Ui.dp(act, 44)))
        col.addView(top, LinearLayout.LayoutParams(-1, -2))

        progress = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply {
            progressTintList = android.content.res.ColorStateList.valueOf(cAccent)
            visibility = View.GONE
        }
        col.addView(progress, LinearLayout.LayoutParams(-1, Ui.dp(act, 2)))

        host = FrameLayout(act)
        col.addView(host, LinearLayout.LayoutParams(-1, 0, 1f))

        // rodapé: voltar · avançar · recarregar · nova aba · todas as abas
        val nav = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(cSurface)
            setPadding(Ui.dp(act, 8), Ui.dp(act, 4), Ui.dp(act, 8), Ui.dp(act, 4))
        }
        fun navBtn(v: View) = nav.addView(v, LinearLayout.LayoutParams(0, Ui.dp(act, 48), 1f))
        navBtn(btn("‹", 30f) { currentWeb()?.let { if (it.canGoBack()) it.goBack() } }.also { it.tag = "back"; it.contentDescription = "Voltar" })
        navBtn(btn("›", 30f) { currentWeb()?.let { if (it.canGoForward()) it.goForward() } }.also { it.tag = "fwd"; it.contentDescription = "Avançar" })
        navBtn(btn("⟳", 22f) { currentWeb()?.reload() }.apply { contentDescription = "Recarregar" })
        navBtn(btn("＋", 24f) { newBlankTab() }.apply { contentDescription = "Nova aba" })
        countBox = TextView(act).apply {
            gravity = Gravity.CENTER
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(cText)
            background = Ui.shape(act, 0, 6, cText, 2)
            setOnClickListener { showTabs() }
            contentDescription = "Todas as abas"
        }
        val cbWrap = FrameLayout(act)
        cbWrap.addView(countBox, FrameLayout.LayoutParams(Ui.dp(act, 28), Ui.dp(act, 28), Gravity.CENTER))
        cbWrap.setOnClickListener { showTabs() }
        navBtn(cbWrap)
        col.addView(nav, LinearLayout.LayoutParams(-1, -2))
        navButtons = nav

        panel.addView(col, FrameLayout.LayoutParams(-1, -1))

        // tela cheia com todas as abas
        tabsView = FrameLayout(act).apply { setBackgroundColor(cBg); visibility = View.GONE; isClickable = true }
        val tcol = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val th = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(cSurface)
            setPadding(Ui.dp(act, 6), Ui.dp(act, 6), Ui.dp(act, 10), Ui.dp(act, 6))
        }
        th.addView(btn("←", 22f) { hideTabs() }.apply { contentDescription = "Voltar" }, LinearLayout.LayoutParams(Ui.dp(act, 44), Ui.dp(act, 44)))
        tabsTitle = TextView(act).apply { textSize = 18f; setTypeface(typeface, Typeface.BOLD); setTextColor(cText) }
        th.addView(tabsTitle, LinearLayout.LayoutParams(0, -2, 1f))
        th.addView(btn("Fechar todas", 13f) { closeAll() }.apply { setTextColor(Ui.color(act, R.color.danger)); setPadding(Ui.dp(act, 10), 0, Ui.dp(act, 10), 0) }, LinearLayout.LayoutParams(-2, Ui.dp(act, 44)))
        th.addView(btn("＋ Nova", 14f) { newBlankTab() }.apply { setTextColor(cAccent); setPadding(Ui.dp(act, 10), 0, Ui.dp(act, 4), 0) }, LinearLayout.LayoutParams(-2, Ui.dp(act, 44)))
        tcol.addView(th, LinearLayout.LayoutParams(-1, -2))
        val sv = ScrollView(act)
        tabsList = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(act, 8), Ui.dp(act, 8), Ui.dp(act, 8), Ui.dp(act, 24)) }
        sv.addView(tabsList, ViewGroup.LayoutParams(-1, -2))
        tcol.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        tabsView.addView(tcol, FrameLayout.LayoutParams(-1, -1))
        panel.addView(tabsView, FrameLayout.LayoutParams(-1, -1))

        root.addView(panel, FrameLayout.LayoutParams(-1, -1))
    }

    private lateinit var navButtons: LinearLayout

    private fun updateNavButtons() {
        val w = currentWeb()
        navButtons.findViewWithTag<TextView>("back")?.alpha = if (w?.canGoBack() == true) 1f else 0.35f
        navButtons.findViewWithTag<TextView>("fwd")?.alpha = if (w?.canGoForward() == true) 1f else 0.35f
    }

    private fun updateUi() {
        val t = currentTab()
        if (!urlField.hasFocus()) urlField.setText(shown(t?.url ?: ""))
        countBox.text = tabs.size.toString()
        updateNavButtons()
        refreshBubble()
    }

    private fun shown(u: String): String = u.removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')

    private fun hideKeyboard() {
        try {
            urlField.clearFocus()
            (act.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(panel.windowToken, 0)
        } catch (_: Exception) { }
    }

    private fun normalize(input: String): String {
        val s = input.trim()
        if (s.isEmpty()) return ""
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://.*").matches(s)) return s
        if (!s.contains(' ') && (s.contains('.') || s.startsWith("localhost"))) return "https://$s"
        return "https://www.google.com/search?q=" + Uri.encode(s)
    }

    private fun go(text: String) {
        val u = normalize(text)
        hideKeyboard()
        if (u.isEmpty()) return
        val t = currentTab() ?: run { open(u); return }
        t.url = u
        webFor(t).loadUrl(u)
        save()
    }

    private fun newBlankTab() {
        tabs.add(LTab(newId(), "", ""))
        show(tabs.size - 1)
        hideTabs()
        urlField.requestFocus()
        (act.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(urlField, 0)
    }

    private fun showMenu(anchor: View) {
        val pm = PopupMenu(act, anchor)
        pm.menu.add(0, 1, 0, "Abrir no Chrome")
        pm.menu.add(0, 2, 1, "Copiar link")
        pm.menu.add(0, 3, 2, "Fechar esta aba")
        pm.menu.add(0, 4, 3, "Fechar todas as abas")
        pm.setOnMenuItemClickListener { m ->
            val t = currentTab()
            when (m.itemId) {
                1 -> if (t != null && t.url.isNotBlank()) try {
                    androidx.browser.customtabs.CustomTabsIntent.Builder().build().launchUrl(act, Uri.parse(t.url))
                } catch (_: Exception) { }
                2 -> if (t != null && t.url.isNotBlank()) {
                    (act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("link", t.url))
                    Toast.makeText(act, "Link copiado", Toast.LENGTH_SHORT).show()
                }
                3 -> closeTab(LinkStore.cur)
                4 -> closeAll()
            }
            true
        }
        pm.show()
    }

    // ───────────────────────── tela cheia de abas ─────────────────────────

    private fun showTabs() {
        hideKeyboard()
        renderTabs()
        tabsView.visibility = View.VISIBLE
    }

    private fun hideTabs() { tabsView.visibility = View.GONE }

    private fun renderTabs() {
        tabsTitle.text = "Abas (${tabs.size})"
        tabsList.removeAllViews()
        var row: LinearLayout? = null
        tabs.forEachIndexed { i, t ->
            if (i % 2 == 0) {
                row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
                tabsList.addView(row, LinearLayout.LayoutParams(-1, -2))
            }
            val isCur = i == LinkStore.cur
            val card = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                background = Ui.shape(act, cSurface, 14, if (isCur) cAccent else cOutline, if (isCur) 2 else 1)
                setPadding(Ui.dp(act, 12), Ui.dp(act, 8), Ui.dp(act, 8), Ui.dp(act, 12))
                setOnClickListener { show(i); hideTabs() }
            }
            val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(TextView(act).apply {
                text = t.title.ifBlank { shown(t.url).ifBlank { "Nova aba" } }
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(cText)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(btn("✕", 16f) { closeTab(i) }.apply { setTextColor(cMuted); contentDescription = "Fechar aba" }, LinearLayout.LayoutParams(Ui.dp(act, 36), Ui.dp(act, 36)))
            card.addView(head, LinearLayout.LayoutParams(-1, -2))
            card.addView(TextView(act).apply {
                text = shown(t.url).ifBlank { "Página inicial" }
                textSize = 12f
                setTextColor(cMuted)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, Ui.dp(act, 6), 0, 0)
            }, LinearLayout.LayoutParams(-1, -2))
            card.minimumHeight = Ui.dp(act, 110)
            row!!.addView(card, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(Ui.dp(act, 4), Ui.dp(act, 4), Ui.dp(act, 4), Ui.dp(act, 4)) })
        }
        if (tabs.size % 2 == 1) row?.addView(View(act), LinearLayout.LayoutParams(0, 1, 1f).apply { setMargins(Ui.dp(act, 4), 0, Ui.dp(act, 4), 0) })
    }
}
