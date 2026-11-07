package br.com.compensa

import android.content.Context

/** Suporte pelo WhatsApp (vai direto para o seu número). */
object Support {
    const val PHONE = "5511939370020"      // 55 (Brasil) + 11 93937-0020
    const val DISPLAY = "(11) 93937-0020"

    fun link(mensagem: String): String =
        "https://wa.me/$PHONE?text=" + java.net.URLEncoder.encode(mensagem, "UTF-8")
}

/** Controle do turno: quanto tempo você está rodando (para o alerta de cansaço). */
object Shift {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("compensa_turno", Context.MODE_PRIVATE)

    fun markActivity(ctx: Context) {
        val now = System.currentTimeMillis()
        val last = sp(ctx).getLong("last", 0)
        val e = sp(ctx).edit()
        // parado mais de 4 horas = turno novo
        if (now - last > 4 * 3600_000L) e.putLong("start", now)
        e.putLong("last", now).apply()
    }

    fun hoursToday(ctx: Context): Double {
        val start = sp(ctx).getLong("start", 0)
        val last = sp(ctx).getLong("last", 0)
        if (start == 0L || System.currentTimeMillis() - last > 4 * 3600_000L) return 0.0
        return (last - start) / 3600_000.0
    }
}

/** Etanol compensa se custar até ~70% da gasolina (ajustado pelo consumo real do carro). */
object Fuel {
    fun compare(gasolina: Double, etanol: Double, kmlGas: Double, kmlEta: Double): String {
        if (gasolina <= 0 || etanol <= 0 || kmlGas <= 0 || kmlEta <= 0) return "Preencha os quatro valores."
        val custoGas = gasolina / kmlGas
        val custoEta = etanol / kmlEta
        val melhor = if (custoEta < custoGas) "ETANOL" else "GASOLINA"
        val pct = etanol / gasolina * 100
        return "Abasteça com $melhor\n" +
            "Gasolina: " + money(custoGas) + " por km  •  Etanol: " + money(custoEta) + " por km\n" +
            "Etanol está a " + num(pct, 0) + "% do preço da gasolina"
    }
}

/** Dicas práticas para o motorista de app. */
object Tips {
    val list = listOf(
        "💰 Olhe o R\$/km E o R\$/hora. Corrida curta pode ter bom R\$/km e péssimo R\$/hora no trânsito.",
        "📍 Conte o km até o passageiro. Busca de 5 km numa corrida de 3 km quase sempre não compensa.",
        "↩️ Corrida para outra cidade: pense na volta vazia. O Compensa já soma isso quando o destino é fora da sua cidade base.",
        "⛽ Anote o consumo real do seu carro (km/L) e o preço que você paga. Com isso o lucro calculado fica certo.",
        "🧾 Separe uma parte de cada dia para manutenção: pneu, óleo, freio e revisão. Coloque esse valor em \"Outros custos por km\".",
        "📊 Recusar muitas corridas pode reduzir a taxa de aceitação e a prioridade em alguns apps. Use metas realistas para sua região.",
        "🌧️ Em chuva, horário de pico e eventos a demanda sobe: é quando dá pra ser mais exigente.",
        "😴 Depois de 10 horas de volante o risco de acidente sobe muito. O Compensa avisa quando chegar nesse tempo.",
        "🛡️ Ative o compartilhamento de viagem e a gravação de áudio da própria Uber/99 e deixe um contato de confiança cadastrado.",
        "🏛️ Como MEI (motorista de aplicativo independente) você contribui para o INSS e garante aposentadoria e auxílio-doença. Vale consultar.",
        "🚗 Tenha seguro com cobertura para uso em aplicativo: seguro comum pode negar sinistro durante corrida.",
        "🧠 Dê 👍 ou 👎 nas corridas: quanto mais você avalia, mais a IA aprende o seu jeito de rodar.",
    )
}
