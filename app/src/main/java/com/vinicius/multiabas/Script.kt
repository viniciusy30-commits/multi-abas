package com.vinicius.multiabas

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ScriptItem(val id: String, var text: String, var state: Int = Script.PEND, var zips: Int = 0)

class ScriptEvent(val t: Long, val text: String)

class ScriptZip(val item: Int, val name: String, val file: String, val size: Long, val hash: String)

/** Lista de tarefas que o app executa sozinho no Claude + ZIPs recolhidos pelo caminho. */
object Script {
    const val IDLE = 0
    const val RUNNING = 1
    const val PAUSED = 2
    const val DONE = 3

    const val PEND = 0
    const val RUN = 1
    const val OK = 2
    const val FAIL = 3

    val items = mutableListOf<ScriptItem>()

    val zips = mutableListOf<ScriptZip>()
    var status = IDLE
    var idx = 0
    var chatTabId = ""
    var reason = ""
    var ui = ""
    var resultShown = true

    private const val PREF = "script"

    val events = mutableListOf<ScriptEvent>()

    @Synchronized
    fun log(ctx: Context, text: String) {
        events.add(ScriptEvent(System.currentTimeMillis(), text))
        while (events.size > 300) events.removeAt(0)
        try {
            val a = JSONArray()
            events.forEach { a.put(JSONObject().put("t", it.t).put("x", it.text)) }
            ctx.getSharedPreferences(PREF, 0).edit().putString("events", a.toString()).apply()
        } catch (_: Exception) { }
    }

    @Synchronized
    fun eventsSnapshot(): List<ScriptEvent> = events.toList()

    @Synchronized
    fun clearEvents(ctx: Context) {
        events.clear()
        try { ctx.getSharedPreferences(PREF, 0).edit().remove("events").apply() } catch (_: Exception) { }
    }

    /** Arquivos (caminho, tamanho) que um ZIP guardado traz. */
    fun zipFiles(ctx: Context, z: ScriptZip): List<Pair<String, Long>> {
        val out = mutableListOf<Pair<String, Long>>()
        try {
            ZipInputStream(zipFile(ctx, z).inputStream().buffered()).use { zi ->
                var e = zi.nextEntry
                while (e != null) {
                    if (!e.isDirectory) {
                        val n = e.name.replace('\\', '/').trimStart('/')
                        if (n.isNotEmpty() && !n.startsWith("__MACOSX/")) out.add(n to zi.readBytes().size.toLong())
                    }
                    e = zi.nextEntry
                }
            }
        } catch (_: Exception) { }
        return out
    }

    fun load(ctx: Context) {
        items.clear(); zips.clear()
        try {
            val ev = JSONArray(ctx.getSharedPreferences(PREF, 0).getString("events", "[]") ?: "[]")
            synchronized(this) {
                events.clear()
                for (i in 0 until ev.length()) { val x = ev.getJSONObject(i); events.add(ScriptEvent(x.optLong("t"), x.optString("x"))) }
            }
        } catch (_: Exception) { }
        try {
            val p = ctx.getSharedPreferences(PREF, 0)
            val o = JSONObject(p.getString("data", "{}") ?: "{}")
            status = o.optInt("status", IDLE)
            idx = o.optInt("idx", 0)
            chatTabId = o.optString("chatTabId", "")
            reason = o.optString("reason", "")
            resultShown = o.optBoolean("resultShown", true)
            val a = o.optJSONArray("items") ?: JSONArray()
            for (i in 0 until a.length()) {
                val x = a.getJSONObject(i)
                items.add(ScriptItem(x.getString("id"), x.getString("text"), x.optInt("state", PEND), x.optInt("zips", 0)))
            }
            val z = o.optJSONArray("zips") ?: JSONArray()
            for (i in 0 until z.length()) {
                val x = z.getJSONObject(i)
                zips.add(ScriptZip(x.optInt("item"), x.getString("name"), x.getString("file"), x.optLong("size"), x.optString("hash")))
            }
        } catch (_: Exception) { }
    }


    fun save(ctx: Context) {
        try {
            val a = JSONArray()
            items.forEach { a.put(JSONObject().put("id", it.id).put("text", it.text).put("state", it.state).put("zips", it.zips)) }
            val z = JSONArray()
            zips.forEach { z.put(JSONObject().put("item", it.item).put("name", it.name).put("file", it.file).put("size", it.size).put("hash", it.hash)) }
            val o = JSONObject().put("status", status).put("idx", idx).put("chatTabId", chatTabId)
                .put("reason", reason).put("resultShown", resultShown).put("items", a).put("zips", z)
            ctx.getSharedPreferences(PREF, 0).edit().putString("data", o.toString()).apply()
        } catch (_: Exception) { }
    }

