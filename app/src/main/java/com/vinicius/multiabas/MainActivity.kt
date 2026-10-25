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

class Tab(val id: String, var name: String, var url: String, var until: Long = 0L)

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
    private val favAdapter = FavAdapter()
    private var favWeb: WebView? = null
    private val limitAsked = HashSet<String>()
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
                .show()
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
        findViewById<ImageButton>(R.id.btnFavAdd).setOnClickListener { editFav(-1) }
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
            input("Renomear aba", t.name) { t.name = it; adapter.notifyDataSetChanged(); save() }
        }
        sh.row(R.drawable.ic_refresh, "Recarregar") { webs[t.id]?.reload() }
        sh.row(R.drawable.ic_send, "Continuar em outra conta", "Leva o projeto e a conversa desta aba") { chooseTarget(t) }
        sh.row(R.drawable.ic_timer, "Definir volta do limite", "Mostra um cronômetro na aba") {
            val idx = tabs.indexOf(t)
            if (idx >= 0) pickLimit(idx)
        }
        if (t.until > 0L) {
            sh.row(R.drawable.ic_close, "Limpar limite") { t.until = 0L; save(); tickNow() }
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
        sh.section("Limite acabou?")
        sh.row(R.drawable.ic_refresh, "Continuar em outra conta", "Leva o projeto e a conversa para outra aba") { manualHandoff() }
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
                popup.status("APK pronto ✓")
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
        val proj = Projects.current(this)
        if (proj == null) {
            say("ZIP salvo. Defina um Projeto atual no menu para enviar ao GitHub.", true)
            return
        }
        if (Projects.token(this).isBlank()) {
            say("ZIP salvo. Configure o Token do GitHub em Configurações.", true)
            return
        }
        val popup = BuildPopup(this, proj.name)
        popup.show()
        popup.status("Enviando $zipName → ${proj.name}…")
        Handoff.log(this, proj.id, "ZIP enviado: $zipName")
        GitHubDelivery.deliver(
            this, proj, zipName, bytes,
            onStatus = { popup.status(it) },
            onSteps = { popup.steps(it) },
            onDone = { ok, url, apk ->
                Handoff.log(this, proj.id, "$zipName → " + if (ok) "build OK, APK pronto" else "build falhou ou sem APK")
                popup.done(ok, url, apk)
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
            w.evaluateJavascript(Handoff.CAPTURE_JS) { raw ->
                val snap = Handoff.parse(raw)
                if (snap != null) {
                    Handoff.save(this, t.id, snap)
                    if (snap.limit) {
                        applyLimitTime(t, snap.until)
                        if (limitAsked.add(t.id)) offerHandoff(t)
                    } else limitAsked.remove(t.id)
                }
            }
        }
    }

    /** Lê o "até 05:40" do aviso do Claude e preenche o cronômetro da aba. */
    private fun applyLimitTime(t: Tab, hhmm: String) {
        try {
            if (hhmm.isBlank() || t.until > System.currentTimeMillis()) return
            val h = hhmm.substringBefore(':').toInt()
            val m = hhmm.substringAfter(':').toInt()
            val c = java.util.Calendar.getInstance()
            c.set(java.util.Calendar.HOUR_OF_DAY, h)
            c.set(java.util.Calendar.MINUTE, m)
            c.set(java.util.Calendar.SECOND, 0)
            c.set(java.util.Calendar.MILLISECOND, 0)
            if (c.timeInMillis <= System.currentTimeMillis()) c.add(java.util.Calendar.DAY_OF_YEAR, 1)
            t.until = c.timeInMillis
            save(); tickNow()
        } catch (_: Exception) { }
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
            .show()
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
        val labels = others.map {
            it.name + if (it.until > now) "  ·  limite até " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(it.until)) else "  ·  liberada"
        }.toTypedArray()
        if (Handoff.chatInfo(this, from.id) == null) say("Sem conversa salva nesta aba — vai só o código do projeto.", true)
        MaterialAlertDialogBuilder(this)
            .setTitle("Continuar em qual conta?")
            .setItems(labels) { _, which -> startHandoff(from, others[which]) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun startHandoff(from: Tab, to: Tab) {
        val proj = Projects.current(this)
        val token = Projects.token(this)
        val popup = BuildPopup(this, proj?.name ?: from.name, "Continuar em ${to.name}")
        popup.show()
        popup.status("Montando o pacote…")
        Thread {
            val f = try {
                Handoff.build(this, from.name, from.id, proj, token) { popup.status(it) }
            } catch (e: Exception) { null }
            runOnUiThread {
                if (f == null) {
                    popup.status("Não consegui montar o pacote (sem conversa e sem projeto).")
                    popup.done(false, null, null)
                    return@runOnUiThread
                }
                try { saveBytes(f.readBytes(), f.name, "application/zip") } catch (_: Exception) { }
                popup.status("Pronto ✓ Abrindo \"${to.name}\" e anexando…")
                popup.done(true, null, null)
                openInTarget(to, f)
            }
        }.start()
    }

    /** Abre um chat novo na aba de destino, escreve a mensagem inicial e abre o anexar com o ZIP. */
    private fun openInTarget(to: Tab, f: java.io.File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(this, packageName + ".fileprovider", f)
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("continuar", Handoff.STARTER))
            val idx = tabs.indexOf(to)
            if (idx < 0) return
            select(idx)
            val w = webs[to.id] ?: return
            w.loadUrl("https://claude.ai/new")
            val fill = "(function(){var e=document.querySelector('div[contenteditable=\"true\"]');" +
                "if(!e)return 'none';e.focus();document.execCommand('insertText',false," +
                JSONObject.quote(Handoff.STARTER) + ");return 'ok';})();"
            w.postDelayed({
                w.evaluateJavascript(fill) { r ->
                    pendingClaudeFile = arrayOf(uri)
                    w.postDelayed({ triggerClaudeAttach() }, 600)
                    if (r?.contains("ok") != true) {
                        say("Entre na conta \"${to.name}\", cole a mensagem (já copiada) e anexe o ZIP de Downloads.", true)
                    } else {
                        say("Mensagem pronta. Se o anexo não abrir sozinho, use o ZIP em Downloads.", true)
                    }
                }
            }, 4500)
        } catch (e: Exception) {
            say("ZIP salvo em Downloads. Anexe na outra conta: ${e.message}", true)
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

    inner class FavAdapter : RecyclerView.Adapter<FavAdapter.VH>() {
        inner class VH(val v: View) : RecyclerView.ViewHolder(v) {
            val av: TextView = v.findViewById(R.id.favAvatar)
            val nm: TextView = v.findViewById(R.id.favName)
        }
        override fun onCreateViewHolder(p: ViewGroup, v: Int) = VH(layoutInflater.inflate(R.layout.item_tab, p, false))
        override fun getItemCount() = favs.size
        override fun onBindViewHolder(h: VH, i: Int) {
            val f = favs[i]
            h.nm.text = f.name + (if (f.mode == 2) " ↗" else "")
            Ui.avatar(h.av, f.name)
            h.v.setOnClickListener { val p = h.bindingAdapterPosition; if (p >= 0) openFav(favs[p]) }
            h.v.setOnLongClickListener { favOptions(h.bindingAdapterPosition); true }
        }
    }
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() { tickNow(); handler.postDelayed(this, 1000) }
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
        if (prefs.getBoolean("keepalive", true)) KeepAliveService.start(this)
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
            val av: TextView = v.findViewById(R.id.tabAvatar)
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
            Ui.avatar(h.av, tabs[i].name)
            h.v.isSelected = i == cur
            h.name.setTextColor(if (i == cur) Color.WHITE else Color.parseColor("#B0B6C3"))
            h.bindStatus(tabs[i])
            h.v.setOnClickListener { select(h.bindingAdapterPosition) }
            h.v.setOnLongClickListener { tabOptions(h.bindingAdapterPosition); true }
        }
    }
}
