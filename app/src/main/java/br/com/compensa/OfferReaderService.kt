package br.com.compensa

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Calendar

/**
 * Coração do Compensa: lê a tela quando a Uber Motorista ou a 99 Motorista
 * mostram uma oferta de corrida e responde na hora (verde/vermelho + voz + vibração).
 * Ele NUNCA toca em aceitar/recusar — só mostra.
 */
class OfferReaderService : AccessibilityService() {

    private lateinit var prefs: Prefs
    private lateinit var brain: Brain
    private lateinit var overlay: OverlayController
    private var tts: TextToSpeech? = null
    private var ttsOk = false
    private val handler = Handler(Looper.getMainLooper())
    private var pending = false
    private var lastSig = ""
    private var lastSigAt = 0L
    private var currentSig = ""
    private var currentFeatures: DoubleArray? = null
    private var currentHistId = 0L
    private var learnedThis = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        brain = Brain(this)
        overlay = OverlayController(this)
        tts = TextToSpeech(this) { st ->
            ttsOk = st == TextToSpeech.SUCCESS
            if (ttsOk) {
                tts?.setLanguage(BR)
                tts?.setSpeechRate(1.05f) // clara, sem atropelar as palavras
                tts?.setPitch(1.0f)
            }
        }
        instance = this
        Api.sync(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (!isRideApp(pkg)) return
        if (!appEnabled(pkg)) return
        if (pending) return
        pending = true
        // espera 60 ms para o card terminar de desenhar e lê tudo de uma vez
        handler.postDelayed({ pending = false; scan() }, 60)
    }

    private fun scan() {
        val text = collectText()
        if (!text.contains("R$")) return
        val offer = OfferParser.parse(text, lastApp) ?: return
        val sig = offer.fare.toString() + "|" + offer.tripKm + "|" + offer.pickupKm
        val now = SystemClock.uptimeMillis()
        if (sig == lastSig && now - lastSigAt < 30_000) return
        lastSig = sig
        lastSigAt = now
        Shift.markActivity(this)
        handleOffer(offer)
    }

    /** Público para o botão "Testar" da tela principal. */
    fun handleOffer(offer: Offer) {
        // só funciona com login válido (conta ativa e dentro da validade)
        if (!Session(this).valid) return
        prefs = Prefs(this) // pega configurações recém-salvas
        brain = Brain(this) // pega a IA mais recente (pessoal + coletiva)
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        val weekend = dow == Calendar.SATURDAY || dow == Calendar.SUNDAY

        val sig = offer.fare.toString() + "|" + offer.tripKm + "|" + offer.destination
        currentSig = sig
        learnedThis = false

        val v = withDriverTips(Scorer.evaluate(offer, prefs, brain, Extras(), hour, weekend))
        currentFeatures = v.features
        val willEnrich = prefs.usarMapa && !offer.destination.isNullOrBlank()

        overlay.show(offer, v, willEnrich) { good ->
            val f = currentFeatures
            if (f != null && !learnedThis && currentSig == sig) {
                learnedThis = true
                brain.learn(f, good)
                History.update(this, currentHistId, feedback = good)
            }
        }
        speak(v)
        currentHistId = History.add(this, offer, v)

        if (willEnrich) {
            val loc = Locator.lastKnown(this)
            Enricher.enrich(offer.destination!!, prefs.cidadeBase, loc?.latitude, loc?.longitude) { ex ->
                if (currentSig != sig) return@enrich
                val v2 = withDriverTips(Scorer.evaluate(offer, prefs, brain, ex, hour, weekend))
                currentFeatures = v2.features
                overlay.update(offer, v2)
                History.update(this, currentHistId, v = v2)
                if (v2.good != v.good) {
                    speak(v2, "Atenção")
                }
            }
        }
    }

    /** Alertas de segurança e de rotina do motorista. */
    private fun withDriverTips(v: Verdict): Verdict {
        val extra = ArrayList<String>()
        val horas = Shift.hoursToday(this)
        if (horas >= 10) extra.add("😴 " + num(horas, 0) + "h na rua hoje: faça uma pausa")
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (!v.good && v.perKm >= prefs.minPorKm && (hour in 6..9 || hour in 17..20))
            extra.add("⏰ Horário de pico: segure por corridas melhores")
        return if (extra.isEmpty()) v else v.copy(alerts = v.alerts + extra)
    }

