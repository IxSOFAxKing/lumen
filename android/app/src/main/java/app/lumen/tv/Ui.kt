package app.lumen.tv

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Small helpers to build TV-friendly views in code (big text, clear focus highlight for the remote). */
object Ui {
    const val BG = 0xFF0D0F14.toInt()
    const val BG2 = 0xFF1C212D.toInt()
    const val ACCENT = 0xFFFF9F3D.toInt()
    const val TEXT = 0xFFE9ECF3.toInt()
    const val TEXT2 = 0xFFA3ABBD.toInt()

    fun Context.dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    fun text(ctx: Context, s: String, sizeSp: Float = 18f, color: Int = TEXT, bold: Boolean = false): TextView = TextView(ctx).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun rounded(ctx: Context, color: Int, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
        cornerRadius = ctx.dp(10).toFloat()
        setColor(color)
        if (stroke != null) setStroke(ctx.dp(3), stroke)
    }

    fun button(ctx: Context, label: String, primary: Boolean = false, onClick: () -> Unit): Button = Button(ctx).apply {
        text = label
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setTextColor(if (primary) 0xFF1A1206.toInt() else TEXT)
        val normal = if (primary) ACCENT else BG2
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), rounded(ctx, if (primary) 0xFFFFB45F.toInt() else 0xFF2C3446.toInt(), Color.WHITE))
            addState(intArrayOf(android.R.attr.state_pressed), rounded(ctx, ACCENT))
            addState(intArrayOf(), rounded(ctx, normal))
        }
        setPadding(ctx.dp(22), ctx.dp(12), ctx.dp(22), ctx.dp(12))
        isFocusable = true
        isFocusableInTouchMode = true
        setOnClickListener { onClick() }
    }

    fun vbox(ctx: Context, gravity: Int = Gravity.CENTER_HORIZONTAL): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        this.gravity = gravity
    }

    fun hbox(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun gap(ctx: Context, h: Int): View = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(1, ctx.dp(h)) }
}
