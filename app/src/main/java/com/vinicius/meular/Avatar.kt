package com.vinicius.meular

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Paletas e nomes usados pelo criador de personagem. */
object AvaPal {
    val SKIN = intArrayOf(C("#FFE0C4"), C("#F5C9A0"), C("#E0A878"), C("#C58B5C"), C("#8E5A3A"), C("#5B3A26"))
    val HAIR = intArrayOf(
        C("#1C1A1A"), C("#3B2A1E"), C("#6B4A2F"), C("#D8B25E"), C("#B5482A"),
        C("#9A9A9F"), C("#EDEDED"), C("#3F6FD8"), C("#E86FA8"), C("#3FB57A")
    )
    val EYE = intArrayOf(C("#5B3A1E"), C("#3B7FD0"), C("#3E9B5B"), C("#7F8C99"), C("#B5772B"), C("#1B1B1B"))
    val TOP = intArrayOf(
        C("#FFFFFF"), C("#2B2F3A"), C("#E5484D"), C("#F2A33A"), C("#F4D35E"),
        C("#3CCB7F"), C("#3E8EDE"), C("#7B5CD6"), C("#EE6FA6"), C("#8B6B4A")
    )
    val BOT = intArrayOf(
        C("#2E4A7D"), C("#1E2230"), C("#6B6F7A"), C("#C9B79C"), C("#7A4E32"),
        C("#FFFFFF"), C("#3F7D5A"), C("#B03A48"), C("#E8A6C0"), C("#4F5BD5")
    )
    val SHOE = intArrayOf(C("#FFFFFF"), C("#1E2230"), C("#E5484D"), C("#3E8EDE"), C("#7A4E32"), C("#F2A33A"))

    val HAIR_N = listOf("Careca", "Curto", "Médio", "Longo", "Rabo de cavalo", "Coque", "Moicano", "Black power", "Topete")
    val BODY_N = listOf("Magro", "Médio", "Forte")
    val TOP_N = listOf("Camiseta", "Manga longa", "Regata", "Moletom", "Vestido")
    val BOT_N = listOf("Calça", "Bermuda", "Saia", "Short")
    val GLASS_N = listOf("Sem óculos", "Redondo", "Quadrado", "Escuro")
    val BEARD_N = listOf("Sem barba", "Bigode", "Cavanhaque", "Barba cheia")

    /** Cabelos do personagem modular (Etapa 3): ids do manifesto cute/pieces.txt. O índice 0 é "Sem". */
    val HB_IDS = listOf("", "Flare", "Buzzed", "Short", "ShortBob", "LongStreight", "LongDreads", "DreadBack", "PonyTailShort",
        "PonyTailLong", "TwinTailShort", "TwinTailLong", "Wild", "WildTail", "Punk", "Scrappy")
    val HB_N = listOf("Sem", "Ondulado (Mary)", "Raspado", "Curto", "Chanel", "Liso longo", "Dreads longos", "Dreads atrás", "Rabo curto",
        "Rabo longo", "Maria-chiquinha curta", "Maria-chiquinha longa", "Selvagem", "Rabo selvagem", "Punk", "Bagunçado")
    val HF_IDS = listOf("", "LongSweeps", "BangSideFlicks", "Hime", "Messy", "WolfCut", "SweaptBack", "SweaptDreads", "HangingDreads",
        "QuicklyHair", "Fohawk")
    /** Roupas do personagem modular (Etapa 4): ids do manifesto cute/pieces.txt. O índice 0 é "Sem". */
    val CT_IDS = listOf("", "Sweater", "Shirt", "TankTop", "Henley", "Hoodie", "TurtleNeck", "DressShirt", "ButtonUp", "ButtonUpShort",
        "CasualJacket", "OpenJacket", "SuitJacket", "Seifuku", "SeifukuShort", "OpenShoulder", "OpenShoulderCrop", "OpenShoulderStraps",
        "OpenShoulderStrapless", "Suspenders")
    val CT_N = listOf("Sem", "Suéter (Mary)", "Camiseta", "Regata", "Henley", "Moletom", "Gola alta", "Camisa social", "Camisa de botões",
        "Camisa de botões curta", "Jaqueta casual", "Jaqueta aberta", "Blazer", "Uniforme marinheiro", "Uniforme marinheiro curto",
        "Ombro de fora", "Ombro de fora curta", "Ombro de fora com alças", "Tomara que caia", "Suspensório")
    val CB_IDS = listOf("", "Skirt", "JeanSkirt", "PleatedSkirt", "ShortShorts", "Shorts", "BigShorts", "CargoPantsShort", "BaggyPantsShort",
        "Jeans", "LooseJeans", "DressPants", "YogaPants", "StripedPants", "CargoPants", "BaggyPantsLong", "BaggyPantsAnkles")
    val CB_N = listOf("Sem", "Saia (Mary)", "Saia jeans", "Saia plissada", "Short curto", "Bermuda", "Bermudão", "Bermuda cargo",
        "Calça baggy curta", "Jeans", "Jeans largo", "Calça social", "Legging", "Calça listrada", "Calça cargo", "Calça baggy longa",
        "Calça baggy tornozelo")
    val CO_IDS = listOf("", "Sundress", "SmartDress", "Overall")
    val CO_N = listOf("Nenhum", "Vestido de verão", "Vestido elegante", "Macacão")

