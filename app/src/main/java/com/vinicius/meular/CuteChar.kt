package com.vinicius.meular

import android.content.res.AssetManager
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * Personagem modular (BoZo Stylized Modular Characters), convertido para assets/cute:
 *  - base.bin (CUT1): corpo + boca + cabeça + olhos + os clips Idle/Walk/Jump já "assados" em matrizes de osso por quadro;
 *  - p_*.bin (CUP1): uma peça por arquivo (roupa, cabelo, sapato...), só malha + pesos + UV para o MESMO esqueleto;
 *  - pieces.txt: manifesto das peças (slot|id|arquivo|textura) e a roupa padrão (outfit=slot/id,...).
 * A pele é feita na CPU a cada quadro: os 55 ossos são interpolados uma vez e cada peça ativa usa as mesmas matrizes,
 * então qualquer peça acompanha Idle/Walk/Jump sem alterar as animações.
 * Se o carregamento modular falhar, volta ao arquivo antigo f_1.bin (personagem inteiro, sem peças);
 * se isso também falhar, [frame] devolve null e o app usa o personagem antigo esculpido.
 */
object CuteChar {
    const val ENABLED = true
    const val IDLE = 0
    const val WALK = 1
    const val JUMP = 2
    /** Velocidade de reprodução do andar: com 1,28 m/s no jogo (1,065 m/s do clip × 1,2) os pés não deslizam no chão. */
    private const val WALK_RATE = 1.2f

    @Volatile var assets: AssetManager? = null

    /** true quando o personagem novo carregou e pode ser desenhado. */
    val ready: Boolean get() = ENABLED && loaded && cur != null

    /** true quando está usando base.bin + peças (false = reserva f_1.bin inteiro). */
    @Volatile var modular = false
        private set

    /** Malha rígida que segue o esqueleto: o corpo-base ou uma peça. */
    private class Skin(
        val key: String,
        val tex: String,
        val nv: Int,
        val ni: Int,
        val pos: FloatArray,      // 3 por vértice (pose de referência)
        val nor: FloatArray,      // 3 por vértice
        val bi: IntArray,         // 4 por vértice
        val bw: FloatArray,       // 4 por vértice
        val out: FloatArray,      // STRIDE por vértice (já com cor e UV; pos/normal são reescritos por quadro)
        val fb: FloatBuffer,
        val ib: ShortBuffer,
        val idx: ShortArray       // índices (cópia em array, para o corpo-base poder esconder triângulos)
    ) {
        var part: Part? = null
        var hide: ShortArray? = null   // triângulos do corpo-base cobertos por esta peça (Etapa 4)
    }

    /** Conjunto ativo: as peças e a malha pronta para desenhar (trocados juntos, sem quadro "pela metade"). */
    private class Active(val skins: List<Skin>, val mesh: Mesh)

    private var nb = 0
    private var clipLen = FloatArray(0)
    private var clipFrames = IntArray(0)
    private var clipData = arrayOfNulls<FloatArray>(0) // frames * nb * 12
    private var baseSkin: Skin? = null
    private val library = LinkedHashMap<String, Skin>()   // todas as peças conhecidas, na ordem do manifesto
    @Volatile private var wanted: List<String> = emptyList()
    @Volatile private var cur: Active? = null
    private val texIds = HashMap<String, Int>()
    private var loaded = false
    private var failed = false
    private var atlasTex = 0
    private var hairTex = 0
    @Volatile private var wear: Set<String>? = null   // null = roupa padrão do manifesto (a Mary)
    private val MANAGED = setOf("hairBack", "hairFront", "top", "bottom", "overall")

    /** Chaves ("slot/id") de todas as peças disponíveis. */
    fun available(): List<String> = library.keys.toList()

    /** Peças em uso agora (chaves "slot/id", na ordem de desenho). */
    fun outfit(): List<String> = wanted

    /** Troca a roupa: lista de chaves "slot/id". Chaves desconhecidas são ignoradas. A ordem de desenho é a do manifesto. */
    fun setOutfit(keys: List<String>) {
        wanted = keys.toList()
        rebuildActive()
    }

    /** Etapas 3 e 4: peças escolhidas no criador (chaves "slot/id" de cabelo, blusa, calça/saia, vestido e roupa de baixo). */
    fun setWear(keys: List<String>) {
        wear = keys.toSet()
        rebuildActive()
    }

