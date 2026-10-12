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

class Tab(val id: String, var name: String, var url: String)

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
        findViewById<ImageButton>(R.id.btnAdd).setOnClickListener { addTab() }
        findViewById<ImageButton>(R.id.btnMenu).setOnClickListener { showMenu(it) }
        urlBar.setOnEditorActionListener { v, id, _ ->
            if (id == EditorInfo.IME_ACTION_GO) { go(v.text.toString()); true } else false
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
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
                tabs.add(Tab(o.getString("id"), o.getString("name"), o.getString("url")))
            }
        } catch (e: Exception) { }
        if (tabs.isEmpty()) tabs.add(newTab("Conta 1"))
        cur = prefs.getInt("cur", 0).coerceIn(0, tabs.size - 1)
    }

    private fun save() {
        val a = JSONArray()
        tabs.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("url", it.url)) }
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
        w.setDownloadListener { u, ua, cd, mime, _ ->
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
        container.addView(w)
        w.loadUrl(t.url)
        w
    }

    private fun upd(v: WebView, u: String?) {
        val tb = tabs.firstOrNull { it.id == v.tag } ?: return
        if (u == null) return
        tb.url = u
        if (tabs[cur].id == tb.id && !urlBar.hasFocus()) urlBar.setText(u)
        save()
    }

    private fun select(i: Int) {
        if (i < 0 || i >= tabs.size) return
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
            .setItems(arrayOf("Renomear", "Recarregar", "Fechar aba")) { _, w ->
                when (w) {
                    0 -> input("Renomear aba", tabs[i].name) { tabs[i].name = it; adapter.notifyDataSetChanged(); save() }
                    1 -> webs[tabs[i].id]?.reload()
                    2 -> AlertDialog.Builder(this).setTitle("Fechar \"${tabs[i].name}\"?")
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

    inner class TabAdapter : RecyclerView.Adapter<TabAdapter.VH>() {
        inner class VH(val t: TextView) : RecyclerView.ViewHolder(t)
        override fun onCreateViewHolder(p: ViewGroup, v: Int) = VH(layoutInflater.inflate(R.layout.item_tab, p, false) as TextView)
        override fun getItemCount() = tabs.size
        override fun onBindViewHolder(h: VH, i: Int) {
            h.t.text = tabs[i].name
            h.t.isSelected = i == cur
            h.t.setTextColor(if (i == cur) Color.WHITE else Color.parseColor("#B0B6C3"))
            h.t.setOnClickListener { select(h.bindingAdapterPosition) }
            h.t.setOnLongClickListener { tabOptions(h.bindingAdapterPosition); true }
        }
    }
}
