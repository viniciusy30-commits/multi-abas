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

    /** Tarefa cujo trabalho parcial está guardado em [partial] (o que o Claude já tinha escrito quando o limite cortou). */
    var partialIdx = -1
    var partial = ""

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
            partialIdx = o.optInt("partialIdx", -1)
            partial = o.optString("partial", "")
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
                .put("reason", reason).put("resultShown", resultShown).put("partialIdx", partialIdx).put("partial", partial.takeLast(300_000)).put("items", a).put("zips", z)
            ctx.getSharedPreferences(PREF, 0).edit().putString("data", o.toString()).apply()
        } catch (_: Exception) { }
    }

    private fun dir(ctx: Context) = File(ctx.filesDir, "script_zips").apply { mkdirs() }

    fun zipFile(ctx: Context, z: ScriptZip) = File(dir(ctx), z.file)

    fun shaOf(b: ByteArray): String = sha(b)

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
        "2) Ao terminar cada tarefa, entregue o resultado como UM ZIP pronto para baixar (NUNCA com nome começando em \"codigo-\": esse é o nome do ZIP que eu te mandei; use outro nome (nem \"continuar-\"), ex.: entrega-tarefa-N.zip), com os arquivos alterados/novos na estrutura do projeto (caminhos a partir da raiz do repositório). Nunca código para copiar e colar: eu só uso o celular.\n" +
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
        "O limite acabou no meio da tarefa e já voltou. NÃO recomece: olhe o que você já fez nesta conversa, " +
        "diga em até 3 linhas o que já está pronto e o que falta, e continue SÓ dali. " +
        "Entregue o ZIP pronto para baixar, com a versão completa dos arquivos alterados."

    /**
     * Mensagem da conta nova. [begun] = o Claude anterior já tinha começado esta tarefa (e foi cortado pelo limite).
     * O foco é deixar claro o que NÃO deve ser refeito.
     */
    fun handoffMessage(n: Int, total: Int, task: String, begun: Boolean): String {
        val sb = StringBuilder()
        sb.append("CONTINUAÇÃO DE UM SCRIPT AUTOMÁTICO — NÃO RECOMECE DO ZERO.\n\n")

        sb.append("Este chat continua um trabalho que começou em outra conta do Claude (o limite de lá acabou). ")
        sb.append("Anexei um ZIP: leia PRIMEIRO o PROGRESSO-DO-SCRIPT.md, depois o LEIA-ME-PRIMEIRO.md. ")
        sb.append("O código atual está em codigo/.\n\n")
        sb.append("O QUE JÁ ESTÁ FEITO (não refaça, não reescreva, não confira de novo):\n")
        if (n > 1) sb.append("- Tarefas 1 a ${n - 1} estão CONCLUÍDAS e já aplicadas em codigo/.\n")
        else sb.append("- Nenhuma tarefa foi concluída ainda.\n")
        if (begun) {
            sb.append("- A tarefa $n JÁ FOI COMEÇADA pelo Claude anterior e foi cortada pelo limite. ")
            sb.append("O que ele já fez está em codigo/, em trabalho-incompleto.txt (se existir) e em conversa-anterior.txt.\n\n")
            sb.append("COMO CONTINUAR A TAREFA $n:\n")
            sb.append("1) Antes de escrever qualquer coisa, compare codigo/ com o que a tarefa pede e diga em NO MÁXIMO 5 linhas o que já está pronto e o que falta.\n")
            sb.append("2) Faça SÓ o que falta, aproveitando o que já existe. Se trabalho-incompleto.txt tiver arquivos ou comandos que NÃO estão em codigo/, recrie só isso a partir dele.\n")
            sb.append("3) Entregue UM ZIP pronto para baixar com a versão COMPLETA de cada arquivo alterado (incluindo o que já estava feito).\n\n")
        } else {
            sb.append("- A tarefa $n ainda NÃO foi começada (o limite acabou antes). Pode fazê-la inteira, a partir do código em codigo/.\n\n")
            sb.append("COMO FAZER A TAREFA $n:\n")
            sb.append("1) Parta do código de codigo/, que já tem tudo das tarefas anteriores.\n")
            sb.append("2) Entregue UM ZIP pronto para baixar com a versão COMPLETA de cada arquivo alterado.\n\n")
        }
        sb.append("Regras: eu só uso o celular (nada de código para copiar e colar); faça somente a tarefa atual ")
        sb.append("(as próximas eu envio uma por vez); não faça perguntas se não for essencial; responda curto.\n\n")
        sb.append("TAREFA ATUAL ($n de $total) — texto original, só para referência:\n$task")
        return sb.toString()
    }

    class ProgItem(val text: String, val state: Int)

    /** Cópia da lista de tarefas, tirada na thread principal para ser usada em outra thread. */
    fun snapshotItems(): List<ProgItem> = items.map { ProgItem(it.text, it.state) }

    /** Conteúdo do PROGRESSO-DO-SCRIPT.md que vai no ZIP levado para a outra conta. */
    fun progressMd(ctx: Context, its: List<ProgItem>, zs: List<ScriptZip>, current: Int, begun: Boolean): String {

        val sb = StringBuilder()
        sb.append("# Progresso do script\n\n")
        sb.append("Script automático com ${its.size} tarefa(s). ")
        sb.append("Legenda: ✓ concluída (NÃO refazer) · → atual · • na fila · ✗ falhou.\n\n")
        its.forEachIndexed { i, item ->
            val mark = when {
                item.state == OK -> "✓"
                item.state == FAIL -> "✗"
                i == current -> "→"
                else -> "•"
            }
            sb.append("## $mark Tarefa ${i + 1} de ${its.size}\n\n")
            sb.append(item.text.trim().take(1500)).append("\n\n")
            if (i == current) {
                sb.append(if (begun) "**Estado: JÁ COMEÇADA e cortada pelo limite.** Veja trabalho-incompleto.txt e conversa-anterior.txt; continue só do que falta.\n\n"
                          else "**Estado: ainda não começada.**\n\n")
            }
            for (z in zs.filter { it.item == i }) {
                val files = zipFiles(ctx, z)
                sb.append("ZIP entregue: `${z.name}` — ${files.size} arquivo(s), já aplicados em codigo/:\n")
                files.take(40).forEach { (n, _) -> sb.append("- $n\n") }
                if (files.size > 40) sb.append("- … e mais ${files.size - 40}\n")
                sb.append("\n")
            }
        }
        return sb.toString()
    }
}

