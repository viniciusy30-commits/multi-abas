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
    /** ZIP do main pronto para injetar no próximo file chooser do Claude */
    private var pendingClaudeFile: Array<Uri>? = null
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
        return try {
            val types = params.acceptTypes?.filter { it.isNotBlank() } ?: emptyList()
            val joined = types.joinToString(",").lowercase()
            val wantsImage = types.isEmpty() || joined.contains("image") || joined.contains("*/*")
            val wantsVideo = joined.contains("video")
            val onlyMedia = types.isEmpty() || types.all { t ->
                val x = t.lowercase()
                x.startsWith("image") || x.startsWith("video") || x == "*/*" || x == "." || x.isBlank()
            }
            // Claude costuma pedir image/* ou deixar vazio para anexos de mídia
            if (onlyMedia && (wantsImage || wantsVideo)) {
                val multi = params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
                val mediaType = when {
                    wantsImage && wantsVideo -> androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageAndVideo
                    wantsVideo && !wantsImage -> androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.VideoOnly
                    else -> androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                }
                val req = androidx.activity.result.PickVisualMediaRequest.Builder()
                    .setMediaType(mediaType)
                    .build()
                if (multi) multiPhotoPicker.launch(req) else photoPicker.launch(req)
                true
            } else {
                // Outros tipos: seletor de documentos
                val intent = params.createIntent()
                // Preferir GET_CONTENT que abre melhor no Android
                if (intent.action.isNullOrBlank()) intent.action = Intent.ACTION_GET_CONTENT
                docPicker.launch(intent)
                true
            }
        } catch (e: Exception) {
            try {
                docPicker.launch(params.createIntent())
                true
            } catch (_: Exception) {
                fileCb = null
                false
            }
        }
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
                    Toast.makeText(this@MainActivity, "Salvo em Downloads: $fin", Toast.LENGTH_LONG).show()
                    maybeDeliverZip(fin, bytes)
                }
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
        val proj = Projects.current(this)
        val projLabel = if (proj == null) "Projeto atual: (nenhum)" else "Projeto atual: ${proj.name}"
        m.menu.add(0, 10, 0, projLabel)
        m.menu.add(0, 11, 1, "Gerenciar projetos")
        m.menu.add(0, 12, 2, "Abrir jogo")
        m.menu.add(0, 13, 3, "Enviar main pro Claude")
        m.menu.add(0, 1, 4, "Recarregar")
        m.menu.add(0, 2, 5, "Configurações")
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                10, 11 -> projectPicker()
                12 -> openCurrentGame()
                13 -> sendMainToClaude()
                1 -> webs[tabs[cur].id]?.reload()
                2 -> settings()
            }
            true
        }
        m.show()
    }

    private fun settings() {
        val items = arrayOf(
            "Token do GitHub",
            "Projeto atual / gerenciar",
            "Segundo plano (manter Claude vivo)",
            "Verificar atualização",
            "O que mudou"
        )
        AlertDialog.Builder(this).setTitle("Configurações — v${BuildConfig.VERSION_NAME}")
            .setItems(items) { _, w ->
                when (w) {
                    0 -> editToken()
                    1 -> projectPicker()
                    2 -> toggleKeepAlive()
                    3 -> Updater.check(this, true)
                    4 -> Novidades.show(this, true)
                }
            }
            .setNegativeButton("Fechar", null).show()
    }

    private fun editToken() {
        val e = EditText(this)
        e.setSingleLine()
        e.hint = "ghp_… ou github_pat_…"
        e.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        val cur = Projects.token(this)
        if (cur.isNotEmpty()) e.setText(cur)
        AlertDialog.Builder(this).setTitle("Token do GitHub")
            .setMessage("Personal Access Token com permissão de conteúdo (repo) e actions. Cole aqui e salve.")
            .setView(e)
            .setPositiveButton("Salvar") { _, _ ->
                Projects.setToken(this, e.text.toString())
                Toast.makeText(this, if (e.text.toString().trim().isEmpty()) "Token apagado" else "Token salvo", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun toggleKeepAlive() {
        val on = prefs.getBoolean("keepalive", true)
        val msg = if (on)
            "Segundo plano está LIGADO.\nO Claude continua quando você minimiza (notificação fixa)."
        else
            "Segundo plano está DESLIGADO.\nAo minimizar, o sistema pode interromper o Claude."
        AlertDialog.Builder(this).setTitle("Segundo plano")
            .setMessage(msg)
            .setPositiveButton(if (on) "Desligar" else "Ligar") { _, _ ->
                val next = !on
                prefs.edit().putBoolean("keepalive", next).apply()
                if (next) {
                    maybeRequestNotifPermission()
                    KeepAliveService.start(this)
                    Toast.makeText(this, "Segundo plano ligado", Toast.LENGTH_SHORT).show()
                } else {
                    KeepAliveService.stop(this)
                    Toast.makeText(this, "Segundo plano desligado", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Fechar", null).show()
    }

    private fun maybeRequestNotifPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 91)
            }
        }
    }

    private fun maybeDeliverZip(name: String, bytes: ByteArray) {
        if (!name.lowercase().endsWith(".zip")) return
        val proj = Projects.current(this)
        if (proj == null) {
            Toast.makeText(this, "ZIP salvo. Defina um Projeto atual no menu para enviar ao GitHub.", Toast.LENGTH_LONG).show()
            return
        }
        if (Projects.token(this).isBlank()) {
            Toast.makeText(this, "ZIP salvo. Configure o Token do GitHub em Configurações.", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "Enviando $name → ${proj.name}…", Toast.LENGTH_SHORT).show()
        GitHubDelivery.deliver(this, proj, name, bytes) { status ->
            runOnUiThread { Toast.makeText(this, status, Toast.LENGTH_SHORT).show() }
        }
    }

    private fun openCurrentGame() {
        val p = Projects.current(this)
        if (p == null) {
            Toast.makeText(this, "Defina um Projeto atual primeiro", Toast.LENGTH_SHORT).show()
            projectPicker()
            return
        }
        if (p.packageName.isBlank()) {
            Toast.makeText(this, "Package do jogo não detectado. Edite o projeto e preencha.", Toast.LENGTH_LONG).show()
            return
        }
        if (!GitHubDelivery.openGame(this, p)) {
            Toast.makeText(this, "Jogo não instalado. Use Baixar APK mais recente.", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Baixa o ZIP do branch do projeto (main/padrão) e anexa no chat do Claude aberto.
     */
    private fun sendMainToClaude() {
        val p = Projects.current(this)
        if (p == null) {
            Toast.makeText(this, "Defina um Projeto atual primeiro", Toast.LENGTH_SHORT).show()
            projectPicker()
            return
        }
        val token = Projects.token(this)
        // token ajuda em repo privado; público funciona sem
        Toast.makeText(this, "Baixando main de ${p.name}…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val branch = p.branch.ifBlank { "main" }
                val zip = GitHubDelivery.downloadRepoZip(this, token, p, branch)
                if (zip == null || !zip.exists()) {
                    runOnUiThread {
                        Toast.makeText(this, "Não foi possível baixar o ZIP de ${p.fullRepo()}@$branch", Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    this, packageName + ".fileprovider", zip
                )
                runOnUiThread {
                    pendingClaudeFile = arrayOf(uri)
                    Toast.makeText(this, "Anexando ${zip.name} no Claude…", Toast.LENGTH_SHORT).show()
                    triggerClaudeAttach()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Erro: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /** Clica no botão de anexar / input file da página do Claude. */
    private fun triggerClaudeAttach() {
        val w = webs[tabs.getOrNull(cur)?.id] ?: run {
            Toast.makeText(this, "Nenhuma aba aberta", Toast.LENGTH_SHORT).show()
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
                            Toast.makeText(
                                this,
                                "Não achei o botão de anexar. Abra o Claude, foque no chat e tente de novo.",
                                Toast.LENGTH_LONG
                            ).show()
                            // limpa pendência se o chooser não abrir em 8s
                            w.postDelayed({
                                if (pendingClaudeFile != null) {
                                    pendingClaudeFile = null
                                    Toast.makeText(this, "Envio cancelado (chooser não abriu)", Toast.LENGTH_SHORT).show()
                                }
                            }, 8000)
                        }
                    }
                }, 600)
            }
        }
    }

    private fun projectPicker() {
        val list = Projects.load(this)
        val curId = Projects.currentId(this)
        val labels = mutableListOf<String>()
        list.forEach {
            val mark = if (it.id == curId) "✓ " else ""
            val br = if (it.branch.isBlank()) "auto" else it.branch
            labels.add("$mark${it.name}  (${it.fullRepo()} · $br/${it.folder})")
        }
        labels.add("+ Adicionar do GitHub…")
        AlertDialog.Builder(this).setTitle("Projeto atual")
            .setItems(labels.toTypedArray()) { _, w ->
                if (w == list.size) pickRepoFromGitHub()
                else {
                    Projects.setCurrentId(this, list[w].id)
                    Toast.makeText(this, "Projeto: ${list[w].name}", Toast.LENGTH_SHORT).show()
                    projectOptions(w)
                }
            }
            .setNegativeButton("Fechar", null).show()
    }

    /** Lista os repositórios da sua conta e preenche tudo automaticamente. */
    private fun pickRepoFromGitHub() {
        val token = Projects.token(this)
        if (token.isBlank()) {
            Toast.makeText(this, "Configure o Token do GitHub em Configurações primeiro", Toast.LENGTH_LONG).show()
            editToken()
            return
        }
        Toast.makeText(this, "Carregando repositórios…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val repos = GitHubRepos.listMyRepos(token)
                runOnUiThread {
                    if (repos.isEmpty()) {
                        Toast.makeText(this, "Nenhum repositório encontrado (confira o token)", Toast.LENGTH_LONG).show()
                        return@runOnUiThread
                    }
                    val labels = repos.map { r ->
                        val d = if (r.description.isNotBlank()) " — ${r.description.take(40)}" else ""
                        "${r.name}$d"
                    }.toTypedArray()
                    AlertDialog.Builder(this).setTitle("Escolha o repositório")
                        .setItems(labels) { _, w ->
                            val r = repos[w]
                            Toast.makeText(this, "Detectando package de ${r.name}…", Toast.LENGTH_SHORT).show()
                            Thread {
                                val pkg = try {
                                    GitHubRepos.detectPackage(token, r.owner, r.name, r.defaultBranch)
                                } catch (_: Exception) { "" }
                                val hasInc = try {
                                    GitHubRepos.hasIncoming(token, r.owner, r.name, r.defaultBranch)
                                } catch (_: Exception) { false }
                                runOnUiThread {
                                    addProjectFromRepo(r, pkg, hasInc)
                                }
                            }.start()
                        }
                        .setNegativeButton("Cancelar", null).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Erro ao listar: ${e.message}", Toast.LENGTH_LONG).show()
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
            Toast.makeText(this, "Já cadastrado — projeto atual: ${list[existing].name}", Toast.LENGTH_SHORT).show()
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
        AlertDialog.Builder(this).setTitle("Projeto adicionado")
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
        AlertDialog.Builder(this).setTitle(p.name)
            .setItems(arrayOf("Usar este projeto", "Abrir jogo", "Editar", "Remover")) { _, w ->
                when (w) {
                    0 -> {
                        Projects.setCurrentId(this, p.id)
                        Toast.makeText(this, "Projeto atual: ${p.name}", Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        Projects.setCurrentId(this, p.id)
                        openCurrentGame()
                    }
                    2 -> editProject(i)
                    3 -> AlertDialog.Builder(this).setTitle("Remover ${p.name}?")
                        .setPositiveButton("Remover") { _, _ ->
                            val id = p.id
                            val n = list.toMutableList().also { it.removeAt(i) }
                            Projects.save(this, n)
                            if (Projects.currentId(this) == id) Projects.setCurrentId(this, n.firstOrNull()?.id ?: "")
                            Toast.makeText(this, "Removido", Toast.LENGTH_SHORT).show()
                        }.setNegativeButton("Cancelar", null).show()
                }
            }.show()
    }

    private fun editProject(i: Int) {
        val list = Projects.load(this)
        val existing = if (i >= 0 && i < list.size) list[i] else null
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(56, 16, 56, 0)
        fun field(hint: String, value: String): EditText {
            val e = EditText(this)
            e.hint = hint
            e.setSingleLine()
            e.setText(value)
            box.addView(e)
            return e
        }
        val n = field("Nome (ex: Masmorras)", existing?.name ?: "")
        val owner = field("Dono do repo (ex: viniciusy30-commits)", existing?.owner ?: "viniciusy30-commits")
        val repo = field("Nome do repositório (ex: masmorras-rpg)", existing?.repo ?: "")
        val branch = field("Branch (vazio = padrão do GitHub)", existing?.branch ?: "")
        val folder = field("Pasta do zip (padrão _incoming)", existing?.folder ?: "_incoming")
        val pkg = field("Package do jogo (ex: com.vinicius.masmorras)", existing?.packageName ?: "")
        AlertDialog.Builder(this).setTitle(if (existing == null) "Novo projeto" else "Editar projeto")
            .setView(box)
            .setPositiveButton("Salvar") { _, _ ->
                val name = n.text.toString().trim().ifEmpty { repo.text.toString().trim() }
                val r = repo.text.toString().trim()
                if (r.isEmpty()) {
                    Toast.makeText(this, "Informe o repositório", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(this, "Projeto salvo: ${p.name}", Toast.LENGTH_SHORT).show()
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
