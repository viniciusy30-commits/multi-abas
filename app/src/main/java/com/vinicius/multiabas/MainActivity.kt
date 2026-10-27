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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject

class Tab(val id: String, var name: String, var url: String, var until: Long = 0L) {
    /** Trecho do aviso de limite já tratado (evita recalcular o mesmo aviso a cada 15s). */
    var limitSrc: String = ""
    /** Até quando ignorar o aviso da página (logo depois de recarregar a aba). */
    var holdUntil: Long = 0L
}

class MainActivity : AppCompatActivity() {
    private val HOME = "https://claude.ai"
    private val tabs = mutableListOf<Tab>()
    private var cur = 0
    private val webs = HashMap<String, WebView>()
    private lateinit var container: FrameLayout
    private lateinit var urlBar: EditText
    private lateinit var urlIcon: ImageView
    private lateinit var progress: ProgressBar
    private lateinit var rv: RecyclerView
    private val adapter = TabAdapter()
    private var fileCb: ValueCallback<Array<Uri>>? = null
    /** ZIP do main pronto para injetar no próximo file chooser do Claude */
    private var pendingClaudeFile: Array<Uri>? = null
    private val profilesOk by lazy { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }
    private val prefs by lazy { getSharedPreferences("tabs", 0) }
    private class Fav(var name: String, var url: String, var mode: Int = 0)
    private val favs = mutableListOf<Fav>()
    /** Botão de ação que o usuário escolheu colocar na barra de baixo (junto dos favoritos). */
    private class DockAct(val id: String, val label: String, val icon: Int, val run: () -> Unit)
    private val dockIds = mutableListOf<String>()
    private fun allDockActs(): List<DockAct> = listOf(
        DockAct("send_main", "Enviar main", R.drawable.ic_send) { sendMainToClaude() },
        DockAct("script", "Ver script", R.drawable.ic_play) { scr.ui.showPanel() },
        DockAct("report", "Relatório", R.drawable.ic_info) { scr.ui.showReport() },
        DockAct("apk", "Baixar APK", R.drawable.ic_download) { downloadLatestApkNow() },
        DockAct("game", "Abrir jogo", R.drawable.ic_play) { openCurrentGame() },
        DockAct("reload", "Recarregar", R.drawable.ic_refresh) { webs[tabs[cur].id]?.reload() },
        DockAct("projects", "Projetos", R.drawable.ic_folder) { projectPicker() },
        DockAct("usage", "Uso das contas", R.drawable.ic_timer) { usageMon.ui.show() },
        DockAct("handoff", "Outra conta", R.drawable.ic_refresh) { manualHandoff() },
        DockAct("paste", "Texto grande", R.drawable.ic_send) { pasteLargeText() },
        DockAct("settings", "Ajustes", R.drawable.ic_settings) { settings() },
        DockAct("menu", "Menu", R.drawable.ic_more) { showMenu() }
    )
    private fun dockActs(): List<DockAct> { val all = allDockActs(); return dockIds.mapNotNull { id -> all.firstOrNull { it.id == id } } }
    private val favAdapter = FavAdapter()
    private var favWeb: WebView? = null
    private val limitAsked = HashSet<String>()
    private val limitLearned = HashSet<String>()
    /** limitSrc = CLEARED → o usuário mexeu no limite à mão; ignora o aviso da página até ele sumir. */
    private val CLEARED = "#manual"
    private var avatarTabId: String? = null
    private val avatarPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val id = avatarTabId
        avatarTabId = null
        if (uri != null && id != null) {
            Thread {
                val ok = Avatars.saveFromUri(this, id, uri)
                runOnUiThread {
                    if (ok) { refreshRows(); say("Foto da conta definida") } else say("Não consegui usar essa imagem", true)
                }
            }.start()
        }
    }
    private lateinit var overlay: View
    private lateinit var favHost: FrameLayout
    private lateinit var favTitle: TextView
    private lateinit var favProgress: ProgressBar

    // Galeria do sistema (estilo Google Fotos) — Android 13+ Photo Picker / PickVisualMedia
    private val photoPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) fileCb?.onReceiveValue(arrayOf(uri)) else fileCb?.onReceiveValue(null)
        fileCb = null
    }
    private val multiPhotoPicker = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        if (uris.isNotEmpty()) fileCb?.onReceiveValue(uris.toTypedArray()) else fileCb?.onReceiveValue(null)
        fileCb = null
    }
    // Fallback: documentos / qualquer arquivo
    private val docPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        fileCb = null
    }

    private fun openFileChooser(params: WebChromeClient.FileChooserParams): Boolean {
        // Se estamos enviando o main do repo pro Claude, entrega o ZIP direto
        val pending = pendingClaudeFile
        if (pending != null) {
            pendingClaudeFile = null
            try {
                fileCb?.onReceiveValue(pending)
            } catch (_: Exception) {
                fileCb?.onReceiveValue(null)
            }
            fileCb = null
            return true
        }
        val types = params.acceptTypes?.filter { it.isNotBlank() } ?: emptyList()
        val multi = params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
        val onlyMedia = types.isEmpty() || types.all { t ->
            val x = t.lowercase()
            x.startsWith("image") || x.startsWith("video") || x == "*/*" || x == "."
        }
        // Site pediu um tipo específico (pdf, zip…): vai direto pro gerenciador de arquivos
        if (!onlyMedia) return launchFilePicker(types, multi)

        // Caso geral: pergunta se é foto/vídeo (galeria) ou arquivo (gerenciador, pasta Downloads)
        return try {
            MaterialAlertDialogBuilder(this)
                .setTitle("Anexar")
                .setItems(arrayOf("🖼️  Foto ou vídeo", "📁  Arquivo")) { _, which ->
                    if (which == 0) launchGallery(multi) else launchFilePicker(types, multi)
                }
                .setOnCancelListener {
                    fileCb?.onReceiveValue(null)
                    fileCb = null
                }
                .show().also { trackDlg(it) }
            true
        } catch (_: Exception) {
            fileCb = null
            false
        }
    }

    private fun launchGallery(multi: Boolean) {
        try {
            val req = androidx.activity.result.PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                .build()
            if (multi) multiPhotoPicker.launch(req) else photoPicker.launch(req)
        } catch (_: Exception) {
            fileCb?.onReceiveValue(null)
            fileCb = null
        }
    }

    /** Gerenciador de arquivos do sistema, já abrindo na pasta Downloads. */
    private fun launchFilePicker(types: List<String>, multi: Boolean): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                if (multi) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                // Se o site restringiu o tipo, respeita (extensões viram MIME quando possível)
                val mimes = types.mapNotNull { t ->
                    val x = t.trim().lowercase()
                    when {
                        x.contains("/") -> x
                        x.startsWith(".") -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(x.removePrefix("."))
                        else -> null
                    }
                }.distinct()
                if (mimes.isNotEmpty() && mimes.size == types.size && !mimes.contains("*/*")) {
                    putExtra(Intent.EXTRA_MIME_TYPES, mimes.toTypedArray())
                }
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    putExtra(
                        android.provider.DocumentsContract.EXTRA_INITIAL_URI,
                        android.provider.DocumentsContract.buildDocumentUri(
                            "com.android.externalstorage.documents", "primary:Download"
                        )
                    )
                }
            }
            docPicker.launch(intent)
            true
        } catch (_: Exception) {
            fileCb?.onReceiveValue(null)
            fileCb = null
            false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        container = findViewById(R.id.container)
        urlBar = findViewById(R.id.urlBar)
        urlIcon = findViewById(R.id.urlIcon)
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
        findViewById<ImageButton>(R.id.btnFavAdd).setOnClickListener { addToDock() }
        loadFavs()
        findViewById<ImageButton>(R.id.btnAdd).setOnClickListener { addTab() }
        findViewById<ImageButton>(R.id.btnMenu).setOnClickListener { showMenu() }
        findViewById<ImageButton>(R.id.btnReload).setOnClickListener { webs[tabs[cur].id]?.reload() }
        urlBar.setOnFocusChangeListener { _, has ->
            val u = tabs[cur].url
            if (has) { urlBar.setText(u); urlBar.selectAll() } else urlBar.setText(shownUrl(u))
        }
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
        if (!profilesOk) say("Seu WebView não separa contas. Atualize o \"Android System WebView\" na Play Store.", true)
        select(cur)
        Script.load(this)
        Usage.load(this)
        Est.load(this)
        if (Script.status == Script.RUNNING) scr.resumeAfterRestart()
        Updater.autoCheck(this)
        Novidades.show(this, false)
        handler.postDelayed(handoffTick, 8000)
        if (prefs.getBoolean("keepalive", true)) {
            maybeRequestNotifPermission()
            KeepAliveService.start(this)
        }
    }

    private fun newTab(name: String) = Tab(System.currentTimeMillis().toString(), name, HOME)

    private fun load() {
        try {
            val a = JSONArray(prefs.getString("tabs", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                tabs.add(Tab(o.getString("id"), o.getString("name"), o.getString("url"), o.optLong("until", 0L)).also { it.limitSrc = o.optString("limitSrc", "") })
            }
        } catch (e: Exception) { }
        if (tabs.isEmpty()) tabs.add(newTab("Conta 1"))
        cur = prefs.getInt("cur", 0).coerceIn(0, tabs.size - 1)
    }

    private fun save() {
        val a = JSONArray()
        tabs.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("url", it.url).put("until", it.until).put("limitSrc", it.limitSrc)) }
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
                if (sc == "http" || sc == "https") return githubToChrome(v, r)
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
                return openFileChooser(p)
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
                runOnUiThread {
                    say("Salvo em Downloads: $fin", true)
                    maybeDeliverZip(fin, bytes)
                }
            } catch (e: Exception) {
                runOnUiThread { say("Falha ao salvar o arquivo", true) }
            }
        }
        @JavascriptInterface
        fun fail(token: String) {
            dlTokens.remove(token)
            runOnUiThread { say("Não foi possível baixar", true) }
        }

        // ── salvar chat completo (só aceita o token da sessão em andamento) ──
        @JavascriptInterface
        fun hfProgress(tok: String, msg: String) { hfSessions[tok]?.progress?.invoke(msg) }
        @JavascriptInterface
        fun hfText(tok: String, name: String, text: String) {
            val ss = hfSessions[tok] ?: return
            try { hfWrite(ss.dir, name, text.toByteArray(Charsets.UTF_8)) } catch (_: Exception) { }
        }
        @JavascriptInterface
        fun hfBin(tok: String, name: String, mime: String, dataUrl: String) {
            val ss = hfSessions[tok] ?: return
            try { hfWrite(ss.dir, name, android.util.Base64.decode(dataUrl.substringAfter(","), android.util.Base64.DEFAULT)) } catch (_: Exception) { }
        }
        @JavascriptInterface
        fun hfEnd(tok: String, summary: String) { hfSessions[tok]?.onEnd?.invoke(summary) }
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

    private fun enqueueDownload(u: String, ua: String, cd: String?, mime: String?, w: WebView) {
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
            say("Baixando $name")
        } catch (e: Exception) { say("Não foi possível baixar") }
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
                say("Baixando…")
                return@setDownloadListener
            }
            val name = URLUtil.guessFileName(u, cd, mime)
            val looksZip = name.lowercase().endsWith(".zip") || (mime ?: "").lowercase().contains("zip")
            if (looksZip && u.startsWith("http") && Projects.current(this) != null && Projects.token(this).isNotBlank()) {
                // ZIP: baixa dentro do app para poder entregar no GitHub (DownloadManager não devolve os bytes)
                val zipName = if (name.lowercase().endsWith(".zip")) name else name.substringBeforeLast('.', name) + ".zip"
                val ck = try {
                    if (profilesOk) WebViewCompat.getProfile(w).cookieManager.getCookie(u) else CookieManager.getInstance().getCookie(u)
                } catch (_: Exception) { null }
                say("Baixando $zipName…")
                Thread {
                    try {
                        val c = java.net.URL(u).openConnection() as java.net.HttpURLConnection
                        c.connectTimeout = 30000
                        c.readTimeout = 120000
                        c.setRequestProperty("User-Agent", ua)
                        if (ck != null) c.setRequestProperty("Cookie", ck)
                        if (c.responseCode !in 200..299) throw Exception("HTTP " + c.responseCode)
                        val bytes = c.inputStream.use { it.readBytes() }
                        saveBytes(bytes, zipName, "application/zip")
                        runOnUiThread {
                            say("Salvo em Downloads: $zipName", true)
                            maybeDeliverZip(zipName, bytes)
                        }
                    } catch (e: Exception) {
                        // não deu pelo app: cai no download normal
                        runOnUiThread { enqueueDownload(u, ua, cd, mime, w) }
                    }
                }.start()
                return@setDownloadListener
            }
            enqueueDownload(u, ua, cd, mime, w)
        }
    }

    private val HOOK_JS = """(function(){if(window.__dlHook)return;window.__dlHook=1;var oc=HTMLAnchorElement.prototype.click;HTMLAnchorElement.prototype.click=function(){try{if(this.download)window.__lastDl=this.download;}catch(e){}return oc.apply(this,arguments);};var od=EventTarget.prototype.dispatchEvent;EventTarget.prototype.dispatchEvent=function(ev){try{if(this instanceof HTMLAnchorElement&&ev&&ev.type==='click'&&this.download)window.__lastDl=this.download;}catch(e){}return od.apply(this,arguments);};document.addEventListener('click',function(e){var a=e.target&&e.target.closest?e.target.closest('a[download]'):null;if(a&&a.download)window.__lastDl=a.download;},true);})();"""

    private fun hook(v: WebView) { v.evaluateJavascript(HOOK_JS, null) }

    private fun githubToChrome(v: WebView, r: WebResourceRequest): Boolean {
        if (!r.isForMainFrame) return false
        val host = r.url.host ?: return false
        if (host != "github.com" && !host.endsWith(".github.com")) return false
        val cur = Uri.parse(v.url ?: "").host ?: ""
        if (cur == "github.com" || cur.endsWith(".github.com")) return false
        val p = r.url.path ?: ""
        if (p.startsWith("/login") || p.startsWith("/session") || p.startsWith("/apps/") || p.startsWith("/settings/connections")) return false
        return try {
            androidx.browser.customtabs.CustomTabsIntent.Builder().build().launchUrl(this, r.url)
            true
        } catch (e: Exception) { false }
    }

    private fun upd(v: WebView, u: String?) {
        val tb = tabs.firstOrNull { it.id == v.tag } ?: return
        if (u == null) return
        tb.url = u
        if (tabs[cur].id == tb.id && !urlBar.hasFocus()) setUrlText(u)
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
        setUrlText(t.url)
        progress.visibility = View.GONE
        refreshRows()
        scrollToTab(t)
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
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(urlBar.windowToken, 0)
    }

    private fun dp(v: Int) = Ui.dp(this, v)

    /** Aviso rápido no rodapé (acima da dock), no lugar do Toast. */
    private fun say(msg: String, long: Boolean = false) {
        try {
            val sb = com.google.android.material.snackbar.Snackbar.make(
                findViewById<View>(android.R.id.content), msg,
                if (long) com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                else com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
            )
            sb.setAnchorView(R.id.dock)
            sb.setBackgroundTint(Color.parseColor("#252A35"))
            sb.setTextColor(Color.WHITE)
            sb.view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)?.maxLines = 4
            sb.show()
        } catch (_: Exception) { }
    }

    private fun confirm(title: String, msg: String, okLabel: String, ok: () -> Unit) {
        MaterialAlertDialogBuilder(this).setTitle(title).setMessage(msg)
            .setPositiveButton(okLabel) { _, _ -> ok() }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun shownUrl(u: String): String = try {
        Uri.parse(u).host?.removePrefix("www.")?.ifBlank { null } ?: u
    } catch (_: Exception) { u }

    private fun setUrlText(u: String) {
        urlBar.setText(if (urlBar.hasFocus()) u else shownUrl(u))
        urlIcon.setColorFilter(Color.parseColor(if (u.startsWith("https")) "#8B92A1" else "#FF6B6B"))
    }

    private fun input(title: String, def: String, ok: (String) -> Unit) {
        val (til, e) = Ui.field(this, "Nome", def)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(24), dp(8), dp(24), 0)
        box.addView(til)
        MaterialAlertDialogBuilder(this).setTitle(title).setView(box)
            .setPositiveButton("OK") { _, _ -> val n = e.text.toString().trim(); if (n.isNotEmpty()) ok(n) }
            .setNegativeButton("Cancelar", null).show()
        e.requestFocus()
        e.selectAll()
    }

    private fun addTab() = input("Nome da nova aba", "Conta ${tabs.size + 1}") {
        tabs.add(newTab(it)); select(tabs.size - 1)
    }

    private fun tabOptions(i: Int) {
        if (i < 0 || i >= tabs.size) return
        val t = tabs[i]
        val sh = Sheet(this).title(t.name, "Conta separada, com login e cookies próprios")
        sh.row(R.drawable.ic_edit, "Renomear") {
            input("Renomear aba", t.name) { t.name = it; refreshRows(); save() }
        }
        sh.row(R.drawable.ic_refresh, "Recarregar") { webs[t.id]?.reload() }
        sh.row(R.drawable.ic_star, "Foto da conta", if (Avatars.has(this, t.id)) "Trocar ou remover a foto" else "Escolher uma foto da galeria no lugar da letra") { photoOptions(t) }
        sh.row(R.drawable.ic_send, "Continuar em outra conta", "Leva o projeto e a conversa desta aba") { chooseTarget(t) }
        sh.row(R.drawable.ic_timer, "Definir volta do limite", "Mostra um cronômetro na aba") {
            val idx = tabs.indexOf(t)
            if (idx >= 0) pickLimit(idx)
        }
        if (t.until > 0L) {
            sh.row(R.drawable.ic_close, "Limpar limite") { t.until = 0L; t.limitSrc = CLEARED; save(); tickNow() }
        }
        sh.row(R.drawable.ic_delete, "Fechar aba", "O login dessa aba será apagado", danger = true) {
            confirm("Fechar \"${t.name}\"?", "O login dessa aba será apagado.", "Fechar") {
                val idx = tabs.indexOf(t)
                if (idx >= 0) closeTab(idx)
            }
        }
        sh.show()
    }

    private fun closeTab(i: Int) {
        val t = tabs.removeAt(i)
        Avatars.clear(this, t.id)
        coolingIds.remove(t.id)
        webs.remove(t.id)?.let { container.removeView(it); it.destroy() }
        try { if (profilesOk) ProfileStore.getInstance().deleteProfile("p_" + t.id) } catch (e: Exception) { }
        if (tabs.isEmpty()) tabs.add(newTab("Conta 1"))
        select(minOf(i, tabs.size - 1))
    }

    private fun showMenu() {
        val proj = Projects.current(this)
        val hasToken = Projects.token(this).isNotBlank()
        val sh = Sheet(this).title("Multi Abas", "Versão ${BuildConfig.VERSION_NAME}")
        val ptitle = proj?.name ?: "Escolher projeto"
        val psub = when {
            proj == null -> "Escolha o repositório que recebe os ZIPs do Claude"
            !hasToken -> "${proj.fullRepo()} · falta o token do GitHub"
            else -> "${proj.fullRepo()} · entrega automática ativa"
        }
        sh.row(R.drawable.ic_code, ptitle, psub, highlighted = true) { projectPicker() }
        sh.section("Ações rápidas")
        sh.tiles(listOf(
            Tile(R.drawable.ic_send, "Enviar main pro Claude") { sendMainToClaude() },
            Tile(R.drawable.ic_download, "Baixar APK") { downloadLatestApkNow() },
            Tile(R.drawable.ic_play, "Abrir jogo") { openCurrentGame() },
            Tile(R.drawable.ic_refresh, "Recarregar") { webs[tabs[cur].id]?.reload() },
            Tile(R.drawable.ic_folder, "Projetos") { projectPicker() },
            Tile(R.drawable.ic_settings, "Ajustes") { settings() }
        ))
        sh.section("Automação")
        sh.row(R.drawable.ic_play, "Script de tarefas", scriptSub(), highlighted = Script.status == Script.RUNNING) { scr.ui.showPanel() }
        sh.row(R.drawable.ic_timer, "Uso das contas (0–100%)", usageSub()) { usageMon.ui.show() }
        sh.section("Limite acabou?")
        sh.row(R.drawable.ic_refresh, "Continuar em outra conta", "Salva o chat completo, imagens e código num ZIP e leva para outra aba") { manualHandoff() }
        sh.section("Texto grande")
        sh.row(R.drawable.ic_send, "Colar texto grande", "Vira um arquivo .txt e anexa no chat desta aba") { pasteLargeText() }
        sh.show()
    }

    private fun settings() {
        val tk = Projects.token(this)
        val sh = Sheet(this).title("Ajustes", "Multi Abas v${BuildConfig.VERSION_NAME}")
        sh.section("GitHub")
        sh.row(
            R.drawable.ic_key, "Token do GitHub",
            if (tk.isBlank()) "Não configurado — toque para colar" else "Configurado · ••••" + tk.takeLast(4)
        ) { editToken() }
        sh.row(R.drawable.ic_folder, "Projetos", "Escolher e gerenciar repositórios") { projectPicker() }
        sh.section("App")
        sh.switchRow(
            R.drawable.ic_bolt, "Segundo plano", "Mantém o Claude rodando ao minimizar",
            prefs.getBoolean("keepalive", true)
        ) { on -> setKeepAlive(on) }
        sh.switchRow(
            R.drawable.ic_code, "Salvar andamento do Claude", "Guarda a conversa em tempo real para continuar em outra conta",
            prefs.getBoolean("handoff_auto", true)
        ) { on -> prefs.edit().putBoolean("handoff_auto", on).apply(); say(if (on) "Andamento sendo salvo" else "Andamento pausado") }
        sh.switchRow(
            R.drawable.ic_download, "Instalar o jogo sozinho", "Quando o build terminar, já abre a instalação da nova versão",
            prefs.getBoolean("auto_install", true)
        ) { on -> prefs.edit().putBoolean("auto_install", on).apply(); say(if (on) "Instalação automática ligada" else "Instalação automática desligada") }
        sh.switchRow(
            R.drawable.ic_refresh, "Corrigir erro do build sozinho", "Se o build falhar, manda o erro ao Claude, baixa o ZIP corrigido e entrega de novo (até ${AutoFix.MAX} vezes)",
            prefs.getBoolean("autofix", true)
        ) { on -> prefs.edit().putBoolean("autofix", on).apply(); say(if (on) "Correção automática ligada" else "Correção automática desligada") }
        sh.row(R.drawable.ic_download, "Verificar atualização", "Procura uma versão nova do app") { Updater.check(this, true) }
        sh.row(R.drawable.ic_info, "O que mudou", "Novidades desta versão") { Novidades.show(this, true) }
        sh.show()
    }

    private fun editToken() {
        val (til, e) = Ui.field(this, "ghp_… ou github_pat_…", Projects.token(this), password = true)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(24), dp(8), dp(24), 0)
        box.addView(til)
        MaterialAlertDialogBuilder(this).setTitle("Token do GitHub")
            .setMessage("Personal Access Token com permissão de conteúdo (repo) e actions. Cole aqui e salve.")
            .setView(box)
            .setPositiveButton("Salvar") { _, _ ->
                val v = e.text.toString().trim()
                Projects.setToken(this, v)
                say(if (v.isEmpty()) "Token apagado" else "Token salvo ✓")
            }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun setKeepAlive(on: Boolean) {
        prefs.edit().putBoolean("keepalive", on).apply()
        if (on) {
            maybeRequestNotifPermission()
            KeepAliveService.start(this)
            say("Segundo plano ligado")
        } else {
            KeepAliveService.stop(this)
            say("Segundo plano desligado")
        }
    }

    /** Baixa o APK da última release do projeto atual, com progresso, e oferece instalar. */
    private fun downloadLatestApkNow() {
        val p = Projects.current(this)
        if (p == null) {
            say("Defina um Projeto atual primeiro")
            projectPicker()
            return
        }
        val token = Projects.token(this)
        if (token.isBlank()) {
            say("Configure o Token do GitHub em Ajustes", true)
            editToken()
            return
        }
        val popup = BuildPopup(this, p.name, "Baixar APK")
        popup.show()
        popup.status("Procurando a última versão…")
        Thread {
            val apk = GitHubDelivery.downloadLatestApk(this, token, p, { popup.status(it) }, 20_000L) { popup.progress(it) }
            if (apk != null) {
                val started = AutoInstall.install(this, apk, p.name)
                popup.status(if (started) "APK pronto ✓ Instalando…" else "APK pronto ✓")
                popup.done(true, null, apk)
            } else {
                popup.status("Não achei APK na última release ✗")
                popup.done(false, null, null)
            }
        }.start()
    }

    private fun maybeRequestNotifPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 91)
            }
        }
    }

    private fun looksLikeZipBytes(b: ByteArray) =
        b.size > 4 && b[0] == 0x50.toByte() && b[1] == 0x4B.toByte() && b[2] == 0x03.toByte() && b[3] == 0x04.toByte()

    private fun maybeDeliverZip(name: String, bytes: ByteArray) {
        val ext = name.substringAfterLast('.', "").lowercase()
        val isZipName = ext == "zip"
        // arquivo sem extensão útil (.bin) mas com cabeçalho de ZIP também conta; docx/apk/etc. não
        val isZipUnknown = (ext == "bin" || ext.isEmpty()) && looksLikeZipBytes(bytes)
        if (!isZipName && !isZipUnknown) return
        val zipName = if (isZipName) name else name.substringBeforeLast('.', name) + ".zip"
        // script rodando: o ZIP fica guardado para o final, não vai direto ao GitHub
        if (Script.status == Script.RUNNING && scr.onZip(zipName, bytes)) return
        val proj = Projects.current(this)
        if (proj == null) {
            say("ZIP salvo. Defina um Projeto atual no menu para enviar ao GitHub.", true)
            return
        }
        if (Projects.token(this).isBlank()) {
            say("ZIP salvo. Configure o Token do GitHub em Configurações.", true)
            return
        }
        autoFix.onZipDownloaded()
        val guessTab = tabs.getOrNull(cur)?.takeIf { webs[it.id]?.url?.contains("claude.ai") == true }?.id ?: Script.chatTabId
        val fixTab = autoFix.tabFor(guessTab)
        val popup = BuildPopup(this, proj.name)
        popup.show()
        popup.status("Enviando $zipName → ${proj.name}…")
        Handoff.log(this, proj.id, "ZIP enviado: $zipName")
        var failLog = ""
        GitHubDelivery.deliver(
            this, proj, zipName, bytes,
            onStatus = { popup.status(it) },
            onSteps = { popup.steps(it) },
            onFailLog = {
                failLog = it
                if (autoFix.enabled()) popup.status("Build falhou ✗ — copiando o erro e mandando para o Claude…")
            },
            onDone = { ok, url, apk ->
                Handoff.log(this, proj.id, "$zipName → " + if (ok) "build OK, APK pronto" else "build falhou ou sem APK")
                popup.done(ok, url, apk)
                if (ok) autoFix.onBuildOk(proj.id)
                else if (failLog.isNotBlank()) runOnUiThread { autoFix.start(proj, zipName, failLog, url, fixTab) }
                else autoFix.cancel()
            }
        )
    }

    private fun openCurrentGame() {
        val p = Projects.current(this)
        if (p == null) {
            say("Defina um Projeto atual primeiro")
            projectPicker()
            return
        }
        if (p.packageName.isBlank()) {
            say("Package do jogo não detectado. Edite o projeto e preencha.", true)
            return
        }
        if (!GitHubDelivery.openGame(this, p)) {
            say("Jogo não instalado. Use Baixar APK mais recente.", true)
        }
    }

    /**
     * Baixa o ZIP do branch do projeto (main/padrão) e anexa no chat do Claude aberto.
     */
    private fun sendMainToClaude() {
        val p = Projects.current(this)
        if (p == null) {
            say("Defina um Projeto atual primeiro")
            projectPicker()
            return
        }
        val token = Projects.token(this)
        // token ajuda em repo privado; público funciona sem
        say("Baixando main de ${p.name}…")
        Thread {
            try {
                val branch = p.branch.ifBlank { "main" }
                val zip = GitHubDelivery.downloadRepoZip(this, token, p, branch)
                if (zip == null || !zip.exists()) {
                    runOnUiThread {
                        say("Não foi possível baixar o ZIP de ${p.fullRepo()}@$branch", true)
                    }
                    return@Thread
                }
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    this, packageName + ".fileprovider", zip
                )
                runOnUiThread {
                    pendingClaudeFile = arrayOf(uri)
                    say("Anexando ${zip.name} no Claude…")
                    triggerClaudeAttach()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    say("Erro: ${e.message}", true)
                }
            }
        }.start()
    }

    /** Clica no botão de anexar / input file da página do Claude. */
    private fun triggerClaudeAttach() {
        val w = webs[tabs.getOrNull(cur)?.id] ?: run {
            say("Nenhuma aba aberta")
            pendingClaudeFile = null
            return
        }
        val js = """
            (function(){
              function click(el){ if(!el) return false; el.click(); return true; }
              var input = document.querySelector('input[type=file]');
              if (input) { input.click(); return 'input'; }
              var sels = [
                'button[aria-label*="attach" i]',
                'button[aria-label*="Upload" i]',
                'button[aria-label*="Add files" i]',
                'button[aria-label*="Anexar" i]',
                'button[data-testid*="file" i]',
                'button[data-testid*="attach" i]',
                '[data-testid="input-menu"] button',
                'button[aria-label*="plus" i]'
              ];
              for (var i=0;i<sels.length;i++){
                var b = document.querySelector(sels[i]);
                if (click(b)) return 'btn:'+sels[i];
              }
              // botões com ícone de clipe / + perto do composer
              var all = document.querySelectorAll('button');
              for (var j=0;j<all.length;j++){
                var a = (all[j].getAttribute('aria-label')||'') + ' ' + (all[j].getAttribute('data-testid')||'');
                if (/attach|upload|file|paperclip|plus|anexar|arquivo/i.test(a)) {
                  all[j].click(); return 'scan:'+a;
                }
              }
              return 'none';
            })();
        """.trimIndent()
        w.evaluateJavascript(js) { result ->
            val r = result?.trim('"') ?: "null"
            if (r == "none" || r == "null") {
                // tenta de novo após um instante (UI pode estar hidratando)
                w.postDelayed({
                    w.evaluateJavascript(js) { r2 ->
                        val x = r2?.trim('"') ?: "null"
                        if (x == "none" || x == "null") {
                            say("Não achei o botão de anexar. Abra o Claude, foque no chat e tente de novo.", true)
                            // limpa pendência se o chooser não abrir em 8s
                            w.postDelayed({
                                if (pendingClaudeFile != null) {
                                    pendingClaudeFile = null
                                    say("Envio cancelado (chooser não abriu)")
                                }
                            }, 8000)
                        }
                    }
                }, 600)
            }
        }
    }

    // ───────────── Continuar em outra conta ─────────────

    private val handoffTick = object : Runnable {
        override fun run() {
            try { captureAll() } catch (_: Exception) { }
            handler.postDelayed(this, 15000)
        }
    }

    /** A cada 15s guarda a conversa de cada aba do Claude e avisa se algum limite foi atingido. */
    private fun captureAll() {
        if (!prefs.getBoolean("handoff_auto", true)) return
        for (t in tabs.toList()) {
            val w = webs[t.id] ?: continue
            val u = w.url ?: continue
            if (!u.contains("claude.ai")) continue
            if (System.currentTimeMillis() < t.holdUntil) continue
            w.evaluateJavascript(Handoff.CAPTURE_JS) { raw ->
                val snap = Handoff.parse(raw)
                if (snap != null) {
                    Handoff.save(this, t.id, snap)
                    if (snap.limit) {
                        val active = applyLimitTime(t, snap.until)
                        if (active && Script.status != Script.RUNNING && limitAsked.add(t.id)) offerHandoff(t)
                    } else {
                        limitAsked.remove(t.id); limitLearned.remove(t.id)
                        if (t.limitSrc.isNotEmpty()) { t.limitSrc = ""; save() }
                    }
                }
            }
        }
    }

    /**
     * Lê o horário do aviso do Claude e preenche o cronômetro da aba.
     * Devolve true se a conta está mesmo em limite agora.
     * O mesmo aviso não é recalculado: antes, quando o horário passava e o aviso continuava na tela,
     * o app empurrava o limite para o dia seguinte.
     */
    private fun applyLimitTime(t: Tab, src: String): Boolean {
        if (t.limitSrc == CLEARED) return false
        val now = System.currentTimeMillis()
        val key = src.trim()
        if (key.isNotEmpty() && key == t.limitSrc) return t.until > now
        val at = LimitTime.parse(key, now)
        return when (at) {
            LimitTime.UNKNOWN -> { if (limitLearned.add(t.id)) Est.onLimit(this, t.id, 0L); true }
            LimitTime.PASSED -> { t.limitSrc = key; t.until = 0L; save(); tickNow(); false }
            else -> { t.limitSrc = key; t.until = at; save(); tickNow(); Est.onLimit(this, t.id, at); true }
        }
    }

    private fun offerHandoff(t: Tab) {
        if (isFinishing || isDestroyed) return
        if (tabs.size < 2) {
            say("\"${t.name}\" parece ter batido o limite. Adicione outra aba (conta) para continuar.", true)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Limite atingido?")
            .setMessage("A conta \"${t.name}\" parece ter chegado ao limite. Quer continuar o projeto em outra conta? O app leva a conversa e o código atual.")
            .setPositiveButton("Continuar") { _, _ -> chooseTarget(t) }
            .setNegativeButton("Agora não", null)
            .show().also { trackDlg(it) }
    }

    private fun manualHandoff() {
        val t = tabs.getOrNull(cur) ?: return
        chooseTarget(t)
    }

    /** Escolhe para qual aba (conta) levar o projeto. */
    private fun chooseTarget(from: Tab) {
        val others = tabs.filter { it.id != from.id }
        if (others.isEmpty()) {
            say("Só existe uma aba. Toque em + para adicionar outra conta.", true)
            return
        }
        if (!Handoff.hasChat(this, from.id) && Projects.current(this) == null) {
            say("Ainda não tenho a conversa de \"${from.name}\" salva. Abra um chat do Claude nela e espere uns 15s.", true)
            return
        }
        val now = System.currentTimeMillis()
        // liberadas primeiro; em recarga por ordem de quem volta antes
        val sorted = others.sortedWith(compareBy<Tab>({ if (it.until > now) 1 else 0 }, { if (it.until > now) it.until else 0L }))
        val labels = sorted.map { limitLabel(it, now) }.toTypedArray()
        if (Handoff.chatInfo(this, from.id) == null) say("Sem conversa salva nesta aba — vai só o código do projeto.", true)
        MaterialAlertDialogBuilder(this)
            .setTitle("Continuar em qual conta?")
            .setItems(labels) { _, which -> startHandoff(from, sorted[which]) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ───────────── Script (fila de tarefas) ─────────────

    internal val scr: ScriptRunner by lazy { ScriptRunner(this) }

    private fun scriptSub(): String = when (Script.status) {
        Script.RUNNING -> "Rodando: tarefa ${Script.idx + 1} de ${Script.items.size}"
        Script.PAUSED -> "Pausado: tarefa ${Script.idx + 1} de ${Script.items.size}"
        Script.DONE -> "Concluído · ${Script.zips.size} ZIP(s) para enviar"
        else -> if (Script.items.isEmpty()) "Monte uma lista de tarefas e deixe rodar sozinho" else "${Script.items.size} tarefa(s) na lista"
    }

    // ── avisos do próprio app que podem aparecer sozinhos e atrapalhar o Script ──
    private val autoDlgs = ArrayList<java.lang.ref.WeakReference<android.app.Dialog>>()
    internal fun trackDlg(d: android.app.Dialog?) { if (d != null) autoDlgs.add(java.lang.ref.WeakReference(d)) }

    /** Fecha os avisos do app que apareceram sozinhos (novidades, atualização, "limite atingido?", escolha de anexo). */
    internal fun scrDismissAppDialogs(): Int {
        var n = 0
        val it = autoDlgs.iterator()
        while (it.hasNext()) {
            val d = it.next().get()
            if (d == null) { it.remove(); continue }
            if (d.isShowing) { try { d.cancel(); n++ } catch (_: Exception) { } }
            it.remove()
        }
        return n
    }

    internal fun scrHandler() = handler
    internal fun scrTabs(): List<Tab> = tabs.toList()
    internal fun scrTab(id: String): Tab? = tabs.firstOrNull { it.id == id }
    internal fun scrCurrentTab(): Tab? = tabs.getOrNull(cur)
    internal fun scrWeb(t: Tab): WebView? = webs[t.id]
    internal fun scrWebOpen(t: Tab): WebView = webFor(t)
    internal fun scrSelect(t: Tab) { val i = tabs.indexOf(t); if (i >= 0) select(i) }
    internal fun scrSay(m: String, long: Boolean = false) = say(m, long)
    internal fun scrConfirm(title: String, msg: String, ok: String, f: () -> Unit) = confirm(title, msg, ok, f)
    internal fun scrReturnLabel(ms: Long): String = returnLabel(ms)
    internal fun scrSetUntil(t: Tab, ms: Long) { t.until = ms; save(); tickNow() }
    internal fun scrSave(bytes: ByteArray, name: String) = saveBytes(bytes, name, "application/zip")
    internal fun scrWaitComposer(w: WebView, tries: Int, cb: (Boolean) -> Unit) = waitComposer(w, tries, cb)
    internal fun scrCaptureFull(w: WebView?, progress: (String) -> Unit, cb: (java.io.File?, String?, JSONObject?) -> Unit) =
        captureFull(w, progress, cb)

    internal fun scrAttach(t: Tab, f: java.io.File, cb: (Boolean) -> Unit) {
        // o caminho principal (injetar o arquivo na página) não precisa do FileProvider: se ele recusar a pasta,
        // registra o motivo e segue com um Uri simples (usado só no último recurso, o seletor de arquivos)
        val uri = try {
            androidx.core.content.FileProvider.getUriForFile(this, packageName + ".fileprovider", f)
        } catch (e: Exception) {
            Script.log(this, "FileProvider recusou ${f.name}: ${(e.message ?: e.javaClass.simpleName).take(90)}")
            Uri.fromFile(f)
        }
        try {
            attachZip(t, f, uri, cb)
        } catch (e: Exception) {
            Script.log(this, "Erro ao anexar ${f.name}: ${(e.message ?: e.javaClass.simpleName).take(90)}")
            cb(false)
        }
    }

    /** Abre um chat novo na aba e espera a caixa de mensagem aparecer. */
    internal fun scrOpenNew(t: Tab, alive: () -> Boolean = { true }, cb: (Boolean) -> Unit) {
        val w = webFor(t)
        t.holdUntil = System.currentTimeMillis() + 20_000L
        w.loadUrl("https://claude.ai/new")
        scrWaitReady(w, "claude.ai/new", alive, cb)
    }

    /**
     * Espera a página ficar pronta, sem tempo máximo: saiu da página antiga (ou chegou em /new), página completa
     * e caixa de mensagem presente em leituras seguidas. Assim que fica pronta, segue.
     * Só desiste se o Script for parado (alive() = false).
     */
    internal fun scrWaitReady(w: WebView, urlPart: String?, alive: () -> Boolean, cb: (Boolean) -> Unit) {
        var stable = 0
        val before = w.url ?: ""
        fun check() {
            if (!alive()) return
            val u = w.url ?: ""
            // com urlPart: chegou nela, ou ao menos saiu da página de antes (o Claude pode redirecionar)
            val urlOk = urlPart == null || u.contains(urlPart) || (u != before && u.contains("claude.ai"))
            val loaded = w.progress >= 100
            w.evaluateJavascript("(function(){return document.readyState==='complete'&&document.querySelector('div[contenteditable=\"true\"]')?'yes':'no';})();") { r ->
                if (!alive()) return@evaluateJavascript
                if (urlOk && r?.contains("yes") == true) stable++ else stable = 0
                // carregamento em 100% e 2 leituras boas — ou 8 leituras boas mesmo que o WebView não marque 100%
                if ((loaded && stable >= 2) || stable >= 8) { cb(true); return@evaluateJavascript }
                w.postDelayed({ check() }, 400)
            }
        }
        w.postDelayed({ check() }, 400)
    }

    /**
     * Estado dos anexos na caixa: "yes" (todos na página e sem envio em andamento), "busy" (subindo) ou "no".
     * A checagem de "subindo" olha só ao redor do próprio anexo, nunca a página toda.
     */
    internal fun scrAttachState(w: WebView, names: List<String>, checkBusy: Boolean = true, cb: (String) -> Unit) {
        val probes = names.map { n -> n.substringBeforeLast('.').let { if (it.length > 20) it.substring(0, 20) else it }.ifBlank { n } }
        val arr = JSONArray(probes).toString()
        val js = "(function(ns,cb){var t=document.body.innerText||'';for(var i=0;i<ns.length;i++){if(t.indexOf(ns[i])<0)return 'no';}" +
            "if(!cb)return 'yes';" +
            "for(var j=0;j<ns.length;j++){var wk=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,null);var nd,el=null;" +
            "while((nd=wk.nextNode())){if(nd.nodeValue&&nd.nodeValue.indexOf(ns[j])>=0&&nd.parentElement){el=nd.parentElement;break;}}" +
            "var p=el;for(var k=0;k<4&&p&&p.parentElement;k++)p=p.parentElement;" +
            "if(p&&p.querySelector('[role=progressbar],[aria-busy=true],progress'))return 'busy';}" +
            "return 'yes';})($arr,$checkBusy);"
        w.evaluateJavascript(js) { r -> cb(if (r?.contains("yes") == true) "yes" else if (r?.contains("busy") == true) "busy" else "no") }
    }

    /** Os nomes estão na página? (checkBusy: e nenhum envio de arquivo em andamento) */
    internal fun scrAttachedOk(w: WebView, names: List<String>, checkBusy: Boolean = true, cb: (Boolean) -> Unit) {
        scrAttachState(w, names, checkBusy) { cb(it == "yes") }
    }

    /** Lê a página da aba (guarda a conversa e atualiza o limite). cb(leitura, contaEmLimiteAgora). */
    internal fun scrSnap(t: Tab, cb: (Handoff.Snap?, Boolean) -> Unit) {
        val w = webs[t.id]
        if (w == null || System.currentTimeMillis() < t.holdUntil) { cb(null, false); return }
        w.evaluateJavascript(Handoff.CAPTURE_JS) { raw ->
            val snap = Handoff.parse(raw)
            var active = false
            if (snap != null) {
                Handoff.save(this, t.id, snap)
                if (snap.limit) {
                    active = applyLimitTime(t, snap.until)
                } else {
                    limitAsked.remove(t.id); limitLearned.remove(t.id)
                    if (t.limitSrc.isNotEmpty()) { t.limitSrc = ""; save() }
                }
            }
            cb(snap, active)
        }
    }

    /** Mostra o resultado do script (ZIPs para enviar) se ele terminou e ainda não foi visto. */
    internal fun scrShowPending() {
        if (isFinishing || isDestroyed) return
        if (Script.status != Script.DONE || Script.resultShown) return
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
        scr.ui.showResults()
    }

    // ───────────── Uso das contas (0–100%) ─────────────

    internal val usageMon: UsageMonitor by lazy { UsageMonitor(this) }
    internal val autoFix: AutoFix by lazy { AutoFix(this) }

    private fun usageSub(): String {
        val parts = tabs.mapNotNull { t ->
            val i = Usage.map[t.id]
            if (t.until > System.currentTimeMillis()) "${t.name} 100%"
            else if (i != null && i.err.isEmpty() && i.s >= 0) "${t.name} ${i.s}%"
            else if (Est.known(t.id)) "${t.name} ~${Est.percent(this, t.id)}%"
            else null
        }
        return if (parts.isEmpty()) "Veja quanto cada conta já usou, ao vivo" else parts.joinToString(" · ")
    }

    /** WebView escondida com o mesmo login da aba, só para ler a página de uso. */
    internal fun scrProbe(t: Tab): WebView {
        val w = WebView(this)
        if (profilesOk) { try { WebViewCompat.setProfile(w, "p_" + t.id) } catch (e: Exception) { } }
        val s = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = false
        s.userAgentString = s.userAgentString.replace("; wv", "").replace(Regex("Version/\\d+\\.\\d+ "), "")
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.alpha = 0f
        findViewById<ViewGroup>(android.R.id.content).addView(w, FrameLayout.LayoutParams(1, 1))
        return w
    }

    internal fun scrProbeDone(w: WebView) {
        try { (w.parent as? ViewGroup)?.removeView(w); w.destroy() } catch (_: Exception) { }
    }

    // ───────────── Colar texto grande → arquivo → anexo no Claude ─────────────

    private fun pasteLargeText() {
        val t = tabs.getOrNull(cur) ?: return
        val w = webs[t.id]
        if (w == null || !(w.url ?: "").contains("claude.ai")) {
            say("Abra o Claude nesta aba primeiro", true)
            return
        }
        val input = android.widget.EditText(this).apply {
            hint = "Cole o texto aqui"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 8
            maxLines = 14
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            isVerticalScrollBarEnabled = true
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = android.widget.FrameLayout(this).apply {
            setPadding(pad, 8, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Colar texto grande")
            .setMessage("Vira um arquivo .txt e é anexado no chat desta aba.")
            .setView(box)
            .setPositiveButton("Anexar") { _, _ -> sendTextAsFile(t, input.text?.toString().orEmpty()) }
            .setNeutralButton("Usar área de transferência") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = try { cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() } catch (_: Exception) { null }
                sendTextAsFile(t, clip.orEmpty())
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun sendTextAsFile(t: Tab, text: String) {
        if (text.isBlank()) { say("Nada para anexar", true); return }
        val w = webs[t.id] ?: return
        val popup = BuildPopup(this, t.name, "Anexar texto")
        val ui = HandoffUi(popup)
        popup.show()
        popup.progress(10)
        popup.status("Criando o arquivo…")
        val kb = text.toByteArray(Charsets.UTF_8).size / 1024
        ui.set("txt", "✓", "Texto recebido (${text.length} caracteres, $kb KB)")
        ui.set("file", "⏳", "Criando o arquivo")
        Thread {
            val dir = java.io.File(cacheDir, "handoff_out").apply { mkdirs() }
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
            val f = java.io.File(dir, "texto-colado-$stamp.txt")
            val ok = try { f.writeText(text); true } catch (_: Exception) { false }
            runOnUiThread {
                if (!ok) {
                    ui.set("file", "✗", "Não consegui criar o arquivo")
                    popup.status("Falhou ✗")
                    popup.done(false, null, null)
                    return@runOnUiThread
                }
                ui.set("file", "✓", "Arquivo ${f.name} criado")
                popup.progress(50)
                popup.status("Anexando no Claude…")
                ui.set("attach", "⏳", "Anexando ao chat")
                try {
                    val uri = androidx.core.content.FileProvider.getUriForFile(this, packageName + ".fileprovider", f)
                    attachZip(t, f, uri) { done ->
                        if (done) {
                            ui.set("attach", "✓", "Anexado ao chat")
                            popup.progress(100)
                            popup.status("Pronto ✓ Escreva sua mensagem e envie")
                            popup.done(true, null, null)
                        } else {
                            ui.set("attach", "✗", "Não anexou sozinho: toque em + e anexe ${f.name}")
                            popup.status("Quase: falta só anexar")
                            popup.done(false, null, null)
                        }
                    }
                } catch (e: Exception) {
                    ui.set("attach", "✗", "Erro: ${e.message}")
                    popup.done(false, null, null)
                }
            }
        }.start()
    }

    /** Lista de etapas (✓ ⏳ ✗) mostrada no popup da continuação. */
    private class HandoffUi(val popup: BuildPopup) {
        private val lines = LinkedHashMap<String, String>()
        @Synchronized
        fun set(key: String, icon: String, text: String) {
            lines[key] = "$icon $text"
            popup.steps(lines.values.joinToString("\n"))
        }
    }

    private class HfSession(val dir: java.io.File, val progress: (String) -> Unit) {
        @Volatile var onEnd: ((String) -> Unit)? = null
    }
    private val hfSessions = java.util.concurrent.ConcurrentHashMap<String, HfSession>()

    private fun hfWrite(dir: java.io.File, name: String, bytes: ByteArray) {
        val parts = name.replace('\\', '/').split('/')
            .map { it.replace(Regex("[^\\w.\\-]+"), "_").trim('.') }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return
        var d = dir
        parts.dropLast(1).forEach { d = java.io.File(d, it) }
        d.mkdirs()
        java.io.File(d, parts.last()).writeBytes(bytes)
    }

    /**
     * Salva o chat inteiro da aba: abre todos os blocos, guarda texto, HTML, imagens, links e arquivos.
     * cb(pasta, últimaResposta, resumo) — pasta é null se não deu para ler o chat.
     */
    private fun captureFull(w: WebView?, progress: (String) -> Unit, cb: (java.io.File?, String?, JSONObject?) -> Unit) {
        if (w == null || !(w.url ?: "").contains("claude.ai/chat")) { cb(null, null, null); return }
        val tok = java.util.UUID.randomUUID().toString()
        val root = java.io.File(cacheDir, "handoff_full").apply { mkdirs() }
        root.listFiles()?.forEach { it.deleteRecursively() }
        val dir = java.io.File(root, tok).apply { mkdirs() }
        val sess = HfSession(dir, progress)
        hfSessions[tok] = sess
        var finished = false
        val finish = { summary: String ->
            if (!finished) {
                finished = true
                hfSessions.remove(tok)
                val lastFile = java.io.File(dir, "ultima-resposta.txt")
                val last = if (lastFile.exists()) lastFile.readText().takeLast(300_000) else null
                val o = try { JSONObject(summary) } catch (_: Exception) { null }
                val has = dir.listFiles()?.isNotEmpty() == true
                cb(if (has) dir else null, last, o)
            }
        }
        sess.onEnd = { sm -> runOnUiThread { finish(sm) } }
        handler.postDelayed({ finish("") }, 150_000)
        w.evaluateJavascript(Handoff.FULL_JS.replace("__TOKEN__", tok)) { r ->
            if (r?.contains("nobridge") == true) finish("")
        }
    }

    /** to = conta de destino; se for null, só salva o chat completo em Downloads. */
    private fun startHandoff(from: Tab, to: Tab?) {
        val proj = Projects.current(this)
        val token = Projects.token(this)
        val popup = BuildPopup(this, proj?.name ?: from.name, if (to != null) "Continuar em ${to.name}" else "Salvar chat completo")
        val ui = HandoffUi(popup)
        popup.show()
        popup.status("Preparando…")
        popup.progress(5)
        val info = Handoff.chatInfo(this, from.id)
        if (info != null) ui.set("chat", "✓", "Conversa salva ($info)")
        else ui.set("chat", "–", "Sem conversa salva nesta aba")
        ui.set("full", "⏳", "Salvando o chat completo (blocos, imagens, links)")
        popup.status("Salvando o chat completo…")
        captureFull(webs[from.id], { msg -> ui.set("full", "⏳", msg); popup.status(msg) }) { dir, unfinished, sum ->
            if (dir == null) {
                ui.set("full", "–", "Não consegui ler o chat completo (uso a conversa salva)")
            } else {
                val imgs = sum?.optInt("imgs") ?: 0
                val files = sum?.optInt("files") ?: 0
                val chars = sum?.optInt("chars") ?: 0
                ui.set("full", "✓", "Chat completo: $imgs imagem(ns), $files arquivo(s), ${chars / 1000} mil caracteres")
                val err = sum?.optString("err") ?: ""
                if (err.isNotBlank()) ui.set("fullerr", "✗", "Parte do chat falhou: $err")
            }
            popup.progress(30)
            ui.set("pack", "⏳", "Montando o pacote")
            Thread {
                val f = try {
                    Handoff.build(this, from.name, from.id, proj, token, unfinished, dir, if (to != null) "continuar" else "chat") { st ->
                        popup.status(st)
                        ui.set("pack", "⏳", st)
                    }
                } catch (e: Exception) { null }
                var saved = false
                if (f != null) {
                    try { saveBytes(f.readBytes(), f.name, "application/zip"); saved = true } catch (_: Exception) { }
                }
                runOnUiThread {
                    if (f == null) {
                        ui.set("pack", "✗", "Não consegui montar o pacote")
                        popup.status("Falhou ✗ (sem conversa e sem projeto)")
                        popup.done(false, null, null)
                        return@runOnUiThread
                    }
                    val mb = String.format(java.util.Locale.US, "%.1f MB", f.length() / 1048576.0)
                    ui.set("pack", "✓", "Pacote montado ($mb)")
                    if (saved) ui.set("save", "✓", "ZIP salvo em Downloads") else ui.set("save", "✗", "Não consegui salvar em Downloads")
                    popup.progress(45)
                    if (to != null) {
                        openInTarget(to, f, ui, popup)
                    } else {
                        popup.progress(100)
                        popup.status("Pronto ✓ ${f.name} em Downloads")
                        popup.done(saved, null, null)
                        say("Chat completo salvo em Downloads", true)
                    }
                }
            }.start()
        }
    }

    /** Abre os blocos "executou N comandos" da última resposta (até 3 níveis) e lê o texto completo. */
    private fun recoverUnfinished(w: WebView?, cb: (String?) -> Unit) {
        if (w == null || (w.url ?: "").contains("claude.ai/chat").not()) { cb(null); return }
        fun read() {
            w.evaluateJavascript(Handoff.READ_LAST_JS) { raw ->
                val t = Handoff.unquote(raw)
                cb(if (t.isNullOrBlank()) null else t.takeLast(300_000))
            }
        }
        fun round(n: Int) {
            w.evaluateJavascript(Handoff.EXPAND_JS) { r ->
                val opened = r?.trim('"')?.toIntOrNull() ?: 0
                if (opened > 0 && n < 3) w.postDelayed({ round(n + 1) }, 900)
                else w.postDelayed({ read() }, 600)
            }
        }
        round(1)
    }

    /** Anexa o ZIP: 1) direto no campo de arquivo da página, 2) arrastar-e-soltar, 3) toques no menu. */
    private fun attachZip(to: Tab, f: java.io.File, uri: Uri, result: (Boolean) -> Unit) {
        val w = webs[to.id]
        if (w == null) { result(false); return }
        attLog("Anexando ${f.name} (${f.length() / 1024} KB)")
        if (f.length() > 25L * 1024 * 1024) { attachWithRetries(to, uri, 1, result); return }
        Thread {
            val b64 = try { android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP) } catch (e: Exception) { null }
            runOnUiThread {
                if (b64 == null) { attachWithRetries(to, uri, 1, result); return@runOnUiThread }
                w.evaluateJavascript("window.__hf='';", null)
                var i = 0
                while (i < b64.length) {
                    val part = b64.substring(i, minOf(i + 200_000, b64.length))
                    w.evaluateJavascript("window.__hf+='$part';", null)
                    i += 200_000
                }
                val mime = when (f.extension.lowercase()) {
                    "txt" -> "text/plain"
                    "md" -> "text/markdown"
                    "zip" -> "application/zip"
                    else -> "application/octet-stream"
                }
                w.evaluateJavascript(Handoff.BUILD_JS.replace("__NAME__", JSONObject.quote(f.name)).replace("__MIME__", JSONObject.quote(mime)), null)
                val cleanup = { w.evaluateJavascript("window.__hf='';window.__hfDt=null;", null) }
                tryInject(w, f.name, "input") { ok1 ->
                    if (ok1) { cleanup(); attLog("Anexo ${f.name}: entrou pelo campo de arquivo"); result(true) }
                    else {
                        attLog("Anexo ${f.name}: campo de arquivo não funcionou; tentando arrastar")
                        tryInject(w, f.name, "drop") { ok2 ->
                            cleanup()
                            if (ok2) { attLog("Anexo ${f.name}: entrou arrastando"); result(true) }
                            else { attLog("Anexo ${f.name}: arrastar não funcionou; tentando o menu de anexar"); attachWithRetries(to, uri, 1, result) }
                        }
                    }
                }
            }
        }.start()
    }

    private fun attLog(s: String) { if (Script.status == Script.RUNNING) Script.log(this, s) }

    private fun tryInject(w: WebView, name: String, mode: String, cb: (Boolean) -> Unit) {
        w.evaluateJavascript(Handoff.INJECT_JS.replace("__MODE__", mode), null)
        // o Claude mostra o nome do anexo sem ".zip" e às vezes cortado: procura só o começo, sem a extensão
        val probe = name.substringBeforeLast('.').let { if (it.length > 20) it.substring(0, 20) else it }.ifBlank { name }
        fun look(n: Int) {
            w.evaluateJavascript("(function(n){return document.body.innerText.indexOf(n)>=0?'yes':'no';})(" + JSONObject.quote(probe) + ");") { r ->
                if (r?.contains("yes") == true) cb(true)
                else if (n <= 0) cb(false)
                else w.postDelayed({ look(n - 1) }, 1000)
            }
        }
        w.postDelayed({ look(9) }, 2000)
    }

    private val FIND_PLUS_JS = """(function(){
function c(el){var r=el.getBoundingClientRect();if(r.width<4||r.height<4)return null;return (r.left+r.width/2)+','+(r.top+r.height/2)+','+window.innerWidth;}
var bad=/send|enviar|mic|voz|voice|audio|dict|stop|parar|model|modelo/i;
var sels=['button[aria-label*="attach" i]','button[aria-label*="Upload" i]','button[aria-label*="Add files" i]','button[aria-label*="Anexar" i]','button[aria-label*="Adicionar" i]','button[data-testid*="attach" i]','button[data-testid*="file" i]','[data-testid="input-menu-plus"]'];
for(var i=0;i<sels.length;i++){var b=document.querySelector(sels[i]);if(b){var s=c(b);if(s)return s;}}
var p=document.querySelector('div[contenteditable="true"]');
for(var k=0;k<8&&p;k++){p=p.parentElement;if(!p)break;var bs=p.querySelectorAll('button');if(bs.length>=2){
var best=null,bl=1e9;
for(var j=0;j<bs.length;j++){var a=(bs[j].getAttribute('aria-label')||'')+' '+(bs[j].innerText||'');if(bad.test(a))continue;var r=bs[j].getBoundingClientRect();if(r.width<4)continue;if(r.left<bl){bl=r.left;best=bs[j];}}
if(best){var s2=c(best);if(s2)return s2;}}}
return 'none';})();"""

    private val FIND_ITEM_JS = """(function(){
function c(el){var r=el.getBoundingClientRect();if(r.width<4||r.height<4)return null;return (r.left+r.width/2)+','+(r.top+r.height/2)+','+window.innerWidth;}
var re=/(upload|arquivo|file|photo|foto|anex|attach)/i,no=/(drive|github|conector|connector|projeto|project|screenshot|captura)/i;
var els=document.querySelectorAll('[role=menuitem],[role=option],[data-radix-collection-item],[role=menu] button,[role=menu] div');
for(var i=0;i<els.length;i++){var t=((els[i].innerText||'')+' '+(els[i].getAttribute('aria-label')||'')).trim();
if(t.length>0&&t.length<70&&re.test(t)&&!no.test(t)){var s=c(els[i]);if(s)return s;}}
return 'none';})();"""

    /** Toque de verdade (com "gesto do usuário") no elemento que o JS encontrar. */
    private fun tapByJs(w: WebView, js: String, done: (Boolean) -> Unit) {
        w.evaluateJavascript(js) { raw ->
            val p = (raw ?: "none").trim('"').split(',')
            if (p.size < 3) {
                done(false)
            } else {
                try {
                    val iw = p[2].toFloat()
                    val k = if (iw > 0) w.width / iw else 1f
                    val x = p[0].toFloat() * k
                    val y = p[1].toFloat() * k
                    val t0 = android.os.SystemClock.uptimeMillis()
                    val d = android.view.MotionEvent.obtain(t0, t0, android.view.MotionEvent.ACTION_DOWN, x, y, 0)
                    w.dispatchTouchEvent(d); d.recycle()
                    w.postDelayed({
                        val u = android.view.MotionEvent.obtain(t0, android.os.SystemClock.uptimeMillis(), android.view.MotionEvent.ACTION_UP, x, y, 0)
                        w.dispatchTouchEvent(u); u.recycle()
                        done(true)
                    }, 80)
                } catch (_: Exception) { done(false) }
            }
        }
    }

    /** Tenta abrir o seletor de arquivos do Claude e entregar o ZIP; confere se o seletor foi usado. */
    private fun attachWithRetries(to: Tab, uri: Uri, attempt: Int, result: (Boolean) -> Unit) {
        val w = webs[to.id]
        if (w == null) { result(false); return }
        pendingClaudeFile = arrayOf(uri)
        if (attempt == 0) {
            w.evaluateJavascript("(function(){var i=document.querySelector('input[type=file]');if(i){i.click();return 'input';}return 'none';})();", null)
        } else {
            tapByJs(w, FIND_ITEM_JS) { found ->
                if (!found) tapByJs(w, FIND_PLUS_JS) { _ ->
                    w.postDelayed({ tapByJs(w, FIND_ITEM_JS) { } }, 900)
                }
            }
        }
        w.postDelayed({
            when {
                pendingClaudeFile == null -> { attLog("Anexo: o seletor de arquivos recebeu o ZIP (tentativa $attempt)"); result(true) }
                attempt < 3 -> attachWithRetries(to, uri, attempt + 1, result)
                else -> { pendingClaudeFile = null; attLog("Anexo: não consegui por nenhum caminho; vou tentar de novo"); result(false) }
            }
        }, if (attempt == 0) 2000L else 3500L)
    }

    /** Espera a caixa de mensagem aparecer (até ~14s). */
    private fun waitComposer(w: WebView, tries: Int, cb: (Boolean) -> Unit) {
        w.evaluateJavascript("(function(){return document.querySelector('div[contenteditable=\"true\"]')?'yes':'no';})();") { r ->
            if (r?.contains("yes") == true) cb(true)
            else if (tries <= 0) cb(false)
            else w.postDelayed({ waitComposer(w, tries - 1, cb) }, 700)
        }
    }

    /** Abre um chat novo na aba de destino, escreve a mensagem inicial e anexa o ZIP. */
    private fun openInTarget(to: Tab, f: java.io.File, ui: HandoffUi, popup: BuildPopup) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(this, packageName + ".fileprovider", f)
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("continuar", Handoff.STARTER))
            val idx = tabs.indexOf(to)
            if (idx < 0) return
            select(idx)
            val w = webs[to.id] ?: return
            ui.set("open", "⏳", "Abrindo \"${to.name}\"")
            popup.status("Abrindo a outra conta…")
            popup.progress(55)
            w.loadUrl("https://claude.ai/new")
            val fill = "(function(){var e=document.querySelector('div[contenteditable=\"true\"]');" +
                "if(!e)return 'none';e.focus();document.execCommand('insertText',false," +
                JSONObject.quote(Handoff.STARTER) + ");return 'ok';})();"
            w.postDelayed({
                waitComposer(w, 20) { ready ->
                    if (!ready) {
                        ui.set("open", "✗", "A conta \"${to.name}\" não abriu o chat (está logada?)")
                        popup.status("Falhou ✗ — ZIP está em Downloads")
                        popup.done(false, null, null)
                        return@waitComposer
                    }
                    ui.set("open", "✓", "Conta \"${to.name}\" aberta")
                    popup.progress(70)
                    w.evaluateJavascript(fill) { r ->
                        if (r?.contains("ok") == true) ui.set("fill", "✓", "Mensagem inicial escrita")
                        else ui.set("fill", "✗", "Não escrevi a mensagem (já copiei: cole no chat)")
                        popup.progress(80)
                        ui.set("attach", "⏳", "Anexando o ZIP")
                        popup.status("Anexando o ZIP…")
                        attachZip(to, f, uri) { ok ->
                            if (ok) {
                                ui.set("attach", "✓", "ZIP anexado")
                                popup.progress(100)
                                popup.status("Tudo pronto ✓ Revise e envie a mensagem")
                                popup.done(true, null, null)
                                say("Pronto! Revise e envie a mensagem.", true)
                            } else {
                                ui.set("attach", "✗", "Não anexou sozinho: toque em + e anexe ${f.name} (Downloads)")
                                popup.status("Quase: falta só anexar o ZIP")
                                popup.done(false, null, null)
                            }
                        }
                    }
                }
            }, 2500)
        } catch (e: Exception) {
            ui.set("open", "✗", "Erro: ${e.message}")
            popup.status("Falhou ✗ — ZIP está em Downloads")
            popup.done(false, null, null)
        }
    }

    private fun projectPicker() {
        val list = Projects.load(this)
        val curId = Projects.currentId(this)
        val sh = Sheet(this).title("Projetos", "Para onde vão os ZIPs que você baixa do Claude")
        if (list.isEmpty()) sh.note("Nenhum projeto ainda. Adicione um repositório do GitHub para ativar a entrega automática.")
        list.forEachIndexed { idx, p ->
            val br = if (p.branch.isBlank()) "branch padrão" else p.branch
            sh.row(
                R.drawable.ic_code, p.name, "${p.fullRepo()} · $br/${p.folder}",
                highlighted = p.id == curId, onMore = { projectOptions(idx) }
            ) {
                Projects.setCurrentId(this, p.id)
                say("Projeto atual: ${p.name}")
            }
        }
        sh.section("Adicionar")
        sh.row(R.drawable.ic_add, "Do GitHub", "Escolha na lista dos seus repositórios") { pickRepoFromGitHub() }
        sh.row(R.drawable.ic_edit, "Manualmente", "Informe dono, repositório e pasta") { editProject(-1) }
        sh.show()
    }

    /** Lista os repositórios da sua conta e preenche tudo automaticamente. */
    private fun pickRepoFromGitHub() {
        val token = Projects.token(this)
        if (token.isBlank()) {
            say("Configure o Token do GitHub em Configurações primeiro", true)
            editToken()
            return
        }
        say("Carregando repositórios…")
        Thread {
            try {
                val repos = GitHubRepos.listMyRepos(token)
                runOnUiThread {
                    if (repos.isEmpty()) {
                        say("Nenhum repositório encontrado (confira o token)", true)
                        return@runOnUiThread
                    }
                    val sh = Sheet(this).title("Escolha o repositório", "${repos.size} encontrados na sua conta")
                    repos.forEach { r ->
                        sh.row(R.drawable.ic_code, r.name, r.description.ifBlank { null }) {
                            say("Detectando package de ${r.name}…")
                            Thread {
                                val pkg = try {
                                    GitHubRepos.detectPackage(token, r.owner, r.name, r.defaultBranch)
                                } catch (_: Exception) { "" }
                                val hasInc = try {
                                    GitHubRepos.hasIncoming(token, r.owner, r.name, r.defaultBranch)
                                } catch (_: Exception) { false }
                                runOnUiThread { addProjectFromRepo(r, pkg, hasInc) }
                            }.start()
                        }
                    }
                    sh.show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    say("Erro ao listar: ${e.message}", true)
                }
            }
        }.start()
    }

    private fun addProjectFromRepo(r: GitHubRepos.RepoInfo, pkg: String, hasIncoming: Boolean) {
        val list = Projects.load(this)
        // se já existe o mesmo repo, só seleciona
        val existing = list.indexOfFirst { it.owner == r.owner && it.repo == r.name }
        if (existing >= 0) {
            Projects.setCurrentId(this, list[existing].id)
            if (pkg.isNotBlank() && list[existing].packageName.isBlank()) {
                list[existing].packageName = pkg
                Projects.save(this, list)
            }
            say("Já cadastrado — projeto atual: ${list[existing].name}")
            return
        }
        val p = Project(
            id = System.currentTimeMillis().toString(),
            name = r.name,
            owner = r.owner,
            repo = r.name,
            branch = r.defaultBranch,
            folder = "_incoming",
            packageName = pkg
        )
        list.add(p)
        Projects.save(this, list)
        Projects.setCurrentId(this, p.id)
        val extra = buildString {
            append("Projeto ${p.name} pronto")
            if (pkg.isNotBlank()) append("\nPackage: $pkg")
            else append("\nPackage não detectado (pode editar depois)")
            if (!hasIncoming) append("\nObs: pasta _incoming não encontrada — o workflow precisa dela")
        }
        MaterialAlertDialogBuilder(this).setTitle("Projeto adicionado")
            .setMessage(extra)
            .setPositiveButton("OK", null)
            .setNeutralButton("Editar") { _, _ ->
                val idx = Projects.load(this).indexOfFirst { it.id == p.id }
                if (idx >= 0) editProject(idx)
            }
            .show()
    }

    private fun projectOptions(i: Int) {
        val list = Projects.load(this)
        if (i < 0 || i >= list.size) return
        val p = list[i]
        val sh = Sheet(this).title(p.name, p.fullRepo())
        sh.row(R.drawable.ic_check, "Usar este projeto") {
            Projects.setCurrentId(this, p.id)
            say("Projeto atual: ${p.name}")
        }
        sh.row(R.drawable.ic_play, "Abrir jogo", p.packageName.ifBlank { "Package não definido" }) {
            Projects.setCurrentId(this, p.id)
            openCurrentGame()
        }
        sh.row(R.drawable.ic_download, "Baixar APK mais recente") {
            Projects.setCurrentId(this, p.id)
            downloadLatestApkNow()
        }
        sh.row(R.drawable.ic_edit, "Editar") { editProject(i) }
        sh.row(R.drawable.ic_delete, "Remover", "Só tira da lista; o repositório não é apagado", danger = true) {
            confirm("Remover ${p.name}?", "O repositório no GitHub não será apagado.", "Remover") {
                val id = p.id
                val n = Projects.load(this)
                n.removeAll { it.id == id }
                Projects.save(this, n)
                if (Projects.currentId(this) == id) Projects.setCurrentId(this, n.firstOrNull()?.id ?: "")
                say("Removido")
            }
        }
        sh.show()
    }

    private fun editProject(i: Int) {
        val list = Projects.load(this)
        val existing = if (i >= 0 && i < list.size) list[i] else null
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(24), dp(8), dp(24), 0)
        fun field(hint: String, value: String): EditText {
            val (til, e) = Ui.field(this, hint, value)
            box.addView(til)
            return e
        }
        val n = field("Nome do projeto", existing?.name ?: "")
        val owner = field("Dono do repositório", existing?.owner ?: "viniciusy30-commits")
        val repo = field("Repositório (ex: masmorras-rpg)", existing?.repo ?: "")
        val branch = field("Branch (vazio = padrão do GitHub)", existing?.branch ?: "")
        val folder = field("Pasta do ZIP (padrão _incoming)", existing?.folder ?: "_incoming")
        val pkg = field("Package do jogo (ex: com.vinicius.masmorras)", existing?.packageName ?: "")
        val scroll = ScrollView(this)
        scroll.addView(box)
        MaterialAlertDialogBuilder(this).setTitle(if (existing == null) "Novo projeto" else "Editar projeto")
            .setView(scroll)
            .setPositiveButton("Salvar") { _, _ ->
                val name = n.text.toString().trim().ifEmpty { repo.text.toString().trim() }
                val r = repo.text.toString().trim()
                if (r.isEmpty()) {
                    say("Informe o repositório")
                    return@setPositiveButton
                }
                val p = existing ?: Project(System.currentTimeMillis().toString(), name, owner.text.toString().trim(), r)
                p.name = name
                p.owner = owner.text.toString().trim().ifEmpty { "viniciusy30-commits" }
                p.repo = r
                p.branch = branch.text.toString().trim()
                p.folder = folder.text.toString().trim().ifEmpty { "_incoming" }
                p.packageName = pkg.text.toString().trim()
                val nlist = list.toMutableList()
                if (existing == null) nlist.add(p) else nlist[i] = p
                Projects.save(this, nlist)
                Projects.setCurrentId(this, p.id)
                say("Projeto salvo: ${p.name}")
            }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun loadFavs() {
        try {
            val a = JSONArray(prefs.getString("favs", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                favs.add(Fav(o.getString("name"), o.getString("url"), o.optInt("mode", 0)))
            }
        } catch (e: Exception) { }
        try {
            val d = JSONArray(prefs.getString("dock_actions", "[]"))
            for (i in 0 until d.length()) dockIds.add(d.getString(i))
        } catch (e: Exception) { }
        favAdapter.notifyDataSetChanged()
    }

    private fun saveDock() {
        val d = JSONArray(); dockIds.forEach { d.put(it) }
        prefs.edit().putString("dock_actions", d.toString()).apply()
        favAdapter.notifyDataSetChanged()
    }

    /** "+" da barra: novo favorito (link) ou escolher quais botões de ação ficam na barra. */
    private fun addToDock() {
        val sh = Sheet(this).title("Barra de baixo", "Favoritos e botões de ação ficam aqui")
        sh.row(R.drawable.ic_star, "Novo favorito", "Um link para abrir rápido") { editFav(-1) }
        sh.row(R.drawable.ic_bolt, "Escolher botões de ação", "Enviar main, ver script, relatório, baixar APK…") { pickDockActions() }
        sh.show()
    }

    private fun pickDockActions() {
        val all = allDockActs()
        val checked = BooleanArray(all.size) { dockIds.contains(all[it].id) }
        MaterialAlertDialogBuilder(this).setTitle("Quais botões aparecem na barra?")
            .setMultiChoiceItems(all.map { it.label }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setPositiveButton("Salvar") { _, _ ->
                val keep = dockIds.filter { id -> all.indexOfFirst { it.id == id }.let { it >= 0 && checked[it] } }
                val added = all.indices.filter { checked[it] && !keep.contains(all[it].id) }.map { all[it].id }
                dockIds.clear(); dockIds.addAll(keep + added)
                saveDock()
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun dockActOptions(a: DockAct) {
        val sh = Sheet(this).title(a.label, "Botão de ação da barra")
        sh.row(R.drawable.ic_delete, "Tirar da barra", danger = true) { dockIds.remove(a.id); saveDock() }
        sh.row(R.drawable.ic_bolt, "Escolher outros botões") { pickDockActions() }
        sh.show()
    }

    private fun saveFavs() {
        val a = JSONArray()
        favs.forEach { a.put(JSONObject().put("name", it.name).put("url", it.url).put("mode", it.mode)) }
        prefs.edit().putString("favs", a.toString()).apply()
    }

    private fun editFav(i: Int) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(24), dp(8), dp(24), 0)
        val (nTil, n) = Ui.field(this, "Nome", if (i >= 0) favs[i].name else "")
        val (uTil, u) = Ui.field(this, "Link", if (i >= 0) favs[i].url else tabs[cur].url)
        u.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        val lbl = TextView(this)
        lbl.text = "Como abrir"
        lbl.textSize = 12f
        lbl.setTextColor(Color.parseColor("#8B92A1"))
        val sp = Spinner(this)
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            arrayOf("Abrir na conta da aba", "Sessão compartilhada do app", "Abrir pelo Chrome"))
        sp.setSelection(if (i >= 0) favs[i].mode else 0)
        box.addView(nTil); box.addView(uTil); box.addView(lbl); box.addView(sp)
        MaterialAlertDialogBuilder(this).setTitle(if (i >= 0) "Editar favorito" else "Novo favorito").setView(box)
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
        val f = favs[i]
        val sh = Sheet(this).title(f.name, f.url)
        sh.row(R.drawable.ic_edit, "Editar") { editFav(i) }
        sh.row(R.drawable.ic_delete, "Remover", danger = true) {
            favs.remove(f); saveFavs(); favAdapter.notifyDataSetChanged()
        }
        sh.show()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun openFav(f: Fav) {
        closeFav()
        if (f.mode == 2) {
            try { androidx.browser.customtabs.CustomTabsIntent.Builder().build().launchUrl(this, Uri.parse(f.url)) }
            catch (e: Exception) { say("Não foi possível abrir pelo Chrome") }
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
                if (sc == "http" || sc == "https") return githubToChrome(v, r)
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
                return openFileChooser(p)
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

    inner class FavAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        inner class VH(val v: View) : RecyclerView.ViewHolder(v) {
            val av: TextView = v.findViewById(R.id.favAvatar)
            val nm: TextView = v.findViewById(R.id.favName)
        }
        inner class AVH(val v: View) : RecyclerView.ViewHolder(v) {
            val ic: ImageView = v.findViewById(R.id.actIcon)
            val nm: TextView = v.findViewById(R.id.actName)
        }
        override fun getItemViewType(position: Int) = if (position < dockActs().size) 1 else 0
        override fun onCreateViewHolder(p: ViewGroup, v: Int): RecyclerView.ViewHolder =
            if (v == 1) AVH(layoutInflater.inflate(R.layout.item_action, p, false))
            else VH(layoutInflater.inflate(R.layout.item_tab, p, false))
        override fun getItemCount() = dockActs().size + favs.size
        override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
            val acts = dockActs()
            if (h is AVH) {
                val act = acts.getOrNull(i) ?: return
                h.nm.text = act.label
                h.ic.setImageResource(act.icon)
                h.v.setOnClickListener { act.run() }
                h.v.setOnLongClickListener { dockActOptions(act); true }
            } else if (h is VH) {
                val k = i - acts.size
                val f = favs.getOrNull(k) ?: return
                h.nm.text = f.name + (if (f.mode == 2) " ↗" else "")
                Ui.avatar(h.av, f.name)
                h.v.setOnClickListener { val p = h.bindingAdapterPosition - dockActs().size; if (p >= 0 && p < favs.size) openFav(favs[p]) }
                h.v.setOnLongClickListener { favOptions(h.bindingAdapterPosition - dockActs().size); true }
            }
        }
    }
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() { tickNow(); handler.postDelayed(this, 1000) }
    }

    override fun onResume() {
        super.onResume()
        Updater.autoCheck(this)
        handler.post(ticker)
        if (prefs.getBoolean("keepalive", true)) KeepAliveService.start(this)
        handler.postDelayed({ scrShowPending() }, 600)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
        // Não pausa os WebViews: o KeepAliveService mantém o processo vivo
    }

    override fun onDestroy() {
        handler.removeCallbacks(handoffTick)
        // Se o usuário fechou de verdade (não só girou a tela), para o serviço
        if (isFinishing) KeepAliveService.stop(this)
        super.onDestroy()
    }

    private fun tickNow() {
        val now = System.currentTimeMillis()
        // contas que acabaram de voltar
        val freed = tabs.filter { it.id in coolingIds && it.until <= now }
        for (t in freed) { coolingIds.remove(t.id); onTabFreed(t) }
        tabs.filter { it.until > now }.forEach { coolingIds.add(it.id) }
        // mudou quem está liberada / em recarga → reagrupa
        if (sigNow(now) != rowsSig) refreshRows()
        for (k in 0 until rv.childCount) {
            val v = rv.getChildAt(k)
            val p = rv.getChildAdapterPosition(v)
            val h = rv.getChildViewHolder(v) as? TabAdapter.VH ?: continue
            val r = rows.getOrNull(p) as? Row.Item ?: continue
            h.bindStatus(r.tab)
        }
    }

    private fun onTabFreed(t: Tab) {
        say("\"${t.name}\" liberada ✓", true)
        // limite detectado pelo aviso da página: recarrega para o aviso velho sumir
        if (t.limitSrc.isNotEmpty() && t.limitSrc != CLEARED) {
            t.limitSrc = ""
            t.holdUntil = System.currentTimeMillis() + 20_000L
            save()
            val w = webs[t.id]
            if (w != null && (w.url ?: "").contains("claude.ai")) w.reload()
        }
    }

    /** "volta às 17:30", "volta amanhã às 05:40" ou "volta sex 30/10 às 10:00". */
    private fun returnLabel(ms: Long): String {
        val hm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(ms))
        fun midnight(t: Long): Long {
            val c = java.util.Calendar.getInstance()
            c.timeInMillis = t
            c.set(java.util.Calendar.HOUR_OF_DAY, 0); c.set(java.util.Calendar.MINUTE, 0)
            c.set(java.util.Calendar.SECOND, 0); c.set(java.util.Calendar.MILLISECOND, 0)
            return c.timeInMillis
        }
        val days = Math.round((midnight(ms) - midnight(System.currentTimeMillis())) / 86_400_000.0).toInt()
        return when {
            days <= 0 -> "volta às $hm"
            days == 1 -> "volta amanhã às $hm"
            else -> "volta " + java.text.SimpleDateFormat("EEE dd/MM", java.util.Locale("pt", "BR")).format(java.util.Date(ms)) + " às $hm"
        }
    }

    private fun limitLabel(t: Tab, now: Long): String {
        if (t.until <= now) return "✓  ${t.name}  ·  liberada"
        val left = t.until - now
        val h = left / 3_600_000L
        val m = (left % 3_600_000L) / 60_000L
        val falta = if (h > 0) "${h}h${m.toString().padStart(2, '0')}" else "${m}min"
        return "⏳  ${t.name}  ·  ${returnLabel(t.until)} (falta $falta)"
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
            tabs[i].limitSrc = CLEARED
            save(); tickNow()
        }, c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE), true).show()
    }

    inner class TabAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        inner class VH(val v: View) : RecyclerView.ViewHolder(v) {
            val av: TextView = v.findViewById(R.id.tabAvatar)
            val name: TextView = v.findViewById(R.id.tabName)
            val st: TextView = v.findViewById(R.id.tabStatus)
            val ret: TextView = v.findViewById(R.id.tabReturn)
            fun bindStatus(t: Tab) {
                st.visibility = View.VISIBLE
                val left = t.until - System.currentTimeMillis()
                if (t.until <= 0L || left <= 0L) {
                    st.text = "Liberado" + Usage.chip(t.id); st.setTextColor(Color.parseColor("#4ADE80"))
                    ret.visibility = View.GONE
                } else {
                    st.text = fmt(left); st.setTextColor(Color.parseColor("#E0A58F"))
                    ret.text = returnLabel(t.until)
                    ret.visibility = View.VISIBLE
                }
            }
        }
        inner class HeadVH(val box: LinearLayout, val label: TextView) : RecyclerView.ViewHolder(box)

        override fun getItemViewType(position: Int) = if (rows.getOrNull(position) is Row.Head) 1 else 0
        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(p: ViewGroup, type: Int): RecyclerView.ViewHolder {
            if (type == 1) {
                val box = LinearLayout(p.context)
                box.gravity = android.view.Gravity.CENTER_VERTICAL
                box.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56)).apply { marginEnd = dp(8) }
                val tv = TextView(p.context)
                tv.textSize = 10.5f
                tv.letterSpacing = 0.06f
                tv.setTypeface(tv.typeface, android.graphics.Typeface.BOLD)
                tv.setPadding(dp(10), dp(5), dp(10), dp(5))
                box.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                return HeadVH(box, tv)
            }
            return VH(layoutInflater.inflate(R.layout.item_conta, p, false))
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
            val row = rows.getOrNull(i) ?: return
            if (h is HeadVH && row is Row.Head) {
                val color = Color.parseColor(if (row.free) "#4ADE80" else "#E0A58F")
                h.label.text = (if (row.free) "LIBERADAS" else "EM RECARGA") + " · " + row.count
                h.label.setTextColor(color)
                h.label.background = Ui.shape(this@MainActivity, (0x22 shl 24) or (color and 0xFFFFFF), 12)
                return
            }
            if (h !is VH || row !is Row.Item) return
            val t = row.tab
            h.name.text = t.name
            Ui.avatar(h.av, t.name)
            val bmp = Avatars.get(this@MainActivity, t.id)
            if (bmp != null) {
                h.av.text = ""
                h.av.background = android.graphics.drawable.BitmapDrawable(resources, bmp)
            }
            val sel = tabs.indexOf(t) == cur
            h.v.isSelected = sel
            h.name.setTextColor(if (sel) Color.WHITE else Color.parseColor("#B0B6C3"))
            h.bindStatus(t)
            h.v.setOnClickListener { val idx = tabs.indexOf(t); if (idx >= 0) select(idx) }
            h.v.setOnLongClickListener { val idx = tabs.indexOf(t); if (idx >= 0) tabOptions(idx); true }
        }
    }

    // ───────────── Abas: liberadas × em recarga ─────────────

    private sealed class Row {
        class Head(val free: Boolean, val count: Int) : Row()
        class Item(val tab: Tab) : Row()
    }
    private var rows: List<Row> = emptyList()
    private var rowsSig = ""
    private val coolingIds = HashSet<String>()

    private fun sigNow(now: Long) = tabs.joinToString("|") { it.id + (if (it.until > now) "c" else "f") }

    /** Liberadas primeiro (na ordem em que você criou); em recarga depois, de quem volta antes para quem volta depois. */
    private fun buildRows(now: Long): List<Row> {
        val free = tabs.filter { it.until <= now }
        val cool = tabs.filter { it.until > now }.sortedBy { it.until }
        val out = ArrayList<Row>()
        val split = free.isNotEmpty() && cool.isNotEmpty()
        if (split) out.add(Row.Head(true, free.size))
        free.forEach { out.add(Row.Item(it)) }
        if (split) out.add(Row.Head(false, cool.size))
        cool.forEach { out.add(Row.Item(it)) }
        return out
    }

    private fun refreshRows() {
        val now = System.currentTimeMillis()
        rows = buildRows(now)
        rowsSig = sigNow(now)
        adapter.notifyDataSetChanged()
    }

    private fun scrollToTab(t: Tab) {
        val pos = rows.indexOfFirst { it is Row.Item && it.tab === t }
        if (pos >= 0) rv.scrollToPosition(pos)
    }

    // ───────────── Foto da conta ─────────────

    private fun photoOptions(t: Tab) {
        val sh = Sheet(this).title("Foto da conta", "Aparece no lugar da letra de \"${t.name}\"")
        sh.row(R.drawable.ic_add, "Escolher da galeria", "Qualquer imagem do celular") {
            avatarTabId = t.id
            try {
                avatarPicker.launch(
                    androidx.activity.result.PickVisualMediaRequest.Builder()
                        .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly).build()
                )
            } catch (_: Exception) { avatarTabId = null; say("Não consegui abrir a galeria", true) }
        }
        if (Avatars.has(this, t.id)) {
            sh.row(R.drawable.ic_delete, "Remover foto", "Volta para a letra inicial", danger = true) {
                Avatars.clear(this, t.id); refreshRows(); say("Foto removida")
            }
        }
        sh.show()
    }
}