    private fun dir(ctx: Context) = File(ctx.filesDir, "script_zips").apply { mkdirs() }

    fun zipFile(ctx: Context, z: ScriptZip) = File(dir(ctx), z.file)

    private fun sha(b: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }

    /** Guarda um ZIP baixado do Claude. Devolve null se for repetido (o mesmo arquivo já guardado). */
    fun addZip(ctx: Context, item: Int, name: String, bytes: ByteArray): ScriptZip? {
        val h = sha(bytes)
        if (zips.any { it.hash == h }) return null
        val fn = String.format(Locale.US, "%02d-%s", zips.size + 1, GitHubDelivery.safeName(name))
        File(dir(ctx), fn).writeBytes(bytes)
        val z = ScriptZip(item, name, fn, bytes.size.toLong(), h)
        zips.add(z)
        save(ctx)
        return z
    }


    fun clearZips(ctx: Context) {
        zips.clear()
        try { dir(ctx).listFiles()?.forEach { it.delete() } } catch (_: Exception) { }
    }

    /** Arquivos de todos os ZIPs na ordem das tarefas; o mais novo vence quando o caminho se repete. */
    fun overlay(ctx: Context): LinkedHashMap<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        for (z in zips) {
            try {
                ZipInputStream(zipFile(ctx, z).inputStream().buffered()).use { zi ->
                    var e = zi.nextEntry
                    while (e != null) {
                        val n = e.name.replace('\\', '/').trimStart('/')
                        if (!e.isDirectory && n.isNotEmpty() && !n.startsWith(".github/") && !n.startsWith("__MACOSX/") && !n.contains("..")) {
                            out.remove(n)
                            out[n] = zi.readBytes()
                        }
                        e = zi.nextEntry
                    }
                }
            } catch (_: Exception) { }
        }
        return out
    }

    /** Junta todos os ZIPs em um só, na ordem. Resultado idêntico a enviar um por um. */
    fun merged(ctx: Context): File? {
        val map = overlay(ctx)
        if (map.isEmpty()) return null
        val outDir = File(ctx.cacheDir, "script_out").apply { mkdirs() }
        outDir.listFiles()?.forEach { it.delete() }

        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val f = File(outDir, "script-$stamp.zip")
        ZipOutputStream(f.outputStream().buffered()).use { z ->
            map.forEach { (n, b) ->
                z.putNextEntry(ZipEntry(n))
                z.write(b)
                z.closeEntry()
            }
        }
        return f
    }

    // ───────────── textos enviados ao Claude ─────────────

    fun firstMessage(task: String, total: Int) =
        "Vou te passar uma lista de $total tarefa(s) para o projeto anexado (ZIP com o código atual), uma por vez.\n\n" +
        "Regras:\n" +
        "1) Faça somente a tarefa que eu enviar em cada mensagem.\n" +
        "2) Ao terminar cada tarefa, entregue o resultado como UM ZIP pronto para baixar, com os arquivos alterados/novos na estrutura do projeto (caminhos a partir da raiz do repositório). Nunca código para copiar e colar: eu só uso o celular.\n" +
        "3) As tarefas se acumulam: se um arquivo já foi alterado numa tarefa anterior desta conversa, o ZIP da nova tarefa deve conter a versão COMPLETA dele, com todas as mudanças.\n" +
        "4) Os ZIPs não são aplicados ao repositório durante a lista; eu aplico tudo no final, em ordem.\n" +
        "5) Não faça perguntas se não for essencial: decida e siga em frente. Responda curto, sem repetir o código no chat.\n\n" +
        "TAREFA 1 de $total:\n$task"

    fun nextMessage(n: Int, total: Int, task: String) =
        "TAREFA $n de $total:\n$task\n\n" +
        "(Entregue como ZIP pronto para baixar, com a versão completa dos arquivos alterados, acumulando as tarefas anteriores.)"

    fun askZip(n: Int) =
        "Você não entregou o ZIP da tarefa $n. Entregue agora, como ZIP pronto para baixar, com a versão completa dos arquivos alterados."

    const val CONTINUE_MSG =

        "O limite acabou no meio da tarefa e já voltou. Continue exatamente de onde parou e entregue o ZIP pronto para baixar."

    fun handoffNote(n: Int, total: Int, task: String) =
        "Esta conversa é parte de um SCRIPT AUTOMÁTICO de $total tarefa(s) executadas em sequência. " +
        "Tarefas já concluídas: ${n - 1}. Tarefa ATUAL ($n de $total):\n$task\n\n" +
        "Os arquivos dos ZIPs das tarefas anteriores já estão aplicados em codigo/ (por cima do repositório). " +
        "Faça só a tarefa atual (continue de onde parou se estiver incompleta) e entregue o resultado como ZIP pronto " +
        "para baixar, com a versão COMPLETA de cada arquivo alterado. Não comece as próximas tarefas: eu envio uma por vez."
}

