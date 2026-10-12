package com.vinicius.multiabas

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.*
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject

class Tab(val id: String, var name: String, var url: String, var until: Long = 0L)

class MainActivity : AppCompatActivity() {
    private val HOME = "https://claude.ai"
    private val tabs = mutableListOf<Tab>()
    private var cur = 0
    private val webs = HashMap<String, WebView>()
    private lateinit var container: FrameLayout
    private lateinit var urlBar: EditText
    private lateinit var progress: ProgressBar
    private lateinit var rv: RecyclerView
    private val adapter = TabAdapter()
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private val profilesOk by lazy { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }
    private val prefs by lazy { getSharedPreferences("tabs", 0) }
    private class Fav(var name: String, var url: String, var mode: Int = 0)
    private val favs = mutableListOf<Fav>()
    private val favAdapter = FavAdapter()
    private var favWeb: WebView? = null
    private lateinit var overlay: View
    private lateinit var favHost: FrameLayout
    private lateinit var favTitle: TextView
    private lateinit var favProgress: ProgressBar

    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        fileCb = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        container = findViewById(R.id.container)
        urlBar = findViewById(R.id.urlBar)
        progress = findViewById(R.id.progress)
        rv = findViewById(R.id.rvTabs)
        rv.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rv.adapter = adapter
        overlay = findViewById(R.id.overlay)
        favHost = findViewById(R.id.favHost)
        favTitle = findViewById(R.id.favTitle)
        favProgress = findViewById(R.id.favProgress)
        findViewById<ImageButton>(R.id.btnFavClose).setOnClickListener { closeFav() }
        val rf = findViewById<RecyclerView>(R.id.rvFavs)
        rf.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rf.adapter = favAdapter
        findViewById<ImageButton>(R.id.btnFavAdd).setOnClickListener { editFav(-1) }
        loadFavs()
        findViewById<ImageButton>(R.id.btnAdd).setOnClickListener { addTab() }
        findViewById<ImageButton>(R.id.btnMenu).setOnClickListener { showMenu(it) }
        urlBar.setOnEditorActionListener { v, id, _ ->
            if (id == EditorInfo.IME_ACTION_GO) { go(v.text.toString()); true } else false
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val fw = favWeb
                if (fw != null) { if (fw.canGoBack()) fw.goBack() else closeFav(); return }
                val w = webs[tabs[cur].id]
                if (w != null && w.canGoBack()) w.goBack() else finish()
            }
        })
        load()
        if (!profilesOk) Toast.makeText(this, "Seu WebView não separa contas. Atualize o \"Android System WebView\" na Play Store.", Toast.LENGTH_LONG).show()
        select(cur)
        Updater.autoCheck(this)
        Novidades.show(this, false)
    }

    private fun newTab(name: String) = Tab(System.currentTimeMillis().toString(), name, HOME)

    private fun load() {
        try {
            val a = JSONArray(prefs.getString("tabs", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                tabs.add(Tab(o.getString("id"), o.getString("name"), o.getString("url"), o.optLong("until", 0L)))
            }
        } catch (e: Exception) { }
        if (tabs.isEmpty()) tabs.add(newTab("Conta 1"))
        cur = prefs.getInt("cur", 0).coerceIn(0, tabs.size - 1)
    }

    private fun save() {
        val a = JSONArray()
        tabs.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("url", it.url).put("until", it.until)) }
        prefs.edit().putString("tabs", a.toString()).putInt("cur", cur).apply()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun webFor(t: Tab): WebView = webs.getOrPut(t.id) {
        val w = WebView(this)
        w.tag = t.id
        if (profilesOk) { try { WebViewCompat.setProfile(w, "p_" + t.id) } catch (e: Exception) { } }
        w.layoutParams = ViewGroup.LayoutParams(-1, -1)
        w.setBackgroundColor(Color.parseColor("#0F1115"))
        val s = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = false
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.userAgentString = s.userAgentString.replace("; wv", "").replace(Regex("Version/\\d+\\.\\d+ "), "")
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val sc = r.url.scheme ?: ""
                if (sc == "http" || sc == "https") return false
                try { startActivity(Intent(Intent.ACTION_VIEW, r.url)) } catch (e: Exception) { }
                return true
            }
            override fun onPageStarted(v: WebView, u: String?, f: Bitmap?) { upd(v, u) }
            override fun onPageFinished(v: WebView, u: String?) { upd(v, u) }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView, p: Int) {
                hook(v)
                if (v.tag == tabs[cur].id) {
                    progress.progress = p
                    progress.visibility = if (p < 100) View.VISIBLE else View.GONE
                }
            }
            override fun onShowFileChooser(v: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean {
                fileCb?.onReceiveValue(null)
                fileCb = cb
                return try { picker.launch(p.createIntent()); true } catch (e: Exception) { fileCb = null; false }
            }
        }
        setupDownload(w)
        container.addView(w)
        w.loadUrl(t.url)
        w
    }

    private val dlTokens: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet<String>())

    inner class Bridge {
        @JavascriptInterface
        fun save(token: String, dataUrl: String, mime: String, name: String) {
            if (!dlTokens.remove(token)) return
            try {
                val bytes = android.util.Base64.decode(dataUrl.substringAfter(","), android.util.Base64.DEFAULT)
                val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
                val clean = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_").trim()
                val fin = if (clean.isEmpty()) "claude_" + System.currentTimeMillis() + "." + ext
                    else if (!clean.contains('.')) clean + "." + ext else clean
                saveBytes(bytes, fin, mime)
                runOnUiThread { Toast.makeText(this@MainActivity, "Salvo em Downloads: $fin", Toast.LENGTH_LONG).show() }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this@MainActivity, "Falha ao salvar o arquivo", Toast.LENGTH_LONG).show() }
            }
        }
        @JavascriptInterface
        fun fail(token: String) {
            dlTokens.remove(token)
            runOnUiThread { Toast.makeText(this@MainActivity, "Não foi possível baixar", Toast.LENGTH_LONG).show() }
        }
    }

    private fun saveBytes(b: ByteArray, name: String, mime: String) {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val v = android.content.ContentValues()
            v.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
            v.put(android.provider.MediaStore.Downloads.MIME_TYPE, mime)
            v.put(android.provider.MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)!!
            contentResolver.openOutputStream(uri)!!.use { it.write(b) }
        } else {
            java.io.File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)!!, name).writeBytes(b)
        }
    }

    private fun setupDownload(w: WebView) {
        w.addJavascriptInterface(Bridge(), "AndroidDl")
        w.setDownloadListener { u, ua, cd, mime, _ ->
            if (u.startsWith("blob:")) {
                if (u.contains("'")) return@setDownloadListener
                val mt = if (mime.isNullOrBlank() || mime.contains("'")) "application/octet-stream" else mime
                val tk = java.util.UUID.randomUUID().toString()
                dlTokens.add(tk)
                val js = "(function(){var fn=window.__lastDl||'';window.__lastDl='';var x=new XMLHttpRequest();x.open('GET','" + u + "',true);x.responseType='blob';" +
                    "x.onload=function(){var r=new FileReader();r.onloadend=function(){AndroidDl.save('" + tk + "',r.result,'" + mt + "',fn);};r.readAsDataURL(x.response);};" +
                    "x.onerror=function(){AndroidDl.fail('" + tk + "');};x.send();})()"
                w.evaluateJavascript(js, null)
                Toast.makeText(this, "Baixando…", Toast.LENGTH_SHORT).show()
                return@setDownloadListener
            }
            try {
                val name = URLUtil.guessFileName(u, cd, mime)
                val r = DownloadManager.Request(Uri.parse(u))
                r.setMimeType(mime); r.addRequestHeader("User-Agent", ua)
                val ck = if (profilesOk) WebViewCompat.getProfile(w).cookieManager.getCookie(u) else CookieManager.getInstance().getCookie(u)
                if (ck != null) r.addRequestHeader("Cookie", ck)
                r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                try { r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name) }
                catch (e: Exception) { r.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, name) }
                (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(r)
                Toast.makeText(this, "Baixando $name", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) { Toast.makeText(this, "Não foi possível baixar", Toast.LENGTH_SHORT).show() }
        }
    }

    private val HOOK_JS = """(function(){if(window.__dlHook)return;window.__dlHook=1;var oc=HTMLAnchorElement.prototype.click;HTMLAnchorElement.prototype.click=function(){try{if(this.download)window.__lastDl=this.download;}catch(e){}return oc.apply(this,arguments);};var od=EventTarget.prototype.dispatchEvent;EventTarget.prototype.dispatchEvent=function(ev){try{if(this instanceof HTMLAnchorElement&&ev&&ev.type==='click'&&this.download)window.__lastDl=this.download;}catch(e){}return od.apply(this,arguments);};document.addEventListener('click',function(e){var a=e.target&&e.target.closest?e.target.closest('a[download]'):null;if(a&&a.download)window.__lastDl=a.download;},true);})();"""

    private fun hook(v: WebView) { v.evaluateJavascript(HOOK_JS, null) }

    private fun upd(v: WebView, u: String?) {
        val tb = tabs.firstOrNull { it.id == v.tag } ?: return
        if (u == null) return
        tb.url = u
        if (tabs[cur].id == tb.id && !urlBar.hasFocus()) urlBar.setText(u)
        save()
    }

    private fun select(i: Int) {
        if (i < 0 || i >= tabs.size) return
        closeFav()
        cur = i
        val t = tabs[i]
        val w = webFor(t)
        for (k in 0 until container.childCount) container.getChildAt(k).visibility = View.GONE
        w.alpha = 0f; w.visibility = View.VISIBLE
        w.animate().alpha(1f).setDuration(180).start()
        urlBar.setText(t.url)
        progress.visibility = View.GONE
        adapter.notifyDataSetChanged()
        rv.scrollToPosition(i)
        save()
    }

    private fun go(text: String) {
        val t = text.trim()
        val u = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            !t.contains(" ") && t.contains(".") -> "https://$t"
            else -> "https://www.google.com/search?q=" + Uri.encode(t)
        }
        webs[tabs[cur].id]?.loadUrl(u)
        urlBar.clearFocus()
    }

    private fun input(title: String, def: String, ok: (String) -> Unit) {
        val e = EditText(this); e.setText(def); e.setSingleLine(); e.selectAll()
        AlertDialog.Builder(this).setTitle(title).setView(e)
            .setPositiveButton("OK") { _, _ -> val n = e.text.toString().trim(); if (n.isNotEmpty()) ok(n) }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun addTab() = input("Nome da nova aba", "Conta ${tabs.size + 1}") {
        tabs.add(newTab(it)); select(tabs.size - 1)
    }

    private fun tabOptions(i: Int) {
        if (i < 0 || i >= tabs.size) return
        AlertDialog.Builder(this).setTitle(tabs[i].name)
            .setItems(arrayOf("Renomear", "Recarregar", "Definir volta do limite", "Limpar limite", "Fechar aba")) { _, w ->
                when (w) {
                    0 -> input("Renomear aba", tabs[i].name) { tabs[i].name = it; adapter.notifyDataSetChanged(); save() }
                    1 -> webs[tabs[i].id]?.reload()
                    2 -> pickLimit(i)
                    3 -> { tabs[i].until = 0L; save(); tickNow() }
                    4 -> AlertDialog.Builder(this).setTitle("Fechar \"${tabs[i].name}\"?")
                        .setMessage("O login dessa aba será apagado.")
                        .setPositiveButton("Fechar") { _, _ -> closeTab(i) }
                        .setNegativeButton("Cancelar", null).show()
                }
            }.show()
    }

    private fun closeTab(i: Int) {
        val t = tabs.removeAt(i)
        webs.remove(t.id)?.let { container.removeView(it); it.destroy() }
        try { if (profilesOk) ProfileStore.getInstance().deleteProfile("p_" + t.id) } catch (e: Exception) { }
        if (tabs.isEmpty()) tabs.add(newTab("Conta 1"))
        select(minOf(i, tabs.size - 1))
    }

    private fun showMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, "Recarregar"); m.menu.add(0, 2, 1, "Configurações")
        m.setOnMenuItemClickListener {
            if (it.itemId == 1) webs[tabs[cur].id]?.reload() else settings()
            true
        }
        m.show()
    }

    private fun settings() {
        AlertDialog.Builder(this).setTitle("Configurações")
            .setMessage("Multi Abas versão ${BuildConfig.VERSION_NAME}")
            .setPositiveButton("Verificar atualização") { _, _ -> Updater.check(this, true) }
            .setNeutralButton("O que mudou") { _, _ -> Novidades.show(this, true) }
            .setNegativeButton("Fechar", null).show()
    }

    private fun loadFavs() {
        try {
            val a = JSONArray(prefs.getString("favs", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                favs.add(Fav(o.getString("name"), o.getString("url"), o.optInt("mode", 0)))
            }
        } catch (e: Exception) { }
        favAdapter.notifyDataSetChanged()
    }

    private fun saveFavs() {
        val a = JSONArray()
        favs.forEach { a.put(JSONObject().put("name", it.name).put("url", it.url).put("mode", it.mode)) }
        prefs.edit().putString("favs", a.toString()).apply()
    }

    private fun editFav(i: Int) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(56, 16, 56, 0)
        val n = EditText(this); n.hint = "Nome"; n.setSingleLine()
        val u = EditText(this); u.hint = "Link"; u.setSingleLine()
        u.inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
        if (i >= 0) { n.setText(favs[i].name); u.setText(favs[i].url) } else u.setText(tabs[cur].url)
        val sp = Spinner(this)
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            arrayOf("Abrir na conta da aba", "Sessão compartilhada do app", "Abrir pelo Chrome"))
        sp.setSelection(if (i >= 0) favs[i].mode else 0)
        box.addView(n); box.addView(u); box.addView(sp)
        AlertDialog.Builder(this).setTitle(if (i >= 0) "Editar favorito" else "Novo favorito").setView(box)
            .setPositiveButton("Salvar") { _, _ ->
                var url = u.text.toString().trim()
                if (url.isEmpty()) return@setPositiveButton
                if (!url.startsWith("http")) url = "https://$url"
                val name = n.text.toString().trim()
                val nm = if (name.isEmpty()) (Uri.parse(url).host ?: url) else name
                if (i >= 0) { favs[i].name = nm; favs[i].url = url; favs[i].mode = sp.selectedItemPosition } else favs.add(Fav(nm, url, sp.selectedItemPosition))
                saveFavs(); favAdapter.notifyDataSetChanged()
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun favOptions(i: Int) {
        if (i < 0 || i >= favs.size) return
        AlertDialog.Builder(this).setTitle(favs[i].name)
            .setItems(arrayOf("Editar", "Remover")) { _, w ->
                if (w == 0) editFav(i) else { favs.removeAt(i); saveFavs(); favAdapter.notifyDataSetChanged() }
            }.show()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun openFav(f: Fav) {
        closeFav()
        if (f.mode == 2) {
            try { androidx.browser.customtabs.CustomTabsIntent.Builder().build().launchUrl(this, Uri.parse(f.url)) }
            catch (e: Exception) { Toast.makeText(this, "Não foi possível abrir pelo Chrome", Toast.LENGTH_SHORT).show() }
            return
        }
        val w = WebView(this)
        if (profilesOk) { try { WebViewCompat.setProfile(w, "p_" + (if (f.mode == 1) "shared" else tabs[cur].id)) } catch (e: Exception) { } }
        w.layoutParams = ViewGroup.LayoutParams(-1, -1)
        w.setBackgroundColor(Color.parseColor("#0F1115"))
        val s = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = false
        s.userAgentString = s.userAgentString.replace("; wv", "").replace(Regex("Version/\\d+\\.\\d+ "), "")
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val sc = r.url.scheme ?: ""
                if (sc == "http" || sc == "https") return false
                try { startActivity(Intent(Intent.ACTION_VIEW, r.url)) } catch (e: Exception) { }
                return true
            }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView, p: Int) {
                hook(v)
                favProgress.progress = p
                favProgress.visibility = if (p < 100) View.VISIBLE else View.GONE
            }
            override fun onShowFileChooser(v: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean {
                fileCb?.onReceiveValue(null)
                fileCb = cb
                return try { picker.launch(p.createIntent()); true } catch (e: Exception) { fileCb = null; false }
            }
        }
        setupDownload(w)
        favHost.addView(w)
        favWeb = w
        favTitle.text = f.name
        overlay.alpha = 0f
        overlay.visibility = View.VISIBLE
        overlay.animate().alpha(1f).setDuration(180).start()
        w.loadUrl(f.url)
    }

    private fun closeFav() {
        val w = favWeb ?: return
        favWeb = null
        overlay.visibility = View.GONE
        favProgress.visibility = View.GONE
        favHost.removeView(w)
        w.destroy()
    }

    inner class FavAdapter : RecyclerView.Adapter<FavAdapter.VH>() {
        inner class VH(val t: TextView) : RecyclerView.ViewHolder(t)
        override fun onCreateViewHolder(p: ViewGroup, v: Int) = VH(layoutInflater.inflate(R.layout.item_tab, p, false) as TextView)
        override fun getItemCount() = favs.size
        override fun onBindViewHolder(h: VH, i: Int) {
            h.t.text = favs[i].name + (if (favs[i].mode == 2) " ↗" else "")
            h.t.setTextColor(Color.parseColor("#E6E9F0"))
            h.t.setOnClickListener { val p = h.bindingAdapterPosition; if (p >= 0) openFav(favs[p]) }
            h.t.setOnLongClickListener { favOptions(h.bindingAdapterPosition); true }
        }
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() { tickNow(); handler.postDelayed(this, 1000) }
    }

    override fun onResume() { super.onResume(); handler.post(ticker) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(ticker) }

    private fun tickNow() {
        for (k in 0 until rv.childCount) {
            val v = rv.getChildAt(k)
            val p = rv.getChildAdapterPosition(v)
            val h = rv.getChildViewHolder(v) as? TabAdapter.VH ?: continue
            if (p >= 0 && p < tabs.size) h.bindStatus(tabs[p])
        }
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return String.format(java.util.Locale.US, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    }

    private fun pickLimit(i: Int) {
        val c = java.util.Calendar.getInstance()
        android.app.TimePickerDialog(this, { _, h, m ->
            val t = java.util.Calendar.getInstance()
            t.set(java.util.Calendar.HOUR_OF_DAY, h)
            t.set(java.util.Calendar.MINUTE, m)
            t.set(java.util.Calendar.SECOND, 0)
            t.set(java.util.Calendar.MILLISECOND, 0)
            if (t.timeInMillis <= System.currentTimeMillis()) t.add(java.util.Calendar.DAY_OF_YEAR, 1)
            tabs[i].until = t.timeInMillis
            save(); tickNow()
        }, c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE), true).show()
    }

    inner class TabAdapter : RecyclerView.Adapter<TabAdapter.VH>() {
        inner class VH(val v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.tabName)
            val st: TextView = v.findViewById(R.id.tabStatus)
            fun bindStatus(t: Tab) {
                if (t.until <= 0L) { st.visibility = View.GONE; return }
                st.visibility = View.VISIBLE
                val left = t.until - System.currentTimeMillis()
                if (left <= 0L) { st.text = "Liberado"; st.setTextColor(Color.parseColor("#4ADE80")) }
                else { st.text = fmt(left); st.setTextColor(Color.parseColor("#E0A58F")) }
            }
        }
        override fun onCreateViewHolder(p: ViewGroup, v: Int) = VH(layoutInflater.inflate(R.layout.item_conta, p, false))
        override fun getItemCount() = tabs.size
        override fun onBindViewHolder(h: VH, i: Int) {
            h.name.text = tabs[i].name
            h.v.isSelected = i == cur
            h.name.setTextColor(if (i == cur) Color.WHITE else Color.parseColor("#B0B6C3"))
            h.bindStatus(tabs[i])
            h.v.setOnClickListener { select(h.bindingAdapterPosition) }
            h.v.setOnLongClickListener { tabOptions(h.bindingAdapterPosition); true }
        }
    }
}
