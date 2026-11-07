package br.com.compensa

import android.content.Context
import android.content.SharedPreferences
import java.text.Normalizer
import java.util.Locale

/** Configurações do motorista (metas e custos do carro). */
class Prefs(ctx: Context) {
    val sp: SharedPreferences = ctx.getSharedPreferences("compensa", Context.MODE_PRIVATE)

    private fun d(key: String, def: Double): Double =
        sp.getString(key, null)?.trim()?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it > 0 } ?: def

    val minPorKm: Double get() = d(K_MIN_KM, 1.80)
    val minPorHora: Double get() = d(K_MIN_HORA, 35.0)
    val maxBuscaKm: Double get() = d(K_MAX_BUSCA, 4.0)
    val precoCombustivel: Double get() = d(K_PRECO, 6.19)
    val kmPorLitro: Double get() = d(K_KML, 13.0)
    val custoExtraKm: Double get() = d(K_CUSTO_KM, 0.20)
    val cidadeBase: String get() = sp.getString(K_CIDADE, null)?.trim()?.ifEmpty { null } ?: "São Roque"

    val bairrosEvitar: List<String>
        get() = (sp.getString(K_BAIRROS, "") ?: "")
            .split(',', ';', '\n')
            .map { it.trim() }
            .filter { it.length >= 3 }

    val lerUber: Boolean get() = sp.getBoolean(K_LER_UBER, true)
    val ler99: Boolean get() = sp.getBoolean(K_LER_99, true)
    val evitarTerra: Boolean get() = sp.getBoolean(K_EVITAR_TERRA, true)
    val voz: Boolean get() = sp.getBoolean(K_VOZ, true)
    val usarMapa: Boolean get() = sp.getBoolean(K_MAPA, true)
    val contarVolta: Boolean get() = sp.getBoolean(K_VOLTA, true)

    fun raw(key: String, def: String): String = sp.getString(key, null) ?: def
    fun putString(key: String, v: String) = sp.edit().putString(key, v).apply()
    fun putBool(key: String, v: Boolean) = sp.edit().putBoolean(key, v).apply()

    companion object {
        const val K_MIN_KM = "min_km"
        const val K_MIN_HORA = "min_hora"
        const val K_MAX_BUSCA = "max_busca"
        const val K_PRECO = "preco_comb"
        const val K_KML = "km_l"
        const val K_CUSTO_KM = "custo_km"
        const val K_CIDADE = "cidade"
        const val K_BAIRROS = "bairros"
        const val K_LER_UBER = "ler_uber"
        const val K_LER_99 = "ler_99"
        const val K_EVITAR_TERRA = "evitar_terra"
        const val K_VOZ = "voz"
        const val K_MAPA = "mapa"
        const val K_VOLTA = "volta"
    }
}

val BR: Locale = Locale.forLanguageTag("pt-BR")

fun money(v: Double): String = "R\$ " + String.format(BR, "%.2f", v)
fun num(v: Double, casas: Int = 1): String = String.format(BR, "%." + casas + "f", v)

/** "São Roque" -> "sao roque" (sem acento, minúsculo) para comparar textos. */
fun norm(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .trim()
