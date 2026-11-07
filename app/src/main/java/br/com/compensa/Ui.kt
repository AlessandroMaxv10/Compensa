package br.com.compensa

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Pequenos ajudantes para montar as telas sem XML (app mais leve). */
object Ui {
    val GREEN = Color.rgb(18, 161, 80)
    val DARK = Color.rgb(27, 36, 32)
    val MUTED = Color.rgb(102, 115, 108)
    val BG = Color.rgb(243, 246, 244)
    val RED_TX = Color.rgb(217, 48, 37)

    fun Activity.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    fun Activity.column(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
    }

    fun Activity.card(parent: LinearLayout, titleText: String? = null): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(Color.WHITE) }
            elevation = dp(1).toFloat()
        }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, 0, 0, dp(12))
        parent.addView(c, lp)
        if (titleText != null) c.addView(text(titleText, 17f, true))
        return c
    }

    fun Activity.text(s: String, size: Float = 15f, bold: Boolean = false, color: Int = DARK): TextView =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(3), 0, dp(3))
        }

    fun Activity.field(parent: LinearLayout, label: String, value: String, numeric: Boolean = true, password: Boolean = false): EditText {
        parent.addView(text(label, 13f, color = MUTED))
        val e = EditText(this).apply {
            setText(value)
            textSize = 16f
            inputType = when {
                password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                numeric -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                else -> InputType.TYPE_CLASS_TEXT
            }
        }
        parent.addView(e)
        return e
    }

    fun Activity.button(parent: LinearLayout, label: String, primary: Boolean = true, click: () -> Unit): Button {
        val b = Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 16f
            setTextColor(if (primary) Color.WHITE else DARK)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(if (primary) GREEN else Color.rgb(228, 235, 231))
            }
            setOnClickListener { click() }
        }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50))
        lp.setMargins(0, dp(8), 0, 0)
        parent.addView(b, lp)
        return b
    }

    fun Activity.header(parent: LinearLayout, title: String, subtitle: String): TextView {
        val h = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(22), dp(16), dp(18))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR, intArrayOf(GREEN, Color.rgb(11, 138, 67))
            ).apply { cornerRadius = dp(20).toFloat() }
        }
        h.addView(text("🚗💲", 34f).apply { gravity = Gravity.CENTER })
        h.addView(text(title, 26f, true, Color.WHITE).apply { gravity = Gravity.CENTER })
        val sub = text(subtitle, 14f, false, Color.WHITE).apply { gravity = Gravity.CENTER }
        h.addView(sub)
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, 0, 0, dp(12))
        parent.addView(h, lp)
        return sub
    }

    fun View.gone(hide: Boolean) { visibility = if (hide) View.GONE else View.VISIBLE }
}
