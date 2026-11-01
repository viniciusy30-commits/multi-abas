package com.vinicius.meular

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.opengl.GLES20
import android.opengl.GLUtils

/**
 * Cores do personagem modular (Etapa 2).
 *
 * O atlas colorido `cute/textures.png` (2048x1024) é a base. Os arquivos `cute/recolor.png`, `cute/recolor_s.png` e
 * `cute/recolor.txt` (gerados no PC por `ferramentas/etapa2_masks.py` a partir das máscaras do pack) dizem, para cada pixel,
 * a qual região ele pertence, quanto pesa em cada um dos 2 slots de cor da região e a sombra dele.
 * Por pixel e canal:  saída = (base + S*(w0*max(D0,0) + w1*max(D1,0))) * (1 + w0*min(D0/ref0,0) + w1*min(D1/ref1,0)),
 * onde D = cor escolhida - cor de referência (a cor com que a Mary foi assada). Com as cores originais a saída é IGUAL à base.
 *
 * O cálculo roda numa thread de fundo (a última escolha vence) e a thread GL só sobe a textura pronta (glTexSubImage2D)
 * no começo de [CuteChar.frame]. Não mexe em malha, ossos nem animações.
 */
object CuteColor {
    const val SLOTS = 10
    const val S_PELE = 0
    const val S_OLHOS = 1
    const val S_CABELO = 2
    const val S_PONTAS = 3
    const val S_BLUSA = 4
    const val S_DETALHE = 5
    const val S_SAIA = 6
    const val S_SANDALIA = 7
    const val S_TIRAS = 8
    const val S_DETALHE_BAIXO = 9

    val SLOT_NAMES = listOf("Pele", "Olhos", "Cabelo", "Pontas", "Blusa", "Detalhe", "Saia", "Sandália", "Tiras", "Detalhe baixo")

    private fun pal(vararg hex: String): IntArray = IntArray(hex.size) { Color.parseColor(hex[it]) }

    private val SKIN = pal("#FFE0C4", "#F5C9A0", "#E0A878", "#C58B5C", "#A8704A", "#8E5A3A", "#6B4430", "#4A2F22")
    private val EYES = pal("#5B3A1E", "#B5772B", "#3E9B5B", "#3B7FD0", "#7F8C99", "#7B5CD6", "#C94B6D", "#1B1B1B")
    private val HAIR = pal(
        "#1C1A1A", "#3B2A1E", "#6B4A2F", "#A66A3A", "#D8B25E", "#F0D9A0",
        "#B5482A", "#9A9A9F", "#EDEDED", "#3F6FD8", "#E86FA8", "#3FB57A"
    )
    private val CLOTH = pal(
        "#FFFFFF", "#2B2F3A", "#E5484D", "#F2A33A", "#F4D35E", "#3CCB7F", "#3E8EDE",
        "#7B5CD6", "#EE6FA6", "#8B6B4A", "#2E4A7D", "#6B6F7A", "#C9B79C", "#B03A48"
    )
    private val SHOE = pal("#FFFFFF", "#1E2230", "#E5484D", "#3E8EDE", "#7A4E32", "#F2A33A", "#EE6FA6", "#6B6F7A")

    private val PALS = arrayOf(SKIN, EYES, HAIR, HAIR, CLOTH, CLOTH, CLOTH, SHOE, SHOE, CLOTH)

    /** Cores (ARGB) que o jogador pode escolher no slot. */
    fun palette(slot: Int): IntArray = PALS[slot.coerceIn(0, SLOTS - 1)]

    /** Todos os slots em "original" (-1). */
    fun defaults(): IntArray = IntArray(SLOTS) { -1 }

    // ------------------------------------------------------------------ dados

    private class Region(val id: Int, val s0: Int, val s1: Int, val ref0: FloatArray, val ref1: FloatArray)

    /** Um atlas recolorível: textura base + máscaras (recolor) + resultado. A Mary usa textures.png; os cabelos novos usam hair.png. */
    private class Layer(val tex: String, val mask: String, val maskS: String, val table: String) {
        var w = 0
        var h = 0
        var base: IntArray = IntArray(0)   // ARGB da textura base
        var meta: IntArray = IntArray(0)   // id<<24 | w0<<16 | w1<<8 | S
        var out: IntArray = IntArray(0)
        var bmp: Bitmap? = null
        val regions = ArrayList<Region>()
        @Volatile var pending = false
        var modified = false               // o bitmap atual tem cores diferentes da base
        var loaded = false
    }

    private val layers = arrayOf(
        Layer("textures.png", "recolor.png", "recolor_s.png", "recolor.txt"),
        Layer("extra.png", "extra_recolor.png", "extra_recolor_s.png", "extra_recolor.txt")
    )