/** JavaScript que roda dentro da página do Claude. */
object ScriptJs {
    const val STATE = """(function(){try{
var u=document.querySelectorAll('[data-testid="user-message"]').length;
var s=!!document.querySelector('[data-is-streaming="true"]');
if(!s){var bs=document.querySelectorAll('button[aria-label]');for(var i=0;i<bs.length;i++){if(/^(stop|parar|interromper)/i.test(bs[i].getAttribute('aria-label')||'')){s=true;break;}}}
var n=((document.body&&document.body.innerText)||'').length;
return JSON.stringify({u:u,s:s,n:n,c:!!document.querySelector('div[contenteditable="true"]')});
}catch(e){return JSON.stringify({u:-1,s:false,c:false});}})();"""

    const val FILL = """(function(){var e=document.querySelector('div[contenteditable="true"]');if(!e)return 'none';
e.focus();document.execCommand('selectAll',false,null);document.execCommand('delete',false,null);
document.execCommand('insertText',false,__TEXT__);return 'ok';})();"""

    const val SEND = """(function(){
var e=document.querySelector('div[contenteditable="true"]');var b=null,p=e;
for(var k=0;k<10&&p&&!b;k++){p=p.parentElement;if(!p)break;var bs=p.querySelectorAll('button');
for(var i=0;i<bs.length;i++){var a=((bs[i].getAttribute('aria-label')||'')+' '+(bs[i].getAttribute('data-testid')||'')).trim();
if(/^(send|enviar)|send-button|send message|enviar mensagem/i.test(a)){b=bs[i];break;}}}
if(!b)return 'none';
if(b.disabled||b.getAttribute('aria-disabled')==='true')return 'disabled';
b.click();return 'sent';})();"""


    /** Acha e clica no "Baixar" do ZIP que o Claude entregou. Cada botão só é clicado uma vez. */
    const val CLICK_DL = """(function(){try{
var done=window.__sDone||(window.__sDone={});
var re=/download|baixar|descargar/i;
function lab(el){return ((el.getAttribute('aria-label')||'')+' '+(el.getAttribute('title')||'')+' '+(el.innerText||'')).replace(/\s+/g,' ').trim();}
function nameIn(p){var t=(p.innerText||'');if(t.length>600)return '';var m=t.match(/[\w.\-() ]+\.zip/i);return m?m[0].trim():'';}
function cardName(el){var p=el;for(var k=0;k<6&&p;k++){p=p.parentElement;if(!p)break;var n=nameIn(p);if(n)return n;}return '';}
var els=document.querySelectorAll('a[download],a[href],button,[role="button"],[role="menuitem"]');
for(var i=0;i<els.length;i++){var el=els[i];var lb=lab(el);
if(!lb||lb.length>80||!re.test(lb))continue;
var nm=cardName(el)||(/\.zip/i.test(lb)?lb:'')||window.__sOpen||'';
if(!nm)continue;
var key='d|'+nm+'|'+i;if(done[key])continue;
done[key]=1;el.click();
return JSON.stringify({a:'dl',n:nm});}
for(var j=0;j<els.length;j++){var e2=els[j];var l2=lab(e2);
if(!l2||l2.length>120||!/\.zip\b/i.test(l2))continue;
var k2='o|'+l2+'|'+j;if(done[k2])continue;
done[k2]=1;window.__sOpen=(l2.match(/[\w.\-() ]+\.zip/i)||[l2])[0];e2.click();
return JSON.stringify({a:'open',n:window.__sOpen});}
return JSON.stringify({a:'none'});
}catch(e){return JSON.stringify({a:'err',m:String(e)});}})();"""
}
