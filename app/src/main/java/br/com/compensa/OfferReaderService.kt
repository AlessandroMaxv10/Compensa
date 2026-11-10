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
        startHeartbeat()
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            Api.ping(this@OfferReaderService)
            handler.postDelayed(this, 2 * 60_000) // a cada 2 minutos enquanto ativo
        }
    }
    private fun startHeartbeat() {
        handler.removeCallbacks(heartbeat)
        handler.post(heartbeat)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (!isRideApp(pkg)) return
        if (!appEnabled(pkg)) return
        lastApp = if (pkg.contains("99")) "99" else "Uber"
        // Captura IMEDIATAMENTE o que mudou na tela (nó de origem do evento).
        // É a forma mais confiável de pegar o card da oferta da Uber/99.
        try {
            val src = event.source
            if (src != null) {
                val set = LinkedHashSet<String>()
                walk(src, set, 0)
                if (set.isNotEmpty()) eventText = set.joinToString("\n")
            }
        } catch (e: Exception) {}
        if (pending) return
        pending = true
        // duas tentativas: o card pode terminar de desenhar um pouco depois
        handler.postDelayed({ scan() }, 80)
        handler.postDelayed({ pending = false; scan() }, 550)
    }

    private fun logDiag(line: String) {
        lastDiag = line
        try {
            val sp = getSharedPreferences("compensa_diag", MODE_PRIVATE)
            val cur = sp.getString("log", "") ?: ""
            val lines = (cur.split("\n") + line).filter { it.isNotBlank() }.takeLast(12)
            sp.edit().putString("log", lines.joinToString("\n")).apply()
        } catch (e: Exception) {}
    }

    private fun isSelf(t: String): Boolean =
        t.contains("COMPENSA") || t.contains("Lucro estimado") ||
            t.contains("IA aprendendo") || t.contains("NÃO COMPENSA")

    private fun scan() {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        val ev = eventText
        // 1) Primeiro tenta SÓ o card que mudou na tela (isola a oferta e evita
        //    pegar o total de ganhos da tela inicial da Uber).
        var src = "card"
        var text = ev
        var offer = if (ev.contains("R$") && !isSelf(ev)) OfferParser.parse(ev, lastApp) else null
        // 2) Se o card isolado não deu, tenta a tela inteira.
        if (offer == null) {
            val full = collectText()
            if (full.length > text.length) text = full
            if (full.contains("R$") && !isSelf(full)) {
                val o = OfferParser.parse(full, lastApp)
                if (o != null) { offer = o; src = "tela"; text = full }
            }
        }
        val snip = text.replace("\n", " · ").take(150)
        if (offer == null) {
            val why = when {
                isSelf(text) -> "IGNORADO (proprio app)"
                !text.contains("R$") -> "SEM R\$"
                !Regex("""\d\s*km""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "so tela inicial (sem corrida)"
                else -> "ACHOU R\$ mas nao entendeu o card"
            }
            logDiag("$stamp $lastApp $why | $snip"); return
        }
        logDiag("$stamp $lastApp OK[$src] R\$${offer.fare} km=${offer.pickupKm + offer.tripKm}")
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
    private var eventText = ""

    private fun collectText(): String {
        // Junta o texto de TODAS as janelas do app de corrida (a oferta às vezes
        // aparece numa janela separada por cima do mapa) + a janela ativa.
        // Usa um conjunto para não repetir linhas quando as janelas se sobrepõem.
        val lines = LinkedHashSet<String>()
        // Começa pelo que mudou na tela (nó de origem do último evento).
        if (eventText.isNotEmpty()) lines.addAll(eventText.split("\n"))
        var foundRide = eventText.isNotEmpty()
        val ws = try { windows } catch (e: Exception) { emptyList<android.view.accessibility.AccessibilityWindowInfo>() }
        for (w in ws) {
            val r = w.root ?: continue
            val pkg = r.packageName?.toString() ?: continue
            if (!isRideApp(pkg) || !appEnabled(pkg)) continue
            lastApp = if (pkg.contains("99")) "99" else "Uber"
            foundRide = true
            walk(r, lines, 0)
        }
        val ra = try { rootInActiveWindow } catch (e: Exception) { null }
        if (ra != null) {
            val rpkg = ra.packageName?.toString() ?: ""
            // NUNCA lê a própria tela do Compensa (senão lê a corrida de exemplo).
            if (isRideApp(rpkg) && appEnabled(rpkg)) {
                lastApp = if (rpkg.contains("99")) "99" else "Uber"
                walk(ra, lines, 0)
            }
        }
        val joined = lines.joinToString("\n")
        lastDiag = "app=" + lastApp + " janelas=" + ws.size + " evento=" + (if (eventText.isNotEmpty()) "sim" else "nao") +
            " linhas=" + lines.size + " R$=" + (if (joined.contains("R$")) "sim" else "nao")
        lastRaw = joined.replace("\n", " · ").take(220)
        return joined
    }

    private fun walk(n: AccessibilityNodeInfo, out: MutableSet<String>, depth: Int) {
        if (depth > 60 || out.size > 500) return
        val t = n.text
        if (!t.isNullOrBlank()) out.add(t.toString().trim())
        val cd = n.contentDescription
        if (!cd.isNullOrBlank()) out.add(cd.toString().trim())
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            walk(c, out, depth + 1)
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        handler.removeCallbacks(heartbeat)
        try { overlay.hide() } catch (_: Exception) { }
        tts?.shutdown()
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: OfferReaderService? = null

        // Texto curto de diagnóstico, mostrado na tela principal para depuração.
        @Volatile
        var lastDiag: String = "aguardando primeira corrida"

        // Trecho do texto cru lido na última corrida (para depuração).
        @Volatile
        var lastRaw: String = "—"

        private val RIDE_PACKAGES = setOf(
            "com.ubercab.driver",   // Uber Motorista
            "com.app99.driver",     // 99 Motorista
        )

        fun isRideApp(pkg: String): Boolean =
            pkg in RIDE_PACKAGES || pkg.startsWith("com.ubercab.driver") ||
                pkg.startsWith("com.app99.driver") || pkg.startsWith("com.taxis99.driver")
    }
}
