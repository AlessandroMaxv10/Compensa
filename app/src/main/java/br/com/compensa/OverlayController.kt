package br.com.compensa

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Balão COMPENSA que aparece por cima da Uber/99.
 * Estilo compacto e semitransparente: uma barra no topo com ganho por
 * km / hora / min. Ao tocar, abre os detalhes (lucro, motivos, destino, 👍/👎).
 */
class OverlayController(private val ctx: Context) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var root: LinearLayout? = null

    private lateinit var verdictTag: TextView
    private lateinit var perKm: TextView
    private lateinit var perHour: TextView
    private lateinit var perMin: TextView
    private lateinit var hint: TextView
    private lateinit var detailBox: LinearLayout
    private lateinit var detailText: TextView

    private var expanded = false
    private val hideRunnable = Runnable { hide() }
    private var feedback: (Boolean) -> Unit = {}

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun show(o: Offer, v: Verdict, analyzing: Boolean, onFeedback: (Boolean) -> Unit) {
        feedback = onFeedback
        if (root == null) { expanded = false; build() }
        render(o, v, analyzing)
        handler.removeCallbacks(hideRunnable)
        if (!expanded) handler.postDelayed(hideRunnable, 20_000)
    }

    fun update(o: Offer, v: Verdict) {
        if (root == null) return
        render(o, v, false)
    }

    private fun render(o: Offer, v: Verdict, analyzing: Boolean) {
        val good = v.good
        // Fundo bem destacado na cor do veredito (verde = compensa, vermelho = não),
        // porém semitransparente: dá pra ver a tela da Uber atrás.
        root?.background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(if (good) BG_GREEN else BG_RED)
            setStroke(dp(2), Color.argb(230, 255, 255, 255))
        }
        verdictTag.text = if (good) "✅ COMPENSA" else "⛔ NÃO COMPENSA"
        verdictTag.setTextColor(Color.WHITE)

        // Ganhos em branco (a cor do fundo já diz se compensa ou não).
        perKm.text = money(v.perKm) + "/km"
        perHour.text = money(v.perHour) + "/h"
        perMin.text = money(v.perHour / 60.0) + "/min"
        perKm.setTextColor(Color.WHITE)
        perHour.setTextColor(Color.WHITE)
        perMin.setTextColor(Color.WHITE)

        // Detalhes (aparecem ao tocar).
        val lines = ArrayList<String>()
        lines.add(money(o.fare) + "  •  lucro " + money(v.profit) + "  •  " + num(o.pickupKm + o.tripKm) + " km")
        lines.addAll(v.alerts)
        if (!good) lines.addAll(v.reasons.take(3))
        lines.add(
            when {
                analyzing -> "🔄 Analisando mapa e clima…"
                v.aiUsed -> "🧠 IA personalizada  •  " + o.app
                else -> "🧠 IA aprendendo (avalie com 👍/👎)  •  " + o.app
            }
        )
        detailText.text = lines.joinToString("\n")
    }

    private fun build() {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(9), dp(14), dp(9))
            elevation = dp(6).toFloat()
            setOnClickListener { toggle() }
        }

        verdictTag = TextView(ctx).apply {
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

        // Linha dos ganhos: km | hora | min
        val metrics = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, 0)
        }
        fun sep() = TextView(ctx).apply {
            text = "  |  "
            setTextColor(Color.argb(120, 255, 255, 255))
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
        }
        perKm = metric()
        perHour = metric()
        perMin = metric()
        metrics.addView(perKm)
        metrics.addView(sep())
        metrics.addView(perHour)
        metrics.addView(sep())
        metrics.addView(perMin)

        hint = TextView(ctx).apply {
            text = "toque para ver detalhes"
            setTextColor(Color.argb(150, 255, 255, 255))
            textSize = 10f
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, 0)
        }

        // Caixa de detalhes (escondida até tocar)
        detailBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(8), 0, 0)
        }
        detailText = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        fun chip(label: String, click: () -> Unit) = TextView(ctx).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(14), dp(7), dp(14), dp(7))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.argb(90, 255, 255, 255))
            }
            setOnClickListener { click() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(5), 0, dp(5), 0) }
        }
        row.addView(chip("👍 Boa") { feedback(true); hint.text = "🧠 Aprendi! Obrigado." })
        row.addView(chip("👎 Ruim") { feedback(false); hint.text = "🧠 Aprendi! Obrigado." })
        row.addView(chip("✕ Fechar") { hide() })
        detailBox.addView(detailText)
        detailBox.addView(row)

        box.addView(verdictTag)
        box.addView(metrics)
        box.addView(hint)
        box.addView(detailBox)

        // Se o usuário liberou "sobrepor outros apps", usamos a janela que fica
        // por cima de TODOS os apps. Caso contrário, overlay da acessibilidade.
        val canOverlay = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Settings.canDrawOverlays(ctx)
        val type = if (canOverlay) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(44)
        }
        try {
            wm.addView(box, lp)
            root = box
        } catch (e: Exception) {
            root = null
        }
    }

    private fun metric() = TextView(ctx).apply {
        setTextColor(Color.WHITE)
        textSize = 20f
        typeface = Typeface.DEFAULT_BOLD
    }

    private fun toggle() {
        expanded = !expanded
        detailBox.visibility = if (expanded) View.VISIBLE else View.GONE
        hint.text = if (expanded) "toque para recolher" else "toque para ver detalhes"
        handler.removeCallbacks(hideRunnable)
        if (!expanded) handler.postDelayed(hideRunnable, 20_000)
    }

    fun hide() {
        handler.removeCallbacks(hideRunnable)
        expanded = false
        root?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        root = null
    }

    companion object {
        val GREEN = Color.rgb(18, 161, 80)
        val RED = Color.rgb(217, 48, 37)
        // Fundo bem destacado, porém semitransparente (dá pra ver a Uber atrás).
        val BG_GREEN = Color.argb(214, 18, 150, 75)
        val BG_RED = Color.argb(214, 205, 42, 32)
    }
}