/** JavaScript que roda dentro da página do Claude. */
object ScriptJs {
    const val STATE = """(function(){try{
var u=document.querySelectorAll('[data-testid="user-message"]').length;
var s=!!document.querySelector('[data-is-streaming="true"]');
if(!s){var bs=document.querySelectorAll('button[aria-label]');for(var i=0;i<bs.length;i++){if(/^(stop|parar|interromper)/i.test(bs[i].getAttribute('aria-label')||'')){s=true;break;}}}
var n=((document.body&&document.body.innerText)||'').length;
var cb=document.querySelector('div[contenteditable="true"]');
var ce=cb?((cb.innerText||'')+'').replace(/\s+/g,'').length:-1;
return JSON.stringify({u:u,s:s,n:n,c:!!cb,e:ce});
}catch(er){return JSON.stringify({u:-1,s:false,c:false,e:-1});}})();"""

    /**
     * Fecha avisos/janelas da página do Claude que atrapalham (novidades, pesquisas, "got it", cookies…).
     * NÃO mexe em aviso de limite (o app precisa ler), em menus, nem na janela de anexo.
     */
    const val CLEAR = """(function(){try{
var lim=/sem\s+mensagens|mensagens\s+gratuitas|(you.?ve|you have)\s+(reached|hit)|usage limit|limit\s+(will\s+)?reset|resets?\s+(at|in|on)\s|atingiu\s+o\s+limite|limite\s.{0,40}(redefin|reinici|volt|renov)|volta(r[áa])?\s.{0,20}\d/i;
var bad=/upgrade|assinar|subscribe|pagar|\bpay\b|delete|excluir|apagar|remov|log\s*out|sign\s*out|sair|comprar|purchase|get (plus|pro|max)|try (claude|pro)/i;
var closeLbl=/^(close|fechar|dismiss|dispensar|close dialog|fechar janela)$/i;
var soft=/^(got it|entendi|ok|okay|not now|agora n[ãa]o|no,? thanks|n[ãa]o,? obrigad[oa]|maybe later|later|depois|skip|pular|i understand|entendo|done|conclu[íi]do)$/i;
var cookie=/^(accept all|aceitar tudo|accept|aceitar|reject all|rejeitar tudo|reject|rejeitar)$/i;
function vis(e){var r=e.getBoundingClientRect();return r.width>4&&r.height>4;}
function lb(b){return ((b.getAttribute('aria-label')||'')+'').trim()||((b.innerText||'')+'').replace(/\s+/g,' ').trim();}
var ds=document.querySelectorAll('[role="dialog"],[role="alertdialog"],[aria-modal="true"]');
var n=0,txt='';
for(var i=0;i<ds.length;i++){var d=ds[i];if(!vis(d))continue;
if(d.querySelector('div[contenteditable="true"],input[type="file"],[role="menu"]'))continue;
var t=((d.innerText||'')+'').replace(/\s+/g,' ').trim();
if(lim.test(t))continue;
var bs=d.querySelectorAll('button,[role="button"]');var pick=null;
for(var j=0;j<bs.length&&!pick;j++){var l=lb(bs[j]);if(l&&closeLbl.test(l)&&vis(bs[j]))pick=bs[j];}
if(!pick){var ck=/cookie/i.test(t);for(var j2=0;j2<bs.length&&!pick;j2++){var l2=lb(bs[j2]);if(!l2||bad.test(l2)||!vis(bs[j2]))continue;if(soft.test(l2)||(ck&&cookie.test(l2)))pick=bs[j2];}}
if(pick){pick.click();n++;txt=t.slice(0,80);}
else{txt=t.slice(0,80);}
}
return JSON.stringify({n:n,t:txt});
}catch(e){return JSON.stringify({n:0,t:''});}})();"""

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


