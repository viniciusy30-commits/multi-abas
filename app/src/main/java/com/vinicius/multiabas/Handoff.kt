package com.vinicius.multiabas

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Continuar o projeto em outra conta quando o limite da primeira acaba.
 *
 * - Enquanto você conversa, o app guarda (a cada ~15s) o texto da conversa de cada aba do Claude.
 * - Cada ZIP entregue ao GitHub fica registrado num log por projeto.
 * - Na hora de trocar de conta, monta um ZIP com: instruções (LEIA-ME-PRIMEIRO.md),
 *   a conversa anterior, o log de entregas e o código atual do repositório.
 */
object Handoff {

    /** Lê a conversa visível na página do Claude e detecta aviso de limite (fora das mensagens). */
    const val CAPTURE_JS = """(function(){try{
var sel='[data-testid="user-message"], .font-claude-response, .font-claude-message, [data-is-streaming]';
var nodes=document.querySelectorAll(sel), seen=[], parts=[];
nodes.forEach(function(n){
  for(var i=0;i<seen.length;i++){ if(seen[i].contains(n)) return; }
  seen.push(n);
  var t=(n.innerText||'').trim();
  if(!t) return;
  var u=n.matches('[data-testid="user-message"]');
  parts.push((u?'### VOCÊ':'### CLAUDE')+'\n'+t);
});
var body=(document.body&&document.body.innerText)||'';
if(!parts.length){
  var m=document.querySelector('main');
  if(m&&(m.innerText||'').trim().length>0) parts.push((m.innerText||'').trim());
}
var rest=body;
seen.forEach(function(n){ try{ rest=rest.replace(n.innerText,''); }catch(e){} });
rest=rest.slice(-1500);
var re=/sem\s+mensagens|mensagens\s+gratuitas|(you.?ve|you have)\s+(reached|hit)\s+(your|the)\b.{0,50}limit|usage limit|limit\s+(will\s+)?reset|resets?\s+(at|in|on)\s|atingiu\s+o\s+limite|limite\s.{0,40}(redefin|reinici|volt|renov)|volta(r[áa])?\s.{0,20}\d/i;
var tm=rest.match(/at[ée]\s+(\d{1,2}):(\d{2})/i);
return JSON.stringify({t:document.title,u:location.href,x:parts.join('\n\n'),l:re.test(rest),h:tm?(tm[1]+':'+tm[2]):''});
}catch(e){return JSON.stringify({t:'',u:'',x:'',l:false});}})();"""

    data class Snap(val title: String, val url: String, val text: String, val limit: Boolean, val until: String = "")

    /** evaluateJavascript devolve a string JS como literal JSON: desembrulha e lê o objeto. */
    fun parse(raw: String?): Snap? {
        if (raw.isNullOrBlank() || raw == "null") return null
        return try {
            val s = JSONArray("[$raw]").getString(0)
            val o = JSONObject(s)
            Snap(o.optString("t"), o.optString("u"), o.optString("x"), o.optBoolean("l"), o.optString("h"))
        } catch (_: Exception) { null }
    }

    private fun dir(ctx: Context) = File(ctx.filesDir, "handoff").apply { mkdirs() }
    private fun chatFile(ctx: Context, tabId: String) = File(dir(ctx), "chat_$tabId.txt")
    private fun metaFile(ctx: Context, tabId: String) = File(dir(ctx), "chat_$tabId.json")
    private fun logFile(ctx: Context, projectId: String) = File(dir(ctx), "log_$projectId.txt")

