package com.vinicius.multiabas

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

object Ui {
    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density).toInt()
    fun color(c: Context, id: Int): Int = ContextCompat.getColor(c, id)

    fun shape(
        c: Context, fill: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 1, oval: Boolean = false
    ): GradientDrawable = GradientDrawable().apply {
        shape = if (oval) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
        setColor(fill)
        if (!oval) cornerRadius = dp(c, radiusDp).toFloat()
        if (strokeColor != 0) setStroke(dp(c, strokeDp), strokeColor)
    }

    fun ripple(base: Drawable): Drawable =
        RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), base, null)

    private val palette = intArrayOf(
        0xFFD97757.toInt(), 0xFF5B8DEF.toInt(), 0xFF8E6CEF.toInt(), 0xFF2FB38A.toInt(),
        0xFFE0A33A.toInt(), 0xFFE0627F.toInt(), 0xFF3AA6C9.toInt(), 0xFF7A8BA0.toInt()
    )

    fun avatarColor(name: String): Int = palette[(name.lowercase().hashCode() and 0x7fffffff) % palette.size]

    /** Bolinha colorida com a inicial do nome. */
    fun avatar(tv: TextView, name: String) {
        val ch = name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "•"
        tv.text = ch
        tv.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(avatarColor(name))
        }
    }

    /** Campo de texto com borda (Material), devolve o layout e o EditText. */
    fun field(c: Context, hint: String, value: String = "", password: Boolean = false): Pair<TextInputLayout, TextInputEditText> {
        val til = TextInputLayout(c, null, com.google.android.material.R.attr.textInputOutlinedStyle)
        til.hint = hint
        val et = TextInputEditText(til.context)
        et.setSingleLine()
        if (password) et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        et.setText(value)
        til.addView(et)
        til.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(c, 10) }
        return Pair(til, et)
    }
}

data class Tile(val icon: Int, val label: String, val onClick: () -> Unit)