    val HF_N = listOf("Sem", "Franja longa (Mary)", "Franja de lado", "Hime", "Desalinhada", "Wolf cut", "Para trás", "Dreads para trás",
        "Dreads caídos", "Rápido", "Moicano")

}

/** Dados do personagem (salvos como texto simples). */
class Avatar(
    var female: Boolean = false,
    var skin: Int = 1,
    var body: Int = 1,
    var hair: Int = 1,
    var hairC: Int = 1,
    var eye: Int = 0,
    var top: Int = 0,
    var topC: Int = 6,
    var bottom: Int = 0,
    var botC: Int = 0,
    var shoeC: Int = 0,
    var glasses: Int = 0,
    var beard: Int = 0,
    /** Cores do personagem modular (Etapa 2): índice na paleta de cada slot de [CuteColor]; -1 = cor original da Mary. */
    var cc: IntArray = CuteColor.defaults(),
    /** Etapa 3: índice em [AvaPal.HB_IDS] (cabelo de trás) e [AvaPal.HF_IDS] (franja). Padrão = cabelo da Mary. */
    var hb: Int = 1,
    var hf: Int = 1,
    /** Etapa 4: índice em [AvaPal.CT_IDS] (blusa), [AvaPal.CB_IDS] (calça/saia) e [AvaPal.CO_IDS] (vestido; se != 0 vale no lugar de blusa e calça). */
    var ctop: Int = 1,
    var cbot: Int = 1,
    var cov: Int = 0
) {
    fun encode(): String = listOf(
        if (female) 1 else 0, skin, body, hair, hairC, eye, top, topC, bottom, botC, shoeC, glasses, beard
    ).plus(cc.take(9)).plus(listOf(hb, hf)).plus(cc[9]).plus(listOf(ctop, cbot, cov)).joinToString(",")
    // formato: 13 números antigos | 9 cores (Etapa 2) | cabelo trás, franja (Etapa 3) | cor 10 | blusa, baixo, vestido (Etapa 4)

    /** Peças do personagem modular que devem aparecer (chaves "slot/id" do manifesto). Sem blusa/calça: roupa de baixo. */
    fun wearKeys(): List<String> {
        val k = ArrayList<String>()
        AvaPal.HB_IDS[hb].let { if (it.isNotEmpty()) k.add("hairBack/$it") }
        AvaPal.HF_IDS[hf].let { if (it.isNotEmpty()) k.add("hairFront/$it") }
        if (cov > 0) {
            k.add("overall/" + AvaPal.CO_IDS[cov])
        } else {
            k.add("top/" + AvaPal.CT_IDS[ctop].ifEmpty { "Underwear" })
            k.add("bottom/" + AvaPal.CB_IDS[cbot].ifEmpty { "Underwear" })
        }
        return k
    }

    /** Troca de gênero com um visual-base bonito, mantendo pele, olhos e cor do cabelo. */
    fun applyGender(f: Boolean) {
        female = f
        if (f) {
            hair = 3; beard = 0; top = 0; topC = 8; bottom = 0; botC = 0; shoeC = 0
        } else {
            hair = 1; top = 0; topC = 6; bottom = 0; botC = 1; shoeC = 0
        }
    }


    companion object {
        fun decode(s: String?): Avatar? {
            if (s.isNullOrBlank()) return null
            return try {
                val p = s.split(",").map { it.trim().toInt() }
                if (p.size < 13) null
                else Avatar(
                    p[0] == 1,
                    p[1].coerceIn(0, AvaPal.SKIN.size - 1),
                    p[2].coerceIn(0, 2),
                    p[3].coerceIn(0, AvaPal.HAIR_N.size - 1),
                    p[4].coerceIn(0, AvaPal.HAIR.size - 1),
                    p[5].coerceIn(0, AvaPal.EYE.size - 1),
                    p[6].coerceIn(0, AvaPal.TOP_N.size - 1),
                    p[7].coerceIn(0, AvaPal.TOP.size - 1),
                    p[8].coerceIn(0, AvaPal.BOT_N.size - 1),
                    p[9].coerceIn(0, AvaPal.BOT.size - 1),
                    p[10].coerceIn(0, AvaPal.SHOE.size - 1),
                    p[11].coerceIn(0, AvaPal.GLASS_N.size - 1),
                    p[12].coerceIn(0, AvaPal.BEARD_N.size - 1)
                ).also { av ->
                    // saves antigos (13 números) ficam com as cores originais; os novos trazem 9 números a mais
                    for (k in 0 until 9) {
                        if (13 + k < p.size) av.cc[k] = p[13 + k].coerceIn(-1, CuteColor.palette(k).size - 1)
                    }
                    // saves da Etapa 2 não têm cabelos: ficam com o da Mary
                    if (23 < p.size) {
                        av.hb = p[22].coerceIn(0, AvaPal.HB_IDS.size - 1)
                        av.hf = p[23].coerceIn(0, AvaPal.HF_IDS.size - 1)
                    }
                    // saves da Etapa 3 não têm roupas novas: ficam com a blusa e a saia da Mary
                    if (27 < p.size) {
                        av.cc[9] = p[24].coerceIn(-1, CuteColor.palette(9).size - 1)
                        av.ctop = p[25].coerceIn(0, AvaPal.CT_IDS.size - 1)
                        av.cbot = p[26].coerceIn(0, AvaPal.CB_IDS.size - 1)
                        av.cov = p[27].coerceIn(0, AvaPal.CO_IDS.size - 1)
                    }
                }
            } catch (e: Exception) {
                null
            }
        }

        fun random(female: Boolean): Avatar {
            val r = Random
            val hairs = if (female) listOf(2, 3, 3, 4, 5, 7, 8) else listOf(0, 1, 1, 6, 7, 8, 2)
            val beards = if (female) listOf(0) else listOf(0, 0, 0, 1, 2, 3)
            return Avatar(
                female,

                r.nextInt(AvaPal.SKIN.size),
                r.nextInt(3),
                hairs.random(),
                if (r.nextInt(5) == 0) r.nextInt(AvaPal.HAIR.size) else r.nextInt(7),
                r.nextInt(AvaPal.EYE.size),
                if (female) listOf(0, 1, 2, 4, 4).random() else listOf(0, 0, 1, 2, 3).random(),
                r.nextInt(AvaPal.TOP.size),
                if (female) listOf(0, 1, 2, 3).random() else listOf(0, 0, 1, 3).random(),
                r.nextInt(AvaPal.BOT.size),
                r.nextInt(AvaPal.SHOE.size),
                listOf(0, 0, 0, 1, 2, 3).random(),
                beards.random()
            )
        }
    }
}