    private fun now() = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())

    /** Guarda a conversa da aba. Só reescreve se mudou e se veio conteúdo de verdade. */
    fun save(ctx: Context, tabId: String, s: Snap) {
        if (s.text.length < 40) return
        if (!s.url.contains("/chat/")) return
        try {
            val f = chatFile(ctx, tabId)
            val old = if (f.exists()) f.readText() else ""
            val body = "Título: ${s.title}\nLink: ${s.url}\n\n${s.text}"
            if (old.substringAfter("\n\n", "") == s.text) return
            // não troca uma conversa longa por um pedaço curto da MESMA conversa (página ainda carregando)
            val sameChat = old.contains("Link: ${s.url}")
            if (sameChat && s.text.length < (old.length * 0.5).toInt()) return
            f.writeText(body)
            metaFile(ctx, tabId).writeText(
                JSONObject().put("title", s.title).put("url", s.url).put("time", now()).toString()
            )
        } catch (_: Exception) { }
    }

    fun hasChat(ctx: Context, tabId: String) = chatFile(ctx, tabId).let { it.exists() && it.length() > 40 }

    fun chatInfo(ctx: Context, tabId: String): String? = try {
        val o = JSONObject(metaFile(ctx, tabId).readText())
        o.optString("time") + " · " + o.optString("title").take(40)
    } catch (_: Exception) { null }

    /** Uma linha por evento (ZIP enviado, build ok/falhou). Guarda as últimas 60. */
    fun log(ctx: Context, projectId: String, line: String) {
        try {
            val f = logFile(ctx, projectId)
            val lines = (if (f.exists()) f.readLines() else emptyList()) + "${now()} | $line"
            f.writeText(lines.takeLast(60).joinToString("\n"))
        } catch (_: Exception) { }
    }

    private fun readLog(ctx: Context, projectId: String): String =
        try { logFile(ctx, projectId).readText() } catch (_: Exception) { "" }

    fun unquote(raw: String?): String? {
        if (raw.isNullOrBlank() || raw == "null") return null
        return try { JSONArray("[$raw]").getString(0) } catch (_: Exception) { null }
    }

    /** Abre os blocos recolhidos ("executou N comandos") da ÚLTIMA resposta do Claude. Devolve quantos abriu. */
    const val EXPAND_JS = """(function(){var all=document.querySelectorAll('[data-is-streaming]');
if(!all.length)all=document.querySelectorAll('.font-claude-response,.font-claude-message');
var m=all[all.length-1];if(!m)return '0';
var bs=m.querySelectorAll('button[aria-expanded="false"]');var n=0;
for(var i=0;i<bs.length;i++){try{bs[i].click();n++;}catch(e){}}
return String(n);})();"""

    /** Texto da última resposta do Claude (com os blocos já abertos). */
    const val READ_LAST_JS = """(function(){var all=document.querySelectorAll('[data-is-streaming]');
if(!all.length)all=document.querySelectorAll('.font-claude-response,.font-claude-message');
var m=all[all.length-1];if(!m)return '';
return (m.innerText||'').trim();})();"""

    /** Monta o File do anexo (base64 já em window.__hf) e guarda em window.__hfDt. */
    const val BUILD_JS = """(function(name){try{var b=atob(window.__hf);var u=new Uint8Array(b.length);
for(var i=0;i<b.length;i++)u[i]=b.charCodeAt(i);
var f=new File([u],name,{type:__MIME__});var dt=new DataTransfer();dt.items.add(f);
window.__hfDt=dt;window.__hf='';return 'ok';}catch(e){return 'err:'+e.message;}})(__NAME__);"""

    /** Entrega o arquivo à página: direto no input[type=file] ou simulando arrastar-e-soltar. */
    const val INJECT_JS = """(function(mode){try{var dt=window.__hfDt;if(!dt)return 'nodt';
if(mode==='input'){var ins=document.querySelectorAll('input[type=file]'),pick=null;
for(var j=0;j<ins.length;j++){var a=(ins[j].getAttribute('accept')||'').toLowerCase();
if(!a||a.indexOf('zip')>=0||a.indexOf('*/*')>=0||(a.indexOf('.')>=0&&a.indexOf('image')<0)){pick=ins[j];break;}}
if(!pick&&ins.length)pick=ins[0];
if(!pick)return 'noinput';
pick.files=dt.files;
pick.dispatchEvent(new Event('input',{bubbles:true}));
pick.dispatchEvent(new Event('change',{bubbles:true}));
return 'input';}
var ed=document.querySelector('div[contenteditable="true"]');
if(!ed)return 'noeditor';
var t=ed.closest('fieldset,form')||ed.parentElement||ed;
['dragenter','dragover','drop'].forEach(function(n){t.dispatchEvent(new DragEvent(n,{bubbles:true,cancelable:true,dataTransfer:dt}));});
return 'drop';}catch(e){return 'err:'+e.message;}})('__MODE__');"""

    /** Salva o chat inteiro: abre todos os blocos, guarda texto, HTML, imagens, links e arquivos. */
    const val FULL_JS = """(function(tok){
var A=window.AndroidDl;
if(!A||!A.hfEnd){return 'nobridge';}
function sleep(ms){return new Promise(function(r){setTimeout(r,ms);});}
function nodesOf(){
 var sel='[data-testid="user-message"], .font-claude-response, .font-claude-message, [data-is-streaming]';
 var all=document.querySelectorAll(sel),out=[];
 all.forEach(function(n){for(var i=0;i<out.length;i++){if(out[i].contains(n))return;}out.push(n);});
 return out;
}
function extOf(m){m=(m||'').toLowerCase();
 if(m.indexOf('png')>=0)return 'png';if(m.indexOf('jpeg')>=0||m.indexOf('jpg')>=0)return 'jpg';
 if(m.indexOf('webp')>=0)return 'webp';if(m.indexOf('gif')>=0)return 'gif';if(m.indexOf('svg')>=0)return 'svg';
 if(m.indexOf('pdf')>=0)return 'pdf';if(m.indexOf('zip')>=0)return 'zip';if(m.indexOf('json')>=0)return 'json';
 if(m.indexOf('text')>=0)return 'txt';return 'bin';}
var total=0,LIMIT=20*1024*1024;
function readAs(blob){return new Promise(function(res){var fr=new FileReader();fr.onloadend=function(){res(fr.result);};fr.onerror=function(){res(null);};fr.readAsDataURL(blob);});}
function esc(s){return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;');}
async function grab(src,folder,base,fname){
 try{
  var blob;
  if(src.indexOf('data:')===0){var r0=await fetch(src);blob=await r0.blob();}
  else{var r=await fetch(src,{credentials:'include'});if(!r.ok)return null;blob=await r.blob();}
  if((blob.type||'').indexOf('text/html')===0)return null;
  if(blob.size<200||blob.size>12*1024*1024||total+blob.size>LIMIT)return null;
  var name=fname||(base+'.'+extOf(blob.type));
  var du=await readAs(blob);if(!du)return null;
  total+=blob.size;
  A.hfBin(tok,folder+'/'+name,blob.type||'',du);
  return folder+'/'+name;
 }catch(e){return null;}
}
(async function(){
 var sum={msgs:0,imgs:0,files:0,chars:0,err:''};
 try{
  A.hfProgress(tok,'Abrindo todos os blocos recolhidos…');
  for(var round=0;round<4;round++){
   var opened=0;
   nodesOf().forEach(function(m){m.querySelectorAll('button[aria-expanded="false"]').forEach(function(b){try{b.click();opened++;}catch(e){}});});
   if(!opened)break;
   await sleep(900);
  }
  var nodes=nodesOf(),parts=[],html=[],links=[],last='';
  for(var i=0;i<nodes.length;i++){
   var n=nodes[i],isU=n.matches('[data-testid="user-message"]');
   var t=(n.innerText||'').trim();
   if(!t&&!n.querySelector('img'))continue;
   parts.push((isU?'### VOCÊ':'### CLAUDE')+'\n'+t);
   if(!isU&&t)last=t;
   var c=n.cloneNode(true);
   c.querySelectorAll('script,style,iframe,video,audio').forEach(function(x){x.remove();});
   var oi=n.querySelectorAll('img'),ci=c.querySelectorAll('img');
   for(var j=0;j<oi.length&&j<ci.length;j++){
    var src=oi[j].currentSrc||oi[j].src||'';
    if(!src)continue;
    var w=oi[j].naturalWidth||0;
    if((w&&w<48)||/avatar|favicon|emoji/i.test(src)){ci[j].remove();continue;}
    A.hfProgress(tok,'Salvando imagem '+(sum.imgs+1)+'…');
    var loc=await grab(src,'imagens','img_'+(sum.imgs+1));
    if(loc){sum.imgs++;ci[j].setAttribute('src',loc);ci[j].removeAttribute('srcset');}
   }
   n.querySelectorAll('a[href]').forEach(function(a){var h=a.href;if(h&&h.indexOf('javascript')!==0)links.push({t:(a.innerText||'').trim().slice(0,80),h:h,d:a.getAttribute('download')||''});});
   html.push('<div class="'+(isU?'u':'c')+'"><b>'+(isU?'VOCÊ':'CLAUDE')+'</b>'+c.innerHTML+'</div>');
  }
  var seen={},lt=[];
  for(var k=0;k<links.length;k++){var L=links[k];if(seen[L.h])continue;seen[L.h]=1;lt.push((L.t||'(sem texto)')+' -> '+L.h);}
  var dl=links.filter(function(L){return L.d||/\/(download|files?|attachments?)\b/i.test(L.h);}).slice(0,15);
  for(var q=0;q<dl.length;q++){
   var D=dl[q];
   var fn=(D.d||D.h.split('?')[0].split('/').pop()||('arquivo_'+(q+1))).replace(/[^\w.\-]+/g,'_');
   A.hfProgress(tok,'Baixando arquivo '+(q+1)+' de '+dl.length+'…');
   var loc2=await grab(D.h,'arquivos','f'+q,fn);if(loc2)sum.files++;
  }
  var full=parts.join('\n\n');
  sum.msgs=parts.length;sum.chars=full.length;
  A.hfText(tok,'conversa-completa.txt',full);
  if(last)A.hfText(tok,'ultima-resposta.txt',last);
  if(lt.length)A.hfText(tok,'links.txt',lt.join('\n'));
  var doc='<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>'+esc(document.title)+'</title><style>body{font-family:sans-serif;background:#111;color:#eee;max-width:820px;margin:auto;padding:12px;line-height:1.5}.u{background:#1d2740;border-radius:12px;padding:10px;margin:16px 0}.c{margin:16px 0}pre{background:#000;padding:8px;overflow:auto;white-space:pre-wrap;border-radius:8px}img{max-width:100%}a{color:#8ab4ff}</style>'+html.join('\n');
  if(doc.length<8000000)A.hfText(tok,'conversa.html',doc);
 }catch(e){sum.err=String(e&&e.message||e);}
 A.hfEnd(tok,JSON.stringify(sum));
})();
return 'started';
})('__TOKEN__');"""

    const val STARTER = "Estou continuando um projeto que começou em outra conta do Claude " +
        "(o limite de lá acabou). Anexei um ZIP: leia primeiro o LEIA-ME-PRIMEIRO.md, depois a " +
        "conversa-anterior.txt; o código atual está na pasta codigo/. Entenda onde paramos e " +
        "continue exatamente de onde parou, sem refazer o que já está pronto. Eu só uso o celular: " +
        "entregue as mudanças como ZIP pronto para baixar (os arquivos alterados/novos na estrutura " +
        "do projeto), nunca código para copiar e colar. Se houver um trabalho-incompleto.txt, ele mostra " +
        "o que o Claude anterior estava fazendo quando o limite acabou: termine a partir dali. A pasta " +
        "chat-completo/ tem a conversa inteira com todos os blocos abertos, as imagens do chat e os links."

    private fun slug(s: String): String =
        java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').lowercase().ifEmpty { "chat" }

    private fun skip(path: String): Boolean {
        val p = path.lowercase()
        return p.startsWith(".git/") || p.endsWith(".keystore") || p.endsWith(".jks") ||
            p.endsWith(".apk") || p.startsWith("_incoming/")
    }

    private fun put(z: ZipOutputStream, name: String, text: String) {
        z.putNextEntry(ZipEntry(name))
        z.write(text.toByteArray(Charsets.UTF_8))
        z.closeEntry()
    }

    /**
     * Monta o ZIP de continuação em cache/handoff_out. Devolve o arquivo ou null se não deu.
     * Roda fora da thread principal (baixa o código pelo GitHub).
     */
    fun build(
        ctx: Context, fromName: String, fromTabId: String,
        project: Project?, token: String, unfinished: String?, extraDir: File?, prefix: String,
        onStatus: (String) -> Unit
    ): File? {
        val transcript = try { chatFile(ctx, fromTabId).readText() } catch (_: Exception) { "" }
        if (transcript.isBlank() && project == null && extraDir == null) return null

        val outDir = File(ctx.cacheDir, "handoff_out").apply { mkdirs() }
        outDir.listFiles()?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val out = File(outDir, "$prefix-${slug(project?.name ?: fromName)}-$stamp.zip")

        val log = if (project != null) readLog(ctx, project.id) else ""
        val branch = project?.branch?.ifBlank { "main" } ?: "main"

        var codeOk = false
        ZipOutputStream(out.outputStream().buffered()).use { z ->
            if (project != null && token.isNotBlank()) {
                onStatus("Baixando o código atual de ${project.fullRepo()}…")
                val rz = GitHubDelivery.downloadRepoZip(ctx, token, project, branch)
                if (rz != null && rz.exists()) {
                    onStatus("Empacotando o código…")
                    ZipInputStream(rz.inputStream().buffered()).use { zi ->
                        var e = zi.nextEntry
                        while (e != null) {
                            val n = e.name.substringAfter('/', "")
                            if (n.isNotEmpty() && !e.isDirectory && !skip(n)) {
                                z.putNextEntry(ZipEntry("codigo/$n"))
                                zi.copyTo(z)
                                z.closeEntry()
                            }
                            e = zi.nextEntry
                        }
                    }
                    codeOk = true
                }
            }
            onStatus("Salvando a conversa…")
            put(z, "conversa-anterior.txt", transcript.ifBlank { "(nenhuma conversa capturada nesta aba)" })
            put(z, "entregas.txt", log.ifBlank { "(nenhuma entrega registrada)" })
            if (!unfinished.isNullOrBlank()) put(z, "trabalho-incompleto.txt", unfinished)
            if (extraDir != null && extraDir.exists()) {
                extraDir.walkTopDown().filter { it.isFile }.forEach { file ->
                    val rel = file.relativeTo(extraDir).path.replace('\\', '/')
                    z.putNextEntry(ZipEntry("chat-completo/$rel"))
                    file.inputStream().use { it.copyTo(z) }
                    z.closeEntry()
                }
            }

            val tail = transcript.takeLast(6000)
            val md = buildString {
                appendLine("# Continuação de projeto — LEIA PRIMEIRO")
                appendLine()
                appendLine("Gerado em ${now()} pelo app Multi Abas, porque o limite da conta \"$fromName\" acabou.")
                appendLine("Você (Claude) está assumindo o trabalho de onde a outra conta parou.")
                appendLine()
                appendLine("## O que tem neste ZIP")
                appendLine("- `conversa-anterior.txt` — a conversa completa da conta anterior (use para entender pedidos, decisões e pendências).")
                appendLine("- `entregas.txt` — ZIPs que já foram entregues ao GitHub e o resultado do build.")
                if (extraDir != null) appendLine("- `chat-completo/` — o chat salvo por inteiro: `conversa-completa.txt` (com todos os blocos abertos), `conversa.html` (abre no navegador, com as imagens), `imagens/`, `arquivos/` e `links.txt`.")
                if (!unfinished.isNullOrBlank()) appendLine("- `trabalho-incompleto.txt` — a última resposta do Claude anterior, com comandos e arquivos que ele estava criando quando o limite acabou. O que estiver aqui e NÃO estiver em `codigo/` foi escrito mas nunca entregue: termine/recrie a partir daqui, sem recomeçar do zero.")
                if (codeOk) appendLine("- `codigo/` — código atual do repositório (branch `$branch`), já com tudo que foi entregue e buildou.")
                else appendLine("- (o código não pôde ser baixado; peça ao usuário o ZIP do projeto se precisar dele)")
                appendLine()
                if (project != null) {
                    appendLine("## Projeto")
                    appendLine("- Nome: ${project.name}")
                    appendLine("- Repositório: ${project.fullRepo()} (branch `$branch`)")
                    appendLine("- Entrega: ZIPs com os arquivos alterados/novos vão para `${project.folder}/` e um workflow descompacta, faz commit e gera o APK.")
                    if (project.packageName.isNotBlank()) appendLine("- Package: ${project.packageName}")
                    appendLine()
                }
                appendLine("## Como trabalhar")
                appendLine("1. Leia `conversa-anterior.txt`, principalmente o final: o último pedido pode estar incompleto.")
                appendLine("2. Confira em `codigo/` o que já existe antes de mexer; não refaça o que está pronto.")
                appendLine("3. O usuário só usa o celular: entregue tudo como ZIP pronto para baixar, nunca código para copiar e colar.")
                appendLine("4. Se algo estiver ambíguo, pergunte antes de reescrever.")
                appendLine()
                appendLine("## Final da conversa anterior (resumo rápido)")
                appendLine("```")
                appendLine(tail.ifBlank { "(vazio)" })
                appendLine("```")
            }
            put(z, "LEIA-ME-PRIMEIRO.md", md)
        }
        return if (out.length() > 100) out else null
    }
}
