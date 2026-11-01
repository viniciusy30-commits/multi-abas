package com.vinicius.meular

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Tela do personagem: mostra a Mary girando e deixa escolher as cores (Etapa 2):
 * uma fileira de partes (Pele, Olhos, Cabelo, Pontas, Blusa, Detalhe, Saia, Sandália, Tiras)
 * e, embaixo, as cores daquela parte (a primeira bolinha, "Original", volta à cor da Mary). Botão "Pronto" salva.
 * Etapas 3 e 4: as cinco primeiras partes ("Cabelo atrás", "Franja", "Blusa", "Calça/saia", "Vestido") escolhem o estilo
 * (15 + 10 cabelos, 19 blusas, 16 calças/saias, 3 vestidos); as cores valem para qualquer estilo. Vestido vale no lugar de blusa e calça.
 */
class CreatorUi(
    ctx: Context,
    private val st: GameState,
    private val game: GameView,
    private val onDone: () -> Unit
) : FrameLayout(ctx) {

    private val d = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private fun dpf(v: Float) = v * d

    private val panel = LinearLayout(ctx)
    private val chipRow = LinearLayout(ctx)
    private val swatchRow = LinearLayout(ctx)
    private val chips = ArrayList<TextView>()
    private var slot = 0   // 0..4 = estilos (cabelo atrás, franja, blusa, calça/saia, vestido); 5.. = cor (slot - 5 de CuteColor)
    private val STYLES = 5
    private val NAMES = listOf("Cabelo atrás", "Franja", "Blusa", "Calça/saia", "Vestido") + CuteColor.SLOT_NAMES

    init {
        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(dp(14), dp(12), dp(14), dp(12))
        panel.background = Ui.topSheet(Pal.GLASS, dpf(22f))

        // partes
        chipRow.orientation = LinearLayout.HORIZONTAL
        for (i in NAMES.indices) {
            val c = TextView(ctx)
            c.text = NAMES[i]
            c.textSize = 14f
            c.typeface = Ui.BOLD
            c.setTextColor(Color.WHITE)
            c.gravity = Gravity.CENTER
            c.setPadding(dp(14), 0, dp(14), 0)
            c.setOnClickListener { slot = i; refresh() }
            val lp = LinearLayout.LayoutParams(-2, dp(36))
            lp.rightMargin = dp(8)
            chipRow.addView(c, lp)
            chips.add(c)
        }
        val chipScroll = HorizontalScrollView(ctx)
        chipScroll.isHorizontalScrollBarEnabled = false
        chipScroll.addView(chipRow)
        panel.addView(chipScroll, LinearLayout.LayoutParams(-1, -2))

        // cores da parte escolhida
        swatchRow.orientation = LinearLayout.HORIZONTAL
        swatchRow.gravity = Gravity.CENTER_VERTICAL
        val swScroll = HorizontalScrollView(ctx)
        swScroll.isHorizontalScrollBarEnabled = false
        swScroll.addView(swatchRow)
        val swLp = LinearLayout.LayoutParams(-1, dp(52))
        swLp.topMargin = dp(10)
        panel.addView(swScroll, swLp)

        val ok = TextView(ctx)
        ok.text = "Pronto"
        ok.textSize = 18f
        ok.typeface = Ui.BOLD
        ok.setTextColor(Color.WHITE)
        ok.gravity = Gravity.CENTER
        ok.background = Ui.bg(Pal.GREEN, dpf(22f))
        ok.setOnClickListener { st.avatarSet = true; st.save(); onDone() }
        val okLp = LinearLayout.LayoutParams(-1, dp(50))
        okLp.topMargin = dp(10)
        panel.addView(ok, okLp)
        addView(panel, LayoutParams(-1, -2, Gravity.BOTTOM))
        refresh()
    }

    private fun refresh() {
        for (i in chips.indices) {
            chips[i].background = Ui.bg(if (i == slot) Pal.CORAL else Pal.CARD_HI, dpf(18f))
        }
        swatchRow.removeAllViews()
        if (slot < STYLES) {
            styles()
            return
        }
        val cur = st.avatar.cc[slot - STYLES]

        val orig = TextView(context)
        orig.text = "Original"
        orig.textSize = 13f
        orig.typeface = Ui.BOLD
        orig.setTextColor(Color.WHITE)
        orig.gravity = Gravity.CENTER
        orig.setPadding(dp(12), 0, dp(12), 0)
        orig.background = Ui.bg(Pal.CARD_HI, dpf(18f), if (cur < 0) Color.WHITE else 0, dpf(3f))
        orig.setOnClickListener { pick(-1) }
        val olp = LinearLayout.LayoutParams(-2, dp(40))
        olp.rightMargin = dp(10)
        swatchRow.addView(orig, olp)

        val pal = CuteColor.palette(slot - STYLES)
        for (i in pal.indices) {
            val v = View(context)
            val sel = i == cur
            v.background = Ui.oval(pal[i], if (sel) Color.WHITE else 0x66FFFFFF, if (sel) dpf(3f) else dpf(1f))
            v.setOnClickListener { pick(i) }
            val lp = LinearLayout.LayoutParams(dp(40), dp(40))
            lp.rightMargin = dp(10)
            swatchRow.addView(v, lp)
        }
    }

    /** Fileira de estilos (nomes) para as partes 0..4. */
    private fun styles() {
        val a = st.avatar
        val names = when (slot) { 0 -> AvaPal.HB_N; 1 -> AvaPal.HF_N; 2 -> AvaPal.CT_N; 3 -> AvaPal.CB_N; else -> AvaPal.CO_N }
        val cur = when (slot) { 0 -> a.hb; 1 -> a.hf; 2 -> a.ctop; 3 -> a.cbot; else -> a.cov }
        for (i in names.indices) {
            val t = TextView(context)
            t.text = names[i]
            t.textSize = 13f
            t.typeface = Ui.BOLD
            t.setTextColor(Color.WHITE)
            t.gravity = Gravity.CENTER
            t.setPadding(dp(14), 0, dp(14), 0)
            t.background = Ui.bg(Pal.CARD_HI, dpf(18f), if (i == cur) Color.WHITE else 0, dpf(3f))
            t.setOnClickListener { pickStyle(i) }
            val lp = LinearLayout.LayoutParams(-2, dp(40))
            lp.rightMargin = dp(10)
            swatchRow.addView(t, lp)
        }
    }

    private fun pickStyle(i: Int) {
        val a = st.avatar
        when (slot) { 0 -> a.hb = i; 1 -> a.hf = i; 2 -> a.ctop = i; 3 -> a.cbot = i; else -> a.cov = i }
        CuteChar.setWear(a.wearKeys())
        st.save()
        refresh()
    }

    private fun pick(i: Int) {
        st.avatar.cc[slot - STYLES] = i
        CuteColor.setColors(st.avatar.cc)
        refresh()
    }

    fun setInsets(topPx: Int, bottomPx: Int) {
        panel.setPadding(dp(14), dp(12), dp(14), dp(12) + bottomPx)
    }

    fun open() {
        camera()
        refresh()
    }

    private fun camera() {
        // corpo inteiro (≈1,3 m); o painel de cores cobre a parte de baixo, então o alvo sobe um pouco
        game.r.cTargetDist = 4.2f
        game.r.cTargetY = 0.7f
    }
}