/** Modelo 3D do personagem (origem nos pés, rosto virado para +z) e o palco do criador. Estilo fofinho: cabeça grande, formas lisas e olhos brilhantes. */
object AvatarModel {
    private fun pick(arr: IntArray, i: Int): Int = arr[i.coerceIn(0, arr.size - 1)]

    private const val HR = 0.42   // raio horizontal da cabeça
    private const val HY = 0.385  // raio vertical da cabeça
    private const val HEAD_Y = 1.08

    /** Profundidade (z) da superfície da cabeça no ponto (x, dy) relativo ao centro. */
    private fun hz(x: Double, dy: Double): Double {
        val v = 1.0 - (x / HR) * (x / HR) - (dy / HY) * (dy / HY)
        return HR * sqrt(if (v > 0.0) v else 0.0)
    }

    fun stage(): Mesh {
        val b = MB()
        b.m(Mt.PLAIN)
        b.cyl(0.0, 0.0, 0.0, 1.36, 1.36, 0.05, C("#FF7A59"), true, 64)
        b.cyl(0.0, 0.0, 0.0, 1.3, 1.3, 0.082, C("#2C3450"), true, 64)
        b.cyl(0.0, 0.0, 0.0, 1.0, 1.0, 0.0835, C("#343D5C"), true, 64)
        b.blob(0.0, 0.0855, 0.0, 0.62, 0.5, 0.45f)
        return b.toMesh()
    }

