package br.com.compensa

/** Dados de uma oferta de corrida lidos da tela. */
data class Offer(
    val app: String,
    val fare: Double,
    val pickupKm: Double,
    val pickupMin: Double,
    val tripKm: Double,
    val tripMin: Double,
    val destination: String?,
    val surge: Boolean,
)

/**
 * Lê o texto do card de corrida (Uber / 99) e extrai valor, distâncias, tempos e destino.
 * Funciona por padrões de texto, então aguenta pequenas mudanças de layout dos apps.
 */
object OfferParser {

    private val PRICE = Regex("""(\+\s*)?R\$\s*(\d{1,4}(?:\.\d{3})*(?:,\d{1,2})?)""")
    private val KM = Regex("""(\d{1,3}(?:[.,]\d{1,2})?)\s*km\b""", RegexOption.IGNORE_CASE)
    private val METERS = Regex("""\b(\d{2,4})\s*m\b""")
    private val TIME = Regex("""(?:(\d{1,2})\s*h\s*)?(\d{1,3})\s*min""", RegexOption.IGNORE_CASE)
    private val ADDRESS_HINT = Regex(
        """(?i)\b(rua|avenida|estrada|rodovia|alameda|travessa|pra[cç]a|largo|bairro|jardim|vila|parque|ch[aá]cara|condom[ií]nio|centro|sp)\b|\b(r|av|estr|rod|al|tv|jd)\."""
    )
    private val NOISE = Regex("""(?i)(aceitar|recusar|corrida|viagem de|dist[âa]ncia|pagamento|dinheiro|cart[ãa]o|avalia|★|⭐|verificad|toque|passageiro|uberx|comfort|99pop|99 pop|black|flash|entrega)""")

    private fun toDouble(s: String): Double =
        s.replace(".", "").replace(',', '.').toDoubleOrNull() ?: 0.0

    private fun kmNumber(s: String): Double = s.replace(',', '.').toDoubleOrNull() ?: 0.0

    fun parse(text: String, app: String): Offer? {
        if (!text.contains("R$")) return null

        // Valor da corrida.
        var fare = 0.0
        var surge = false
        // Na Uber o preço vem logo ANTES de "Est. de R$ X/km". Pegamos o último
        // R$ antes desse trecho — assim ignoramos o total de ganhos do dia (que
        // fica na tela inicial) e qualquer outro valor solto na tela.
        val estIdx = Regex("""(?i)est\.?\s*de\s*r\$""").find(text)?.range?.first ?: -1
        if (estIdx > 0) {
            val before = text.substring(0, estIdx)
            val cand = PRICE.findAll(before).lastOrNull { it.groupValues[1].isEmpty() }
            if (cand != null) {
                val v = toDouble(cand.groupValues[2])
                if (v in 0.5..2000.0) fare = v
            }
        }
        // 99 e afins não têm "Est. de". O valor da corrida costuma vir logo ANTES
        // da nota do passageiro (ex.: "5,00 (5)"). Pegamos o último R$ antes dela.
        if (fare <= 0) {
            val ratingIdx = Regex("""\d{1,2},\d{2}\s*\(\s*\d+\s*\)""").find(text)?.range?.first ?: -1
            if (ratingIdx > 0) {
                val before = text.substring(0, ratingIdx)
                val cand = PRICE.findAll(before).lastOrNull { it.groupValues[1].isEmpty() }
                if (cand != null) {
                    val v = toDouble(cand.groupValues[2])
                    if (v in 0.5..2000.0) fare = v
                }
            }
        }
        // Último caso: o maior R$ que não seja um adicional "+R$".
        if (fare <= 0) {
            for (m in PRICE.findAll(text)) {
                if (m.groupValues[1].isNotEmpty()) { surge = true; continue }
                val v = toDouble(m.groupValues[2])
                if (v > fare && v < 2000) fare = v
            }
        }
        if (fare <= 0) return null
        if (Regex("(?i)(din[âa]mic|surge|\\d[.,]\\dx|tarifa alta)").containsMatchIn(text)) surge = true

        // Distâncias na ordem em que aparecem (km e metros)
        val dists = ArrayList<Pair<Int, Double>>()
        KM.findAll(text).forEach { dists.add(it.range.first to kmNumber(it.groupValues[1])) }
        METERS.findAll(text).forEach { dists.add(it.range.first to (it.groupValues[1].toDouble() / 1000.0)) }
        dists.sortBy { it.first }
        if (dists.isEmpty()) return null

        val times = TIME.findAll(text).map {
            val h = it.groupValues[1].toDoubleOrNull() ?: 0.0
            val m = it.groupValues[2].toDoubleOrNull() ?: 0.0
            h * 60 + m
        }.toList()

        val pickupKm: Double
        val tripKm: Double
        if (dists.size >= 2) {
            pickupKm = dists[0].second
            tripKm = dists.drop(1).maxOf { it.second }
        } else {
            pickupKm = 0.0
            tripKm = dists[0].second
        }
        val pickupMin: Double
        val tripMin: Double
        if (times.size >= 2) {
            pickupMin = times[0]
            tripMin = times.drop(1).maxOrNull() ?: 0.0
        } else {
            pickupMin = 0.0
            tripMin = times.firstOrNull() ?: 0.0
        }
        if (tripKm <= 0 && pickupKm <= 0) return null

        return Offer(app, fare, pickupKm, pickupMin, tripKm, tripMin, findDestination(text), surge)
    }

    /** O destino costuma ser o último endereço do card. */
    private fun findDestination(text: String): String? {
        val lines = text.lines().map { it.trim() }.filter { it.length >= 4 }
        val candidates = lines.filter { l ->
            !l.contains("R$") && !KM.containsMatchIn(l) && !TIME.containsMatchIn(l) &&
                !NOISE.containsMatchIn(l.take(14)) &&
                (ADDRESS_HINT.containsMatchIn(l) || (l.contains(',') && l.any { it.isLetter() }) || l.contains(" - "))
        }
        return candidates.lastOrNull()?.take(120)
    }
}