    private val lock = Any()
    @Volatile private var assets: AssetManager? = null
    @Volatile private var want: IntArray = defaults()
    @Volatile private var failed = false
    private var working = false
    private var dirty = false

    /** Escolhe as cores (índice da paleta por slot, -1 = original). Pode ser chamado de qualquer thread, a qualquer hora. */
    fun setColors(cc: IntArray) {
        val c = defaults()
        for (i in 0 until SLOTS) if (i < cc.size) c[i] = cc[i]
        synchronized(lock) {
            want = c
            dirty = true
        }
        kick()
    }

    /** Chamar na thread GL quando o contexto foi (re)criado: os dados são carregados em segundo plano. */
    fun start(am: AssetManager) {
        assets = am
        synchronized(lock) {
            for (l in layers) if (l.modified) l.pending = true   // textura nova no GL: subir de novo as cores escolhidas
            dirty = true
        }
        kick()
    }

    private fun kick() {
        val am = assets ?: return
        if (failed) return
        synchronized(lock) {
            if (working) return
            working = true
        }
        val t = Thread { work(am) }
        t.isDaemon = true
        t.name = "cute-color"
        t.start()
    }

    private fun work(am: AssetManager) {
        try {
            for (l in layers) {
                if (!l.loaded) {
                    try {
                        load(am, l)
                        l.loaded = true
                    } catch (e: Throwable) {
                        if (l === layers[0]) throw e   // o atlas da Mary é obrigatório; o dos cabelos novos é opcional
                    }
                }
            }
            while (true) {
                val snap: IntArray
                synchronized(lock) {
                    if (!dirty) {
                        working = false
                        return
                    }
                    dirty = false
                    snap = want
                }
                val eff = effective(snap)
                var ident = true
                for (e in eff) if (e != null) ident = false
                for (l in layers) {
                    if (!l.loaded) continue
                    if (ident && !l.modified) continue
                    compute(l, eff)
                    val b = l.bmp ?: continue
                    synchronized(lock) {
                        b.setPixels(l.out, 0, l.w, 0, 0, l.w, l.h)
                        l.modified = !ident
                        l.pending = true
                    }
                }
            }
        } catch (e: Throwable) {
            failed = true
            synchronized(lock) { working = false }
        }
    }

    /** Cor final de cada slot (null = original). Pontas/detalhe/tiras acompanham a cor principal enquanto não escolhidas. */
    private fun effective(cc: IntArray): Array<FloatArray?> {
        val e = arrayOfNulls<FloatArray>(SLOTS)
        for (s in 0 until SLOTS) {
            val i = cc[s]
            val p = PALS[s]
            if (i >= 0 && i < p.size) {
                val c = p[i]
                e[s] = floatArrayOf(Color.red(c).toFloat(), Color.green(c).toFloat(), Color.blue(c).toFloat())
            }
        }
        if (e[S_PONTAS] == null) e[S_PONTAS] = lighten(e[S_CABELO], 0.25f)
        if (e[S_DETALHE] == null) e[S_DETALHE] = lighten(e[S_BLUSA], 0.25f)
        if (e[S_TIRAS] == null) e[S_TIRAS] = e[S_SANDALIA]
        if (e[S_DETALHE_BAIXO] == null) e[S_DETALHE_BAIXO] = lighten(e[S_SAIA], 0.25f)
        return e
    }

    private fun lighten(c: FloatArray?, k: Float): FloatArray? {
        if (c == null) return null
        return floatArrayOf(c[0] + (255f - c[0]) * k, c[1] + (255f - c[1]) * k, c[2] + (255f - c[2]) * k)
    }

    // ------------------------------------------------------------------ carga

    private fun decode(am: AssetManager, name: String): Bitmap {
        val o = BitmapFactory.Options()
        o.inPreferredConfig = Bitmap.Config.ARGB_8888
        return am.open("cute/$name").use { BitmapFactory.decodeStream(it, null, o) } ?: throw IllegalStateException(name)
    }