/** Bottom sheet com cabeçalho, linhas com ícone, grade de atalhos e interruptores. */
class Sheet(private val ctx: Context) {
    private val dialog = BottomSheetDialog(ctx)
    private val root = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(Ui.dp(ctx, 20), Ui.dp(ctx, 10), Ui.dp(ctx, 20), Ui.dp(ctx, 28))
    }

    private val cText = Ui.color(ctx, R.color.text)
    private val cMuted = Ui.color(ctx, R.color.muted)
    private val cAccent = Ui.color(ctx, R.color.accent)
    private val cDanger = Ui.color(ctx, R.color.danger)
    private val cSurface2 = Ui.color(ctx, R.color.surface2)
    private val cOutline = Ui.color(ctx, R.color.outline)
    private val cAccentSoft = Ui.color(ctx, R.color.accent_soft)

    init {
        // alça de arrastar
        val handle = View(ctx).apply {
            background = Ui.shape(ctx, cOutline, 2)
        }
        root.addView(handle, LinearLayout.LayoutParams(Ui.dp(ctx, 36), Ui.dp(ctx, 4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = Ui.dp(ctx, 14)
        })
    }

    fun title(t: String, sub: String? = null): Sheet {
        root.addView(TextView(ctx).apply {
            text = t
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(cText)
        })
        if (!sub.isNullOrBlank()) {
            root.addView(TextView(ctx).apply {
                text = sub
                textSize = 13f
                setTextColor(cMuted)
                setPadding(0, Ui.dp(ctx, 2), 0, 0)
            })
        }
        root.addView(View(ctx), LinearLayout.LayoutParams(-1, Ui.dp(ctx, 14)))
        return this
    }

    fun section(label: String): Sheet {
        root.addView(TextView(ctx).apply {
            text = label.uppercase()
            textSize = 11f
            letterSpacing = 0.08f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(cMuted)
            setPadding(Ui.dp(ctx, 4), Ui.dp(ctx, 14), 0, Ui.dp(ctx, 6))
        })
        return this
    }

    fun note(msg: String): Sheet {
        root.addView(TextView(ctx).apply {
            text = msg
            textSize = 13f
            setTextColor(cMuted)
            setPadding(Ui.dp(ctx, 4), Ui.dp(ctx, 4), Ui.dp(ctx, 4), Ui.dp(ctx, 8))
        })
        return this
    }

    private fun iconBubble(icon: Int, tint: Int, bubble: Int): FrameLayout {
        val fl = FrameLayout(ctx)
        fl.background = Ui.shape(ctx, bubble, 0, oval = true)
        val iv = ImageView(ctx)
        iv.setImageResource(icon)
        iv.setColorFilter(tint)
        fl.addView(iv, FrameLayout.LayoutParams(Ui.dp(ctx, 20), Ui.dp(ctx, 20), Gravity.CENTER))
        return fl
    }

    fun row(
        icon: Int, title: String, sub: String? = null,
        highlighted: Boolean = false, danger: Boolean = false,
        onMore: (() -> Unit)? = null,
        leading: View? = null, subColor: Int? = null,
        onClick: () -> Unit
    ): Sheet {
        val line = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = Ui.dp(ctx, 60)
            setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 8), Ui.dp(ctx, 8), Ui.dp(ctx, 8))
            val base = if (highlighted) Ui.shape(ctx, cAccentSoft, 18, cAccent, 1)
                       else Ui.shape(ctx, Color.TRANSPARENT, 18)
            background = Ui.ripple(base)
            isClickable = true
            isFocusable = true
            setOnClickListener { dialog.dismiss(); onClick() }
        }
        val tint = if (danger) cDanger else if (highlighted) cAccent else cText
        val bubble = if (danger) 0x22FF6B6B else if (highlighted) 0x33D97757 else cSurface2
        line.addView(leading ?: iconBubble(icon, tint, bubble), LinearLayout.LayoutParams(Ui.dp(ctx, 40), Ui.dp(ctx, 40)))

        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(ctx).apply {
            text = title
            textSize = 15f
            setTextColor(if (danger) cDanger else cText)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            if (highlighted) setTypeface(typeface, Typeface.BOLD)
        })
        if (!sub.isNullOrBlank()) {
            col.addView(TextView(ctx).apply {
                text = sub
                textSize = 12f
                setTextColor(subColor ?: cMuted)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }
        line.addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = Ui.dp(ctx, 14) })

        if (onMore != null) {
            val more = ImageButton(ctx).apply {
                setImageResource(R.drawable.ic_more)
                setColorFilter(cMuted)
                background = Ui.ripple(Ui.shape(ctx, Color.TRANSPARENT, 0, oval = true))
                setOnClickListener { dialog.dismiss(); onMore() }
                contentDescription = "Mais opções"
            }
            line.addView(more, LinearLayout.LayoutParams(Ui.dp(ctx, 44), Ui.dp(ctx, 44)))
        }
        root.addView(line, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dp(ctx, 4) })
        return this
    }

    fun switchRow(icon: Int, title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit): Sheet {
        val line = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = Ui.dp(ctx, 60)
            setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 8), Ui.dp(ctx, 12), Ui.dp(ctx, 8))
        }
        line.addView(iconBubble(icon, cText, cSurface2), LinearLayout.LayoutParams(Ui.dp(ctx, 40), Ui.dp(ctx, 40)))
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(ctx).apply { text = title; textSize = 15f; setTextColor(cText) })
        if (!sub.isNullOrBlank()) col.addView(TextView(ctx).apply { text = sub; textSize = 12f; setTextColor(cMuted) })
        line.addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = Ui.dp(ctx, 14) })
        val sw = MaterialSwitch(ctx)
        sw.isChecked = checked
        sw.setOnCheckedChangeListener { _, on -> onChange(on) }
        line.addView(sw)
        line.setOnClickListener { sw.toggle() }
        root.addView(line, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dp(ctx, 4) })
        return this
    }

    /** Grade de atalhos, 3 por linha. */
    fun tiles(items: List<Tile>): Sheet {
        items.chunked(3).forEach { rowItems ->
            val line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            for (k in 0 until 3) {
                val t = rowItems.getOrNull(k)
                if (t == null) {
                    line.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
                    continue
                }
                val tile = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(Ui.dp(ctx, 6), Ui.dp(ctx, 14), Ui.dp(ctx, 6), Ui.dp(ctx, 12))
                    background = Ui.ripple(Ui.shape(ctx, cSurface2, 20))
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { dialog.dismiss(); t.onClick() }
                }
                tile.addView(iconBubble(t.icon, cAccent, cAccentSoft), LinearLayout.LayoutParams(Ui.dp(ctx, 44), Ui.dp(ctx, 44)))
                tile.addView(TextView(ctx).apply {
                    text = t.label
                    textSize = 12f
                    gravity = Gravity.CENTER
                    maxLines = 2
                    setTextColor(cText)
                    setPadding(0, Ui.dp(ctx, 8), 0, 0)
                }, LinearLayout.LayoutParams(-1, -2))
                line.addView(tile, LinearLayout.LayoutParams(0, -2, 1f).apply {
                    marginStart = Ui.dp(ctx, 4); marginEnd = Ui.dp(ctx, 4)
                })
            }
            root.addView(line, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dp(ctx, 8) })
        }
        return this
    }

    /** Chamado quando o usuário fecha a folha sem escolher nada (arrastar, voltar, tocar fora). */
    fun onCancel(cb: () -> Unit): Sheet {
        dialog.setOnCancelListener { cb() }
        return this
    }

    fun raw(): android.app.Dialog = dialog

    fun show() {
        val scroll = NestedScrollView(ctx)
        scroll.addView(root, ViewGroup.LayoutParams(-1, -2))
        dialog.setContentView(scroll)
        dialog.setOnShowListener {
            val sheet = dialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
            if (sheet != null) {
                val r = Ui.dp(ctx, 28).toFloat()
                sheet.background = GradientDrawable().apply {
                    setColor(Ui.color(ctx, R.color.surface))
                    cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                }
                val b = BottomSheetBehavior.from(sheet)
                b.skipCollapsed = true
                b.state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
        dialog.show()
    }
}
