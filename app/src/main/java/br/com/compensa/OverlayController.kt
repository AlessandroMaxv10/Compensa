package br.com.compensa

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/** O balão COMPENSA / NÃO COMPENSA que aparece por cima da Uber e da 99. */
class OverlayController(private val ctx: Context) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var root: LinearLayout? = null
    private lateinit var title: TextView
    private lateinit var numbers: TextView
    private lateinit var details: TextView
    private lateinit var status: TextView
    private val hideRunnable = Runnable { hide() }
    private var feedback: (Boolean) -> Unit = {}

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun show(o: Offer, v: Verdict, analyzing: Boolean, onFeedback: (Boolean) -> Unit) {
        feedback = onFeedback
        if (root == null) build()
        render(o, v, analyzing)
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, 25_000)
    }

    fun update(o: Offer, v: Verdict) {
        if (root == null) return
        render(o, v, false)
    }

    private fun render(o: Offer, v: Verdict, analyzing: Boolean) {
        val bg = GradientDrawable().apply {
            cornerRadius = dp(22).toFloat()
            setColor(if (v.good) GREEN else RED)
            setStroke(dp(2), Color.WHITE)
        }
        root?.background = bg
        title.text = if (v.good) "✅ COMPENSA" else "⛔ NÃO COMPENSA"
        numbers.text = money(o.fare) + "  •  " + money(v.perKm) + "/km  •  " +
            money(v.perHour) + "/h\nLucro estimado: " + money(v.profit) +
            "  •  " + num(o.pickupKm + o.tripKm) + " km"
        val lines = ArrayList<String>()
        lines.addAll(v.alerts)
        if (!v.good) lines.addAll(v.reasons.take(2))
        details.text = lines.joinToString("\n")
        details.visibility = if (lines.isEmpty()) View.GONE else View.VISIBLE
        status.text = when {
            analyzing -> "🔄 Analisando mapa e clima…"
            v.aiUsed -> "🧠 IA personalizada ativa  •  " + o.app
            else -> "🧠 IA aprendendo (avalie com 👍/👎)  •  " + o.app
        }
    }

    private fun build() {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(10))
            elevation = dp(8).toFloat()
        }
        title = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        numbers = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
        }
        details = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        }
        status = TextView(ctx).apply {
            setTextColor(Color.argb(220, 255, 255, 255))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        }
        fun chip(label: String, click: () -> Unit) = TextView(ctx).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.argb(70, 0, 0, 0))
            }
            setOnClickListener { click() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(5), 0, dp(5), 0) }
        }
        row.addView(chip("👍 Boa") { feedback(true); status.text = "🧠 Aprendi! Obrigado." })
        row.addView(chip("👎 Ruim") { feedback(false); status.text = "🧠 Aprendi! Obrigado." })
        row.addView(chip("✕") { hide() })

        box.addView(title)
        box.addView(numbers)
        box.addView(details)
        box.addView(status)
        box.addView(row)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            horizontalMargin = 0.03f
            y = dp(36)
        }
        try {
            wm.addView(box, lp)
            root = box
        } catch (e: Exception) {
            root = null
        }
    }

    fun hide() {
        handler.removeCallbacks(hideRunnable)
        root?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        root = null
    }

    companion object {
        val GREEN = Color.rgb(18, 161, 80)
        val RED = Color.rgb(217, 48, 37)
    }
}