    /** Perfil suave em forma de ovo para o tronco: pares raio e y. */
    private fun egg(h: Double, rBot: Double, rMid: Double, rTop: Double): FloatArray {
        val n = 12
        val out = FloatArray(n * 2)
        for (i in 0 until n) {
            val t = i.toDouble() / (n - 1)
            val base = if (t < 0.45) rBot + (rMid - rBot) * (t / 0.45) else rMid + (rTop - rMid) * ((t - 0.45) / 0.55)
            val round = 0.62 + 0.38 * sqrt(sin(PI * (0.04 + 0.92 * t)))
            out[i * 2] = (base * round).toFloat()
            out[i * 2 + 1] = (h * t).toFloat()
        }
        return out
    }

    /** Personagem esculpido (superfície contínua); se algo falhar, cai no modelo simples antigo. */
    fun build(a: Avatar): Mesh = try {
        SculptAvatar.build(a)
    } catch (e: Throwable) {
        buildCute(a)
    }

    /** Modelo simples de elipsoides (reserva). */
    fun buildCute(a: Avatar): Mesh {
        val b = MB()
        b.m(Mt.PLAIN)
        val f = a.female
        val skin = pick(AvaPal.SKIN, a.skin)
        val hc = pick(AvaPal.HAIR, a.hairC)
        val bc = if (a.hairC >= 7) C("#3A2A20") else hc
        val eyeC = pick(AvaPal.EYE, a.eye)
        val tc = pick(AvaPal.TOP, a.topC)
        val bot = pick(AvaPal.BOT, a.botC)
        val shoe = pick(AvaPal.SHOE, a.shoeC)
        val bi = a.body.coerceIn(0, 2)
        val sides = doubleArrayOf(-1.0, 1.0)
        val k = doubleArrayOf(0.9, 1.0, 1.13)[bi]
        val hy = HEAD_Y
        val white = C("#FFFFFF")
        val ink = C("#2A1F2E")
        val dress = a.top == 4
        val skinD = shade(skin, -20)
        val hcL = shade(hc, 22)
        val hcD = shade(hc, -22)

        // ---------- pernas e sapatos
        val lx = 0.105
        for (sx in sides) {
            val x = sx * lx * (0.95 + 0.05 * k)
            b.limb(x, 0.32, 0.0, x, 0.11, 0.0, 0.072, 0.068, skin, 18)
            b.ell(x, 0.062, 0.05, 0.108, 0.068, 0.155, shoe, 20)
            b.ell(x, 0.022, 0.05, 0.112, 0.024, 0.16, C("#F4F1EC"), 18)
            b.ell(x, 0.105, 0.135, 0.07, 0.04, 0.05, shade(shoe, 14), 12)
        }

        // ---------- roupa de baixo
        val botC = if (dress) tc else bot
        b.ell(0.0, 0.345, 0.0, 0.205 * k + (if (f) 0.012 else 0.0), 0.125, 0.17 * k, botC, 22)
        if (!dress) {
            when (a.bottom) {
                0 -> for (sx in sides) { val x = sx * lx * (0.95 + 0.05 * k); b.limb(x, 0.35, 0.0, x, 0.12, 0.0, 0.092, 0.088, bot, 18) }
                1 -> for (sx in sides) { val x = sx * lx * (0.95 + 0.05 * k); b.limb(x, 0.35, 0.0, x, 0.20, 0.0, 0.094, 0.092, bot, 18) }
                2 -> b.lathe(0.0, 0.21, 0.0, floatArrayOf(0.33f, 0f, 0.31f, 0.05f, 0.27f, 0.12f, 0.22f, 0.19f, 0.19f, 0.24f), bot, true, 40)
                else -> for (sx in sides) { val x = sx * lx * (0.95 + 0.05 * k); b.limb(x, 0.35, 0.0, x, 0.27, 0.0, 0.097, 0.095, bot, 18) }
            }
        } else {
            b.lathe(0.0, 0.17, 0.0, floatArrayOf(0.36f, 0f, 0.345f, 0.04f, 0.30f, 0.12f, 0.24f, 0.21f, 0.19f, 0.30f), tc, true, 44)
            b.ell(0.0, 0.17, 0.0, 0.36, 0.022, 0.36, shade(tc, 26), 16)
        }

        // ---------- tronco
        val hood = a.top == 3
        val ks = if (hood) 1.08 else 1.0
        val rb = (if (f) 0.165 else 0.185) * k * ks
        val rm = (if (f) 0.155 else 0.19) * k * ks
        val rt = (if (f) 0.13 else 0.16) * k * ks
        b.lathe(0.0, 0.30, 0.0, egg(0.50, rb, rm, rt), tc, true, 40)
        if (f) for (sx in sides) b.ell(sx * 0.07, 0.62, 0.13, 0.062, 0.058, 0.055, tc, 14)
        b.cyl(0.0, 0.76, 0.0, 0.088, 0.078, 0.04, shade(tc, -26), true, 28)
        if (hood) {
            b.ell(0.0, 0.82, -0.10, 0.17, 0.11, 0.11, shade(tc, -10), 18)
            b.ell(0.0, 0.47, 0.185 * k, 0.12, 0.06, 0.03, shade(tc, -16), 16)
            for (sx in sides) b.limb(sx * 0.04, 0.74, 0.16, sx * 0.045, 0.60, 0.175, 0.012, 0.012, white, 8)
        }
        if (dress) {
            val bw = shade(tc, -34)
            b.ell(0.0, 0.62, 0.16, 0.035, 0.035, 0.03, bw, 12)
            for (sx in sides) b.ell(sx * 0.07, 0.62, 0.16, 0.06, 0.04, 0.03, bw, 12, true, sx * 0.4)
        }

        // ---------- braços
        val sleeveLong = a.top == 1 || a.top == 3
        val sleeveShort = a.top == 0 || a.top == 4
        for (sx in sides) {
            val sxp = sx * (0.175 * k + 0.035); val syp = 0.70
            val hxp = sx * (0.225 * k + 0.085); val hyp = 0.41; val hzp = 0.035
            fun at(t: Double, v0: Double, v1: Double) = v0 + (v1 - v0) * t
            b.limb(sxp, syp, 0.0, hxp, hyp, hzp, 0.052, 0.046, skin, 16)
            b.ell(hxp, hyp - 0.005, hzp, 0.058, 0.058, 0.055, skin, 16)
            if (sleeveLong) {
                val t = 0.88
                b.limb(sxp, syp, 0.0, at(t, sxp, hxp), at(t, syp, hyp), at(t, 0.0, hzp), if (hood) 0.068 else 0.06, 0.056, tc, 16)
                b.ell(at(t, sxp, hxp), at(t, syp, hyp), at(t, 0.0, hzp), 0.062, 0.03, 0.062, shade(tc, -20), 12, true, -sx * 0.25)
            } else if (sleeveShort) {
                val t = 0.36
                b.limb(sxp, syp, 0.0, at(t, sxp, hxp), at(t, syp, hyp), at(t, 0.0, hzp), 0.066, 0.062, tc, 16)
            }
            b.ell(sxp, syp, 0.0, 0.07, 0.07, 0.07, if (a.top == 2) skin else tc, 16)
        }

        // ---------- pescoço e cabeça
        b.limb(0.0, 0.74, 0.0, 0.0, 0.88, 0.0, 0.065, 0.06, skin, 16)
        b.ell(0.0, hy, 0.0, HR, HY, HR, skin, 34)
        // orelhas
        for (sx in sides) {
            b.ell(sx * 0.405, hy - 0.03, -0.01, 0.05, 0.07, 0.05, skin, 14)
            b.ell(sx * 0.425, hy - 0.03, 0.0, 0.025, 0.043, 0.03, skinD, 12)
        }
        // bochechas e nariz
        val blush = mix(skin, C("#FF5E7A"), 0.45f)
        for (sx in sides) {
            val bx = sx * 0.235
            b.ell(bx, hy - 0.115, hz(bx, -0.115) - 0.018, 0.065, 0.036, 0.03, blush, 14, true, -sx * 0.18)
        }
        b.ell(0.0, hy - 0.085, hz(0.0, -0.085) + 0.004, 0.024, 0.018, 0.022, shade(skin, -10), 12)

        // ---------- olhos grandes e brilhantes
        val eyeDy = -0.025
        val eyeX = 0.152
        for (sx in sides) {
            val ex = sx * eyeX
            val zs = hz(ex, eyeDy)
            val ey = hy + eyeDy
            b.ell(ex, ey, zs - 0.045, 0.088, 0.112, 0.05, ink, 22)
            b.ell(ex, ey - 0.004, zs - 0.036, 0.074, 0.098, 0.05, eyeC, 22)
            b.ell(ex, ey - 0.03, zs - 0.032, 0.058, 0.055, 0.05, mix(eyeC, white, 0.38f), 18)
            b.ell(ex, ey + 0.004, zs - 0.03, 0.044, 0.062, 0.05, shade(eyeC, -70), 18)
            b.ell(ex, ey + 0.006, zs - 0.026, 0.03, 0.044, 0.05, C("#0E0A12"), 16)
            b.ell(ex + 0.028, ey + 0.04, zs - 0.012, 0.027, 0.032, 0.05, white, 14, false)
            b.ell(ex - 0.026, ey - 0.04, zs - 0.012, 0.013, 0.015, 0.05, white, 10, false)
            // pálpebra superior
            b.ell(ex, ey + 0.1, zs - 0.027, 0.098, 0.022, 0.045, ink, 14, true, -sx * 0.1)
            if (f) {
                b.ell(ex + sx * 0.085, ey + 0.095, zs - 0.045, 0.038, 0.013, 0.03, ink, 10, true, sx * 0.55)
                b.ell(ex + sx * 0.09, ey + 0.065, zs - 0.05, 0.03, 0.011, 0.03, ink, 10, true, sx * 0.9)
            }
        }
        // sobrancelhas
        val browC = shade(bc, -30)
        for (sx in sides) {
            val bx = sx * 0.155
            b.ell(bx, hy + 0.165, hz(bx, 0.165) - 0.004, 0.07, 0.014, 0.022, browC, 12, true, sx * 0.12)
        }
        // boca (sorriso pequenino)
        val lip = if (f) C("#D9576C") else C("#B8505A")
        for (i in -4..4) {
            val x = i * 0.0165
            val dy = -0.172 - 0.016 * (1.0 - (i / 4.0) * (i / 4.0))
            b.ell(x, hy + dy, hz(x, dy) - 0.003, 0.0105, 0.0105, 0.012, lip, 8)
        }
        for (sx in sides) b.ell(sx * 0.082, hy - 0.162, hz(sx * 0.082, -0.162) - 0.003, 0.008, 0.008, 0.01, shade(lip, -25), 8)

        // ---------- barba
        if (a.beard > 0) {
            for (sx in sides) b.ell(sx * 0.022, hy - 0.125, hz(sx * 0.022, -0.125) + 0.012, 0.045, 0.018, 0.022, bc, 12, true, sx * 0.3)
            if (a.beard == 2) {
                b.ell(0.0, hy - 0.285, hz(0.0, -0.285) + 0.0, 0.065, 0.075, 0.055, bc, 14)
            } else if (a.beard == 3) {
                b.ell(0.0, hy - 0.30, 0.02, 0.43, 0.17, 0.43, bc, 30)
                for (sx in sides) b.limb(sx * 0.37, hy - 0.02, 0.07, sx * 0.33, hy - 0.2, 0.12, 0.05, 0.05, bc, 12)
            }
        }

        // ---------- cabelo
        fun shell(dy: Double, ry: Double, rr: Double, zc: Double) = b.ell(0.0, hy + dy, zc, rr, ry, rr, hc, 30)
        fun bangs(n: Int, lift: Double) {
            for (j in 0 until n) {
                val u = if (n == 1) 0.0 else (j - (n - 1) / 2.0) / ((n - 1) / 2.0)
                val x = u * 0.27
                val dyb = 0.215 - 0.05 * u * u + lift
                val sw = 0.085 + 0.012 * (1 - abs(u))
                b.ell(x, hy + dyb, hz(x, dyb) + 0.012, sw, 0.07, 0.06, if (j % 2 == 0) hc else hcL, 14, true, -u * 0.25)
            }
        }
        fun temples() {
            for (sx in sides) b.ell(sx * 0.335, hy + 0.06, hz(sx * 0.335, 0.06) - 0.005, 0.07, 0.115, 0.06, hc, 14, true, sx * 0.1)
        }
        fun shine(r: Double) {
            b.ell(0.0, hy + r, 0.12, 0.2, 0.012, 0.09, hcL, 10, true, 0.0, -0.5)
        }
        when (a.hair) {
            1 -> { shell(0.12, 0.38, 0.445, -0.06); bangs(5, 0.0); temples() }
            2 -> {
                shell(0.05, 0.45, 0.47, -0.08); bangs(5, 0.0); temples()
                for (sx in sides) b.limb(sx * 0.37, hy + 0.02, 0.04, sx * 0.39, hy - 0.2, 0.0, 0.075, 0.07, hc, 14)
                b.ell(0.0, hy - 0.15, -0.2, 0.36, 0.25, 0.2, hcD, 22)
            }
            3 -> {
                shell(0.05, 0.45, 0.47, -0.1); bangs(5, 0.0); temples()
                b.ell(0.0, 0.68, -0.2, 0.38, 0.46, 0.17, hcD, 26)
                for (sx in sides) {
                    b.limb(sx * 0.37, hy + 0.0, 0.05, sx * 0.36, 0.6, 0.1, 0.08, 0.07, hc, 14)
                    b.ell(sx * 0.36, 0.56, 0.1, 0.07, 0.06, 0.07, hc, 12)
                }
            }
            4 -> {
                shell(0.12, 0.38, 0.445, -0.06); bangs(5, 0.0); temples()
                b.limb(0.0, hy + 0.2, -0.42, 0.0, hy - 0.3, -0.5, 0.1, 0.05, hc, 16)
                b.ell(0.0, hy + 0.2, -0.4, 0.06, 0.055, 0.06, C("#E5484D"), 14)
            }
            5 -> {
                shell(0.12, 0.38, 0.445, -0.06); bangs(5, 0.0); temples()
                b.ell(0.0, hy + 0.5, -0.05, 0.15, 0.13, 0.15, hc, 22)
                b.ell(0.0, hy + 0.38, -0.05, 0.115, 0.032, 0.115, C("#E5484D"), 14)
            }
            6 -> {
                b.ell(0.0, hy + 0.01, 0.0, HR + 0.006, HY + 0.006, HR + 0.006, mix(skin, hc, 0.30f), 30)
                for (i in 0..6) {
                    val t = i / 6.0
                    b.ell(0.0, hy + 0.37 + 0.06 * sin(PI * t), 0.3 - 0.62 * t, 0.045, 0.13, 0.09, if (i % 2 == 0) hc else hcL, 14)
                }
            }
            7 -> {
                b.ell(0.0, hy + 0.14, -0.17, 0.56, 0.5, 0.52, hc, 30)
                for (i in 0 until 8) {
                    val an = PI * (0.1 + 0.8 * i / 7.0)
                    b.ell(0.5 * cos(an), hy + 0.12 + 0.46 * sin(an), -0.14, 0.15, 0.14, 0.15, if (i % 2 == 0) hcD else hc, 14)
                }
                b.ell(0.0, hy + 0.62, -0.14, 0.17, 0.12, 0.17, hc, 14)
            }
            8 -> {
                shell(0.12, 0.38, 0.445, -0.06); bangs(3, 0.03); temples()
                b.ell(0.0, hy + 0.4, 0.1, 0.2, 0.11, 0.23, hc, 18, true, 0.0, -0.4)
                b.ell(0.1, hy + 0.43, 0.14, 0.12, 0.07, 0.15, hcL, 14, true, -0.3, -0.4)
            }
            else -> {}
        }
        if (a.hair in listOf(1, 2, 3, 4, 5, 8)) shine(0.33)

        // ---------- óculos (por último, por causa da transparência)
        if (a.glasses > 0) {
            val fr = if (a.glasses == 3) C("#0E1117") else C("#242A35")
            val zOff = 0.05
            for (sx in sides) {
                val ex = sx * eyeX
                val ec = hy + eyeDy
                val rr = if (a.glasses == 2) 0.125 else 0.115
                val steps = 22
                for (i in 0 until steps) {
                    val an = 2.0 * PI * i / steps
                    var cx0 = cos(an); var sy0 = sin(an)
                    if (a.glasses == 2) {
                        cx0 = Math.signum(cx0) * Math.pow(abs(cx0), 0.5)
                        sy0 = Math.signum(sy0) * Math.pow(abs(sy0), 0.5)
                    }
                    val px = ex + rr * cx0
                    val py = rr * 0.92 * sy0 + eyeDy
                    b.ell(px, hy + py, hz(px, py) + zOff, 0.0125, 0.0125, 0.012, fr, 8)
                }
                b.al = if (a.glasses == 3) 0.86f else 0.22f
                b.ell(ex, ec, hz(ex, eyeDy) + zOff - 0.004, rr, rr * 0.92, 0.006, if (a.glasses == 3) C("#0A0C10") else C("#BFE3FF"), 14)
                b.al = 1f
                b.limb(sx * (eyeX + rr), ec + 0.02, hz(sx * (eyeX + rr), 0.0) + zOff - 0.01, sx * 0.4, ec + 0.02, 0.06, 0.009, 0.009, fr, 8)
            }
            b.ell(0.0, hy + eyeDy + 0.025, hz(0.0, 0.0) + zOff + 0.005, 0.045, 0.011, 0.011, fr, 8)
        }
        return b.toMesh()
    }
}
