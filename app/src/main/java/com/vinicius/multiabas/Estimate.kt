package com.vinicius.multiabas

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Estimativa de uso por conta (para contas free, que não têm a barra de uso).
 *
 * Conta "unidades" (≈ tokens: caracteres / 3,5) a partir do que o app vê em cada chat:
 *  - texto novo que apareceu no chat (pergunta + resposta);
 *  - a cada mensagem nova, o chat inteiro até ali é relido pelo Claude, então soma o tamanho do contexto;
 *  - arquivos anexados pelo Script.
 * A capacidade da conta é APRENDIDA: toda vez que o Claude avisa que o limite acabou, o que foi usado
 * na janela vira a nova capacidade (média com o que já foi aprendido). Antes disso é só um chute inicial.
 */
object Est {
    const val WINDOW = 5 * 3600_000L
    const val DEFAULT_CAP = 50_000.0
    private const val PREF = "usage_est"

    class St {
        val ev = ArrayList<Pair<Long, Double>>()
        var reset = 0L
        var cap = 0.0
        var learned = 0
        val chats = LinkedHashMap<String, IntArray>()   // url -> [tamanho, mensagens]
    }

    private val map = HashMap<String, St>()
    @Volatile var version = 0


    @Synchronized
    fun load(ctx: Context) {
        map.clear()
        try {
            val o = JSONObject(ctx.getSharedPreferences(PREF, 0).getString("data", "{}") ?: "{}")
            o.keys().forEach { id ->
                val x = o.getJSONObject(id)
                val st = St()
                st.reset = x.optLong("reset")
                st.cap = x.optDouble("cap", 0.0)
                st.learned = x.optInt("learned")
                val ev = x.optJSONArray("ev") ?: JSONArray()
                for (i in 0 until ev.length()) { val p = ev.getJSONArray(i); st.ev.add(p.getLong(0) to p.getDouble(1)) }
                val ch = x.optJSONObject("chats") ?: JSONObject()
                ch.keys().forEach { u -> val p = ch.getJSONArray(u); st.chats[u] = intArrayOf(p.getInt(0), p.getInt(1)) }
                map[id] = st
            }
        } catch (_: Exception) { }
    }

    private fun save(ctx: Context) {
        version++
        try {
            val o = JSONObject()
            map.forEach { (id, st) ->
                val ev = JSONArray(); st.ev.forEach { ev.put(JSONArray().put(it.first).put(it.second)) }
                val ch = JSONObject(); st.chats.forEach { (u, v) -> ch.put(u, JSONArray().put(v[0]).put(v[1])) }
                o.put(id, JSONObject().put("reset", st.reset).put("cap", st.cap).put("learned", st.learned).put("ev", ev).put("chats", ch))
            }
            ctx.getSharedPreferences(PREF, 0).edit().putString("data", o.toString()).apply()
        } catch (_: Exception) { }

    }

    private fun st(id: String) = map.getOrPut(id) { St() }

    private fun prune(s: St, now: Long) {
        s.ev.removeAll { it.first < now - WINDOW }
        while (s.ev.size > 400) s.ev.removeAt(0)
    }

    private fun addRaw(s: St, units: Double, now: Long) {
        if (units <= 0.0) return
        s.ev.add(now to units)
        prune(s, now)
    }

    /** Chamado a cada leitura do chat de uma aba (a cada ~15 s). */
    @Synchronized
    fun observe(ctx: Context, tabId: String, url: String, text: String) {
        if (!url.contains("/chat/")) return
        val s = st(tabId)
        val now = System.currentTimeMillis()
        val len = text.length
        val turns = Regex("### VOCÊ").findAll(text).count()
        val base = s.chats[url]
        if (base == null) {
            // chat que nunca vi: se já veio grande, é conversa antiga — só guarda como ponto de partida
            if (len < 6000) addRaw(s, len / 3.5, now)
        } else {
            val d = len - base[0]
            var u = 0.0
            if (d > 0) u += d / 3.5
            val dt = turns - base[1]

            if (dt > 0) u += dt * (minOf(base[0], len) / 3.5)
            addRaw(s, u, now)
        }
        s.chats.remove(url)
        s.chats[url] = intArrayOf(len, turns)
        while (s.chats.size > 20) s.chats.remove(s.chats.keys.first())
        save(ctx)
    }

    /** Soma manual (ex.: arquivo anexado pelo Script). */
    @Synchronized
    fun add(ctx: Context, tabId: String, units: Double) {
        addRaw(st(tabId), units, System.currentTimeMillis())
        save(ctx)
    }

    @Synchronized
    fun used(tabId: String): Double {
        val s = map[tabId] ?: return 0.0
        val now = System.currentTimeMillis()
        val from = maxOf(now - WINDOW, s.reset)
        return s.ev.filter { it.first > from }.sumOf { it.second }
    }

    @Synchronized
    fun cap(tabId: String): Double {
        val c = map[tabId]?.cap ?: 0.0
        if (c > 0.0) return c
        val others = map.values.filter { it.cap > 0.0 }.map { it.cap }
        return if (others.isNotEmpty()) others.average() else DEFAULT_CAP
    }


    @Synchronized
    fun learned(tabId: String): Int = map[tabId]?.learned ?: 0

    @Synchronized
    fun known(tabId: String): Boolean = map[tabId]?.let { it.ev.isNotEmpty() || it.cap > 0.0 } ?: false

    /** 0..100. Passou da capacidade sem o Claude avisar? A capacidade sobe (estava subestimada). */
    @Synchronized
    fun percent(ctx: Context, tabId: String): Int {
        val u = used(tabId)
        var c = cap(tabId)
        if (u > c) {
            val s = st(tabId)
            s.cap = u * 1.1
            c = s.cap
            save(ctx)
        }
        return ((u / c) * 100).toInt().coerceIn(0, 99)
    }

    /** Quando a janela deve reiniciar (estimado): 5 h depois da 1ª mensagem da janela. */
    @Synchronized
    fun resetGuess(tabId: String): Long {
        val s = map[tabId] ?: return 0L
        val now = System.currentTimeMillis()
        val from = maxOf(now - WINDOW, s.reset)
        val first = s.ev.filter { it.first > from }.minByOrNull { it.first }?.first ?: return 0L
        return first + WINDOW
    }

    /** O Claude avisou que o limite acabou: aprende a capacidade e começa uma janela nova quando ele voltar. */
    @Synchronized

    fun onLimit(ctx: Context, tabId: String, until: Long) {
        val s = st(tabId)
        val u = used(tabId)
        if (u > 1500.0) {
            s.cap = if (s.learned == 0 || s.cap <= 0.0) u else (s.cap * 0.5 + u * 0.5)
            s.learned++
        }
        s.reset = if (until > 0L) until else System.currentTimeMillis() + WINDOW
        s.ev.clear()
        save(ctx)
    }

    @Synchronized
    fun zero(ctx: Context, tabId: String) {
        val s = st(tabId)
        s.reset = System.currentTimeMillis()
        s.ev.clear()
        save(ctx)
    }

    /** "Agora está em X%": ajusta a capacidade para que o que foi contado dê X%. */
    @Synchronized
    fun setPercent(ctx: Context, tabId: String, pct: Int) {
        val u = used(tabId)
        if (pct in 1..99 && u > 500.0) {
            val s = st(tabId)
            s.cap = u * 100.0 / pct
            s.learned++
            save(ctx)
        }
    }


    @Synchronized
    fun forget(ctx: Context, tabId: String) {
        map.remove(tabId)
        save(ctx)
    }
}