    private fun load(am: AssetManager, l: Layer) {
        val tb = decode(am, l.tex)
        l.w = tb.width
        l.h = tb.height
        val w = l.w
        val h = l.h
        l.base = IntArray(w * h)
        tb.getPixels(l.base, 0, w, 0, 0, w, h)
        tb.recycle()

        val rb = decode(am, l.mask)
        require(rb.width == w && rb.height == h)
        l.meta = IntArray(w * h)
        rb.getPixels(l.meta, 0, w, 0, 0, w, h)
        rb.recycle()
        val sb = decode(am, l.maskS)
        require(sb.width == w && sb.height == h)
        val tmp = IntArray(w * h)
        sb.getPixels(tmp, 0, w, 0, 0, w, h)
        sb.recycle()
        val meta = l.meta
        for (i in meta.indices) {
            val m = meta[i]
            val id = m and 0xFF                    // B = id da região
            val w0 = (m shr 16) and 0xFF           // R
            val w1 = (m shr 8) and 0xFF            // G
            val s = tmp[i] and 0xFF
            meta[i] = (id shl 24) or (w0 shl 16) or (w1 shl 8) or s
        }

        l.regions.clear()
        val lines = am.open("cute/" + l.table).bufferedReader(Charsets.UTF_8).use { it.readLines() }
        for (raw in lines) {
            val ln = raw.trim()
            if (ln.isEmpty() || ln.startsWith("#")) continue
            val f = ln.split('|')
            if (f.size < 6) continue
            val r0 = f[4].trim().split(' ').filter { it.isNotEmpty() }.map { it.toFloat() }.toFloatArray()
            val r1 = f[5].trim().split(' ').filter { it.isNotEmpty() }.map { it.toFloat() }.toFloatArray()
            l.regions.add(Region(f[0].trim().toInt(), f[2].trim().toInt(), f[3].trim().toInt(), r0, r1))
        }
        l.out = IntArray(w * h)
        l.bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    }

    // ------------------------------------------------------------------ cálculo

    private fun compute(l: Layer, e: Array<FloatArray?>) {
        val p0 = FloatArray(8 * 3)
        val p1 = FloatArray(8 * 3)
        val n0 = FloatArray(8 * 3)
        val n1 = FloatArray(8 * 3)
        for (r in l.regions) {
            if (r.id < 1 || r.id > 7) continue
            for (c in 0 until 3) {
                val k = r.id * 3 + c
                val c0 = if (r.s0 >= 0) e[r.s0] else null
                if (c0 != null) {
                    val d = c0[c] - r.ref0[c]
                    p0[k] = if (d > 0f) d else 0f
                    n0[k] = if (d < 0f) d / maxOf(r.ref0[c], 1f) else 0f
                }
                val c1 = if (r.s1 >= 0) e[r.s1] else null
                if (c1 != null) {
                    val d = c1[c] - r.ref1[c]
                    p1[k] = if (d > 0f) d else 0f
                    n1[k] = if (d < 0f) d / maxOf(r.ref1[c], 1f) else 0f
                }
            }
        }
        val b = l.base
        val m = l.meta
        val o = l.out
        val n = l.w * l.h
        val k255 = 1f / 255f
        val k2 = 2f / 255f
        var i = 0
        while (i < n) {
            val mv = m[i]
            val id = mv ushr 24
            val bv = b[i]
            if (id == 0 || id > 7) {
                o[i] = bv
                i++
                continue
            }
            val k = id * 3
            val w0 = ((mv shr 16) and 0xFF) * k255
            val w1 = ((mv shr 8) and 0xFF) * k255
            val s = (mv and 0xFF) * k2
            val rr = ((bv shr 16) and 0xFF).toFloat()
            val gg = ((bv shr 8) and 0xFF).toFloat()
            val bb = (bv and 0xFF).toFloat()
            var r = (rr + s * (w0 * p0[k] + w1 * p1[k])) * (1f + w0 * n0[k] + w1 * n1[k])
            var g = (gg + s * (w0 * p0[k + 1] + w1 * p1[k + 1])) * (1f + w0 * n0[k + 1] + w1 * n1[k + 1])
            var bl = (bb + s * (w0 * p0[k + 2] + w1 * p1[k + 2])) * (1f + w0 * n0[k + 2] + w1 * n1[k + 2])
            if (r < 0f) r = 0f else if (r > 255f) r = 255f
            if (g < 0f) g = 0f else if (g > 255f) g = 255f
            if (bl < 0f) bl = 0f else if (bl > 255f) bl = 255f
            o[i] = (0xFF shl 24) or ((r + 0.5f).toInt() shl 16) or ((g + 0.5f).toInt() shl 8) or (bl + 0.5f).toInt()
            i++
        }
    }

    // ------------------------------------------------------------------ GL

    /** Thread GL: se há cores novas prontas, sobe para as texturas: [tex] = atlas da Mary, [hairTex] = atlas dos cabelos. */
    fun uploadIfPending(tex: Int, hairTex: Int = 0) {
        up(layers[0], tex)
        up(layers[1], hairTex)
    }

    private fun up(l: Layer, tex: Int) {
        if (!l.pending || tex == 0) return
        synchronized(lock) {
            val b = l.bmp ?: return
            if (!l.pending) return
            l.pending = false
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, b)
        }
    }
}