    private fun rebuildActive() {
        val bs = baseSkin ?: return
        val bp = bs.part ?: return        // GL ainda não pronto: o initGl chama de novo
        val w = wear
        val act = ArrayList<Skin>()
        val parts = ArrayList<Part>()
        val hide = BooleanArray(bs.ni / 3)
        var anyHide = false
        for ((k, s) in library) {
            val slot = k.substringBefore('/')
            val on = if (w != null && slot in MANAGED) k in w else k in wanted
            if (!on) continue
            val p = s.part ?: continue
            act.add(s)
            parts.add(p)
            s.hide?.let { h ->
                for (t in h) {
                    val ti = t.toInt() and 0xFFFF
                    if (ti < hide.size) { hide[ti] = true; anyHide = true }
                }
            }
        }
        // corpo-base: sem os triângulos cobertos pelas roupas (a pele não atravessa o tecido)
        var body = bp
        if (anyHide) {
            var keep = 0
            for (h in hide) if (!h) keep++
            val ib = ByteBuffer.allocateDirect(keep * 3 * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
            for (t in hide.indices) {
                if (hide[t]) continue
                ib.put(bs.idx[t * 3]); ib.put(bs.idx[t * 3 + 1]); ib.put(bs.idx[t * 3 + 2])
            }
            ib.position(0)
            body = Part(Mt.PLAIN, bs.fb, bs.nv, ib, keep * 3, bp.tex)
        }
        parts.add(0, body)
        cur = Active(act, Mesh(parts))
    }

    /** Chamar na thread GL (onSurfaceCreated): carrega o modelo e cria as texturas. */
    fun initGl() {
        if (!ENABLED) return
        val am = assets ?: return
        try {
            if (!loaded && !failed) load(am)
            if (loaded) {
                texIds.clear()
                val names = LinkedHashSet<String>()
                baseSkin?.let { names.add(it.tex) }
                for (s in library.values) names.add(s.tex)
                for (n in names) texIds[n] = uploadTexture(am, n)
                baseSkin?.let { it.part = Part(Mt.PLAIN, it.fb, it.nv, it.ib, it.ni, texIds[it.tex] ?: 0) }
                for (s in library.values) s.part = Part(Mt.PLAIN, s.fb, s.nv, s.ib, s.ni, texIds[s.tex] ?: 0)
                rebuildActive()
                atlasTex = texIds["textures.png"] ?: 0
                hairTex = texIds["extra.png"] ?: 0
                CuteColor.start(am)   // Etapa 2: cores escolhidas (carrega em segundo plano e sobe a textura recolorida)
            }
        } catch (e: Throwable) {
            failed = true
            loaded = false
            cur = null
        }
    }

    private fun uploadTexture(am: AssetManager, name: String): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val bmp = am.open("cute/$name").use { BitmapFactory.decodeStream(it) }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        bmp.recycle()
        return ids[0]
    }

    private fun load(am: AssetManager) {
        try {
            loadModular(am)
            modular = true
        } catch (e: Throwable) {
            // modular falhou (arquivo ausente/corrompido): volta ao personagem inteiro antigo
            baseSkin = null
            library.clear()
            wanted = emptyList()
            clipData = arrayOfNulls(0)
            modular = false
            loadLegacy(am)
        }
        loaded = true
    }

    private fun loadModular(am: AssetManager) {
        baseSkin = readBase(am, "cute/base.bin", "base", "textures.png")
        library.clear()
        var outfit: List<String> = emptyList()
        val lines = am.open("cute/pieces.txt").bufferedReader(Charsets.UTF_8).use { it.readLines() }
        for (raw in lines) {
            val ln = raw.trim()
            if (ln.isEmpty() || ln.startsWith("#")) continue
            if (ln.startsWith("outfit=")) {
                outfit = ln.substring(7).split(',').map { it.trim() }.filter { it.isNotEmpty() }
                continue
            }
            val f = ln.split('|').map { it.trim() }
            require(f.size >= 4)
            val key = f[0] + "/" + f[1]
            library[key] = readPiece(am, "cute/" + f[2], key, f[3])
        }
        wanted = outfit
    }

    private fun loadLegacy(am: AssetManager) {
        baseSkin = readBase(am, "cute/f_1.bin", "base", "textures.png")
        library.clear()
        wanted = emptyList()
    }