    /**
     * Acha e clica no "Baixar" do ZIP que o Claude entregou NESTA resposta.
     * - ignora anexos da própria mensagem do usuário (o ZIP "codigo-...zip" que o app mandou);
     * - ignora cartões/botões de respostas antigas (senão baixaria de novo o ZIP da tarefa anterior);
     * - o estado é separado por mensagem enviada, então cada tarefa começa do zero;
     * - varre de trás para frente: o mais novo primeiro.
     */
    const val CLICK_DL = """(function(){try{
var us=document.querySelectorAll('[data-testid="user-message"]');
var last=us.length?us[us.length-1]:null;
var thread=null;
if(us.length>1){thread=us[0];while(thread&&!thread.contains(last))thread=thread.parentElement;}
var st=window.__sSt;
if(!st||st.u!==us.length){st=window.__sSt={u:us.length,done:{},open:'',opened:false};}
var re=/download|baixar|descargar/i;
function lab(el){return ((el.getAttribute('aria-label')||'')+' '+(el.getAttribute('title')||'')+' '+(el.innerText||'')).replace(/\s+/g,' ').trim();}
function nameIn(p){var t=(p.innerText||'');if(t.length>600)return '';var m=t.match(/[\w.\-() ]+\.zip/i);return m?m[0].trim():'';}
function cardName(el){var p=el;for(var k=0;k<6&&p;k++){p=p.parentElement;if(!p)break;var n=nameIn(p);if(n)return n;}return '';}
function old(el){
if(!last)return false;
if(last.contains(el))return true;
if(thread&&thread.contains(el)&&(last.compareDocumentPosition(el)&2))return true;
return false;}
function inThread(el){return !!(thread&&thread.contains(el));}
function before(el){return !!last&&!(last.compareDocumentPosition(el)&4);}
function mine(n){return /(^|[\s(])(codigo|continuar)-[^\s]*\.zip/i.test(n||'');}
var els=document.querySelectorAll('a[download],a[href],button,[role="button"],[role="menuitem"]');
for(var i=els.length-1;i>=0;i--){var el=els[i];
if(old(el)||before(el))continue;
var lb=lab(el);
if(!lb||lb.length>80||!re.test(lb))continue;
if(us.length>1&&!inThread(el)&&!st.opened)continue;
var nm=cardName(el)||(/\.zip/i.test(lb)?lb:'')||st.open||'';
if(!nm||mine(nm))continue;
var key='d|'+nm;if(st.done[key])continue;
st.done[key]=1;el.click();
return JSON.stringify({a:'dl',n:nm});}
for(var j=els.length-1;j>=0;j--){var e2=els[j];
if(old(e2)||before(e2))continue;
var l2=lab(e2);
if(!l2||l2.length>120||!/\.zip\b/i.test(l2))continue;
var k2='o|'+l2;if(st.done[k2])continue;
st.done[k2]=1;st.opened=true;st.open=(l2.match(/[\w.\-() ]+\.zip/i)||[l2])[0];e2.click();
return JSON.stringify({a:'open',n:st.open});}
return JSON.stringify({a:'none'});
}catch(er){return JSON.stringify({a:'err',m:String(er)});}})();"""
}
