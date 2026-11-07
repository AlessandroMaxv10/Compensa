package br.com.compensa

import kotlin.math.max
import kotlin.math.min

/** Informações extras que chegam do mapa e do clima (podem demorar 1–3 s). */
data class Extras(
    val unpaved: Boolean? = null,
    val rain: Boolean? = null,
    val outsideBase: Boolean? = null,
    val city: String? = null,
    val note: String? = null,
)

data class Verdict(
    val good: Boolean,
    val score: Double,
    val perKm: Double,
    val perHour: Double,
    val profit: Double,
    val reasons: List<String>,
    val alerts: List<String>,
    val aiUsed: Boolean,
    val features: DoubleArray,
)

object Scorer {

    fun evaluate(o: Offer, p: Prefs, brain: Brain, ex: Extras, hour: Int, weekend: Boolean): Verdict {
        val kmCliente = max(o.pickupKm + o.tripKm, 0.1)
        val minCliente = (o.pickupMin + o.tripMin).let { if (it > 0) it else kmCliente * 2.2 }

        // Volta vazia quando o destino é fora da cidade base
        val voltaKm = if (p.contarVolta && ex.outsideBase == true) o.tripKm else 0.0
        val voltaMin = if (voltaKm > 0) (if (o.tripMin > 0) o.tripMin else o.tripKm * 1.5) else 0.0

        val totalKm = kmCliente + voltaKm
        val totalMin = max(minCliente + voltaMin, 1.0)

        val custo = totalKm / p.kmPorLitro * p.precoCombustivel + totalKm * p.custoExtraKm
        val lucro = o.fare - custo
        val perKm = o.fare / kmCliente
        val perHour = o.fare / (totalMin / 60.0)

        val rKm = perKm / p.minPorKm
        val rHora = perHour / p.minPorHora
        var rule = ((min(rKm, rHora) - 0.7) / 0.6).coerceIn(0.0, 1.0)

        val reasons = ArrayList<String>()
        val alerts = ArrayList<String>()

        if (rKm < 1) reasons.add("Abaixo da meta por km (" + money(p.minPorKm) + ")")
        if (rHora < 1) reasons.add("Abaixo da meta por hora (" + money(p.minPorHora) + ")")
        if (o.pickupKm > p.maxBuscaKm) {
            rule *= 0.5
            reasons.add("Busca longe: " + num(o.pickupKm) + " km")
        }
        if (lucro <= 0) {
            rule = 0.0
            reasons.add("Prejuízo depois do combustível")
        }

        val f = brain.features(rKm, rHora, o.pickupKm, o.tripKm, hour, weekend, ex.rain, ex.unpaved, ex.outsideBase, o.surge)
        val aiUsed = brain.active
        val score = if (aiUsed) 0.5 * rule + 0.5 * brain.predict(f) else rule

        var blocked = false
        val dest = o.destination?.let { norm(it) } ?: ""
        val evitado = p.bairrosEvitar.firstOrNull { dest.isNotEmpty() && dest.contains(norm(it)) }
        if (evitado != null) {
            blocked = true
            alerts.add("🚫 Bairro que você evita: $evitado")
        }
        if (ex.unpaved == true) {
            alerts.add("⚠️ Destino com estrada de TERRA")
            if (p.evitarTerra) blocked = true
        }
        if (ex.rain == true) alerts.add("🌧️ Chovendo no destino")
        if (ex.outsideBase == true) alerts.add("↩️ Fora de " + p.cidadeBase + (ex.city?.let { " ($it)" } ?: "") + ": volta vazia contada")
        if (o.surge) alerts.add("🔥 Preço dinâmico")
        ex.note?.let { alerts.add(it) }

        return Verdict(!blocked && score >= 0.5, score, perKm, perHour, lucro, reasons, alerts, aiUsed, f)
    }
}