    /** CUT1: cabeçalho, vértices, índices e clips. O f_1.bin antigo tem o mesmo formato (só com mais vértices). */
    private fun readBase(am: AssetManager, path: String, key: String, tex: String): Skin {
        val raw = am.open(path).use { it.readBytes() }
        val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        require(b.get().toInt() == 'C'.code && b.get().toInt() == 'U'.code && b.get().toInt() == 'T'.code && b.get().toInt() == '1'.code)
        val nv = b.int
        val ni = b.int
        nb = b.int
        val nc = b.int
        val sk = readSkin(b, key, tex, nv, ni)
        clipLen = FloatArray(nc)
        clipFrames = IntArray(nc)
        clipData = arrayOfNulls(nc)
        for (c in 0 until nc) {
            clipLen[c] = b.float
            val nf = b.int
            clipFrames[c] = nf
            val d = FloatArray(nf * nb * 12)
            for (i in d.indices) d[i] = b.float
            clipData[c] = d
        }
        return sk
    }

    /** CUP1: "CUP1", nv, ni, flags, vértices (44 bytes), índices locais (uint16) e, se flags&1, nHide + nHide ids (uint16) de triângulos do corpo cobertos. */
    private fun readPiece(am: AssetManager, path: String, key: String, tex: String): Skin {
        val raw = am.open(path).use { it.readBytes() }
        val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        require(b.get().toInt() == 'C'.code && b.get().toInt() == 'U'.code && b.get().toInt() == 'P'.code && b.get().toInt() == '1'.code)
        val nv = b.int
        val ni = b.int
        val flags = b.int  // bit 0: depois dos índices vem a lista de triângulos do corpo que esta peça cobre
        val sk = readSkin(b, key, tex, nv, ni)
        if (flags and 1 != 0) {
            val nh = b.int
            val h = ShortArray(nh)
            for (i in 0 until nh) h[i] = b.short
            sk.hide = h
        }
        return sk
    }