    /** Fala natural em português, dizendo se compensa e o motivo principal. */
    private fun speak(v: Verdict, prefixo: String? = null) {
        vibrate(v.good)
        if (!prefs.voz || !ttsOk) return
        val fala = StringBuilder()
        if (prefixo != null) fala.append(prefixo).append(". ")
        fala.append(if (v.good) "Corrida compensa" else "Corrida não compensa")
        val motivo = spokenReason(v)
        if (motivo.isNotEmpty()) fala.append(". ").append(motivo)
        fala.append(".")
        tts?.speak(fala.toString(), TextToSpeech.QUEUE_FLUSH, null, "compensa")
    }

    /** Motivo curto e bem falado, escolhido pela razão mais importante. */
    private fun spokenReason(v: Verdict): String {
        if (v.alerts.any { it.contains("terra", ignoreCase = true) }) return "Atenção, estrada de terra"
        if (v.alerts.any { it.contains("evita", ignoreCase = true) }) return "Bairro que você evita"
        if (!v.good) {
            if (v.reasons.any { it.contains("Prejuízo", ignoreCase = true) }) return "Dá prejuízo com o combustível"
            if (v.reasons.any { it.contains("Busca longe", ignoreCase = true) }) return "Passageiro muito longe"
            if (v.reasons.any { it.contains("meta", ignoreCase = true) }) return "Valor abaixo da sua meta"
            return ""
        }
        if (v.alerts.any { it.contains("dinâmico", ignoreCase = true) }) return "Com preço dinâmico"
        return ""
    }

    @Suppress("DEPRECATION")
    private fun vibrate(good: Boolean) {
        val vib = getSystemService(Vibrator::class.java) ?: return
        val pattern = if (good) longArrayOf(0, 120) else longArrayOf(0, 250, 120, 250)
        if (Build.VERSION.SDK_INT >= 26) vib.vibrate(VibrationEffect.createWaveform(pattern, -1))
        else vib.vibrate(pattern, -1)
    }

    private fun appEnabled(pkg: String): Boolean {
        val pr = Prefs(this)
        return if (pkg.contains("99") || pkg.contains("taxis99")) pr.ler99 else pr.lerUber
    }

    private var lastApp = "Uber"

    private fun collectText(): String {
        val sb = StringBuilder()
        val ws = try { windows } catch (e: Exception) { emptyList() }
        for (w in ws) {
            val r = w.root ?: continue
            val pkg = r.packageName?.toString() ?: continue
            if (!isRideApp(pkg) || !appEnabled(pkg)) continue
            lastApp = if (pkg.contains("99")) "99" else "Uber"
            walk(r, sb, 0)
        }
        if (sb.isEmpty()) {
            val r = rootInActiveWindow
            val pkg = r?.packageName?.toString()
            if (r != null && pkg != null && isRideApp(pkg)) {
                lastApp = if (pkg.contains("99")) "99" else "Uber"
                walk(r, sb, 0)
            }
        }
        return sb.toString()
    }

    private fun walk(n: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        if (depth > 40 || sb.length > 8000) return
        val t = n.text
        if (!t.isNullOrBlank()) sb.append(t).append('\n')
        val cd = n.contentDescription
        if (!cd.isNullOrBlank() && cd.toString() != t?.toString()) sb.append(cd).append('\n')
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            walk(c, sb, depth + 1)
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        try { overlay.hide() } catch (_: Exception) { }
        tts?.shutdown()
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: OfferReaderService? = null

        private val RIDE_PACKAGES = setOf(
            "com.ubercab.driver",   // Uber Motorista
            "com.app99.driver",     // 99 Motorista
        )

        fun isRideApp(pkg: String): Boolean =
            pkg in RIDE_PACKAGES || pkg.startsWith("com.ubercab.driver") ||
                pkg.startsWith("com.app99.driver") || pkg.startsWith("com.taxis99.driver")
    }
}