    /** nv vértices (pos, normal, uv, cor, ossos, pesos) e ni índices, a partir da posição atual do buffer. */
    private fun readSkin(b: ByteBuffer, key: String, tex: String, nv: Int, ni: Int): Skin {
        val pos = FloatArray(nv * 3)
        val nor = FloatArray(nv * 3)
        val bi = IntArray(nv * 4)
        val bw = FloatArray(nv * 4)
        val out = FloatArray(nv * STRIDE)
        for (v in 0 until nv) {
            for (k in 0 until 3) pos[v * 3 + k] = b.float
            for (k in 0 until 3) nor[v * 3 + k] = b.float
            val u = b.float
            val w = b.float
            val o = v * STRIDE
            out[o + 10] = u
            out[o + 11] = w
            out[o + 6] = (b.get().toInt() and 255) / 255f
            out[o + 7] = (b.get().toInt() and 255) / 255f
            out[o + 8] = (b.get().toInt() and 255) / 255f
            out[o + 9] = (b.get().toInt() and 255) / 255f
            for (k in 0 until 4) bi[v * 4 + k] = b.get().toInt() and 255
            for (k in 0 until 4) bw[v * 4 + k] = (b.get().toInt() and 255) / 255f
        }
        val idx = ShortArray(ni)
        for (i in 0 until ni) idx[i] = b.short
        val fb = ByteBuffer.allocateDirect(nv * STRIDE * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val ib = ByteBuffer.allocateDirect(ni * 2).order(ByteOrder.nativeOrder()).asShortBuffer().also {
            it.put(idx)
            it.position(0)
        }
        return Skin(key, tex, nv, ni, pos, nor, bi, bw, out, fb, ib, idx)
    }

    /** Duração (s) do clip. */
    fun clipSeconds(clip: Int): Float =
        if (loaded && clip in clipLen.indices) clipLen[clip] else 1f

    // estado por "slot" (0 = jogador, 1 = avatar da casa, 2 = criador): fase do laço e transição suave
    private val slotClip = IntArray(3) { -1 }
    private val slotT0 = FloatArray(3)
    private val slotBlend = FloatArray(3)
    private val slotNs = LongArray(3)
    private val slotLast = arrayOfNulls<FloatArray>(3)
    private var bone = FloatArray(0)

    private fun smooth01(x: Float): Float {
        val c = if (x < 0f) 0f else if (x > 1f) 1f else x
        return c * c * (3f - 2f * c)
    }

    /**
     * Pose do clip [clip] no tempo [t] (segundos). Devolve a malha (corpo + peças ativas) pronta para desenhar.
     * Nos clips em laço (parado/andar) a fase recomeça do zero quando o clip muda, e a troca
     * de clip é suavizada (≈0,15 s), sem "pulo" de pose. [slot] separa quem está desenhando.
     */
    fun frame(clip: Int, t: Float, slot: Int = 0): Mesh? {
        val cs = cur ?: return null
        CuteColor.uploadIfPending(atlasTex, hairTex)
        val bs = baseSkin ?: return null
        if (!loaded) return null
        val c = clip.coerceIn(0, clipData.size - 1)
        val sl = slot.coerceIn(0, slotClip.size - 1)
        val nf = clipFrames[c]
        val len = clipLen[c].coerceAtLeast(0.001f)
        val d = clipData[c] ?: return null
        val n12 = nb * 12
        if (bone.size != n12) bone = FloatArray(n12)

        val now = System.nanoTime()
        var dt = (now - slotNs[sl]) / 1_000_000_000f
        if (dt < 0f || dt > 0.25f) dt = 0.016f
        slotNs[sl] = now

        if (slotClip[sl] != c) {
            slotBlend[sl] = if (slotClip[sl] < 0) 0f else if (c == JUMP) 0.06f else 0.25f
            slotClip[sl] = c
            slotT0[sl] = t
        }
        var lt = if (c == JUMP) t else (t - slotT0[sl]) * (if (c == WALK) WALK_RATE else 1f)
        if (c != JUMP) {
            lt %= len
            if (lt < 0f) lt += len
        } else if (lt < 0f) lt = 0f

        val ft = (lt / len) * (nf - 1).coerceAtLeast(1)
        val f0 = ft.toInt().coerceIn(0, nf - 1)
        val f1 = (f0 + 1).coerceAtMost(nf - 1)
        val a = ft - f0
        val o0 = f0 * n12
        val o1 = f1 * n12
        for (j in 0 until n12) bone[j] = d[o0 + j] + (d[o1 + j] - d[o0 + j]) * a

        val prev = slotLast[sl]
        if (slotBlend[sl] > 0f && prev != null && prev.size == n12) {
            val dur = if (c == JUMP) 0.06f else 0.25f
            val k = smooth01(1f - slotBlend[sl] / dur)
            for (j in 0 until n12) bone[j] = prev[j] + (bone[j] - prev[j]) * k
            slotBlend[sl] -= dt
        } else {
            slotBlend[sl] = 0f
        }
        val keep: FloatArray = if (prev != null && prev.size == n12) prev else FloatArray(n12).also { slotLast[sl] = it }
        System.arraycopy(bone, 0, keep, 0, n12)

        // as mesmas matrizes de osso vestem o corpo-base e todas as peças ativas
        skinInto(bs, bone)
        for (s in cs.skins) skinInto(s, bone)
        return cs.mesh
    }

    private fun skinInto(s: Skin, mm: FloatArray) {
        val pos = s.pos
        val nor = s.nor
        val bi = s.bi
        val bw = s.bw
        val out = s.out
        for (v in 0 until s.nv) {
            val px = pos[v * 3]; val py = pos[v * 3 + 1]; val pz = pos[v * 3 + 2]
            val nx = nor[v * 3]; val ny = nor[v * 3 + 1]; val nz = nor[v * 3 + 2]
            var x = 0f; var y = 0f; var z = 0f
            var X = 0f; var Y = 0f; var Z = 0f
            for (k in 0 until 4) {
                val w = bw[v * 4 + k]
                if (w <= 0f) continue
                val bo = bi[v * 4 + k] * 12
                x += w * (mm[bo] * px + mm[bo + 1] * py + mm[bo + 2] * pz + mm[bo + 3])
                y += w * (mm[bo + 4] * px + mm[bo + 5] * py + mm[bo + 6] * pz + mm[bo + 7])
                z += w * (mm[bo + 8] * px + mm[bo + 9] * py + mm[bo + 10] * pz + mm[bo + 11])
                X += w * (mm[bo] * nx + mm[bo + 1] * ny + mm[bo + 2] * nz)
                Y += w * (mm[bo + 4] * nx + mm[bo + 5] * ny + mm[bo + 6] * nz)
                Z += w * (mm[bo + 8] * nx + mm[bo + 9] * ny + mm[bo + 10] * nz)
            }
            val o = v * STRIDE
            out[o] = x; out[o + 1] = y; out[o + 2] = z
            val nl = Math.sqrt((X * X + Y * Y + Z * Z).toDouble()).toFloat()
            val inv = if (nl > 1e-9f) 1f / nl else 0f
            out[o + 3] = X * inv; out[o + 4] = if (nl > 1e-9f) Y * inv else 1f; out[o + 5] = Z * inv
        }
        val buf = s.fb
        buf.position(0)
        buf.put(out, 0, s.nv * STRIDE)
        buf.position(0)
    }
}
