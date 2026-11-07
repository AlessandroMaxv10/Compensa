package br.com.compensa

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * IA do Compensa: um modelo que aprende o SEU jeito de rodar.
 *
 * Começa com um "conhecimento de fábrica" (corridas que batem suas metas são boas)
 * e, a cada 👍 / 👎 que você dá, ele ajusta os pesos: horário, dia da semana,
 * chuva, estrada de terra, busca longe, destino fora da cidade, dinâmico...
 * Tudo roda no próprio celular, sem internet e em menos de 1 milissegundo.
 */
class Brain(ctx: Context) {

    private val appCtx = ctx.applicationContext
    private val sp = ctx.getSharedPreferences("compensa_ia", Context.MODE_PRIVATE)
    /** Base de conhecimento: a IA coletiva do servidor (ou a de fábrica). */
    private var prior: DoubleArray = loadArr("global_w") ?: PRIOR.copyOf()
    private var w: DoubleArray = loadArr("w") ?: prior.copyOf()

    val samples: Int get() = loadSamples().length()
    val active: Boolean get() = samples >= MIN_SAMPLES

    fun features(
        rKm: Double, rHora: Double, pickupKm: Double, tripKm: Double,
        hour: Int, weekend: Boolean, rain: Boolean?, unpaved: Boolean?,
        outside: Boolean?, surge: Boolean,
    ): DoubleArray {
        val ang = 2 * PI * hour / 24.0
        return doubleArrayOf(
            1.0,
            min(rKm, 3.0),
            min(rHora, 3.0),
            min(pickupKm / 5.0, 3.0),
            min(tripKm / 20.0, 3.0),
            sin(ang),
            cos(ang),
            if (weekend) 1.0 else 0.0,
            if (rain == true) 1.0 else 0.0,
            if (unpaved == true) 1.0 else 0.0,
            if (outside == true) 1.0 else 0.0,
            if (surge) 1.0 else 0.0,
        )
    }

    fun predict(f: DoubleArray): Double {
        var z = 0.0
        for (i in f.indices) z += w[i] * f[i]
        return 1.0 / (1.0 + exp(-z))
    }

    /** Aprende com sua avaliação (true = foi boa, false = foi ruim). */
    fun learn(f: DoubleArray, good: Boolean) {
        val arr = loadSamples()
        arr.put(JSONObject().put("f", JSONArray(f.toList())).put("y", if (good) 1 else 0))
        val trimmed = JSONArray()
        val start = maxOf(0, arr.length() - MAX_SAMPLES)
        for (i in start until arr.length()) trimmed.put(arr.get(i))
        sp.edit().putString("samples", trimmed.toString()).apply()
        retrain(trimmed)
        Api.queueSample(appCtx, f, good)
    }

    /** Recebe a IA coletiva (aprendida com todos os motoristas) do servidor. */
    fun setGlobal(gw: DoubleArray) {
        if (gw.size != N || gw.any { it.isNaN() || it.isInfinite() }) return
        prior = gw.copyOf()
        sp.edit().putString("global_w", JSONArray(gw.toList()).toString()).apply()
        val arr = loadSamples()
        if (arr.length() == 0) {
            w = prior.copyOf()
            sp.edit().remove("w").apply()
        } else retrain(arr)
    }

    fun reset() {
        val g = sp.getString("global_w", null)
        sp.edit().clear().apply()
        if (g != null) sp.edit().putString("global_w", g).apply()
        w = prior.copyOf()
    }

    private fun retrain(arr: JSONArray) {
        val xs = ArrayList<DoubleArray>()
        val ys = ArrayList<Double>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val fa = o.getJSONArray("f")
            if (fa.length() != N) continue
            xs.add(DoubleArray(N) { fa.getDouble(it) })
            ys.add(o.getDouble("y"))
        }
        val nw = prior.copyOf()
        val lr = 0.08
        val lambda = 0.02 // puxa de volta para o conhecimento de fábrica (evita exagero)
        repeat(80) {
            for (k in xs.indices) {
                val x = xs[k]
                var z = 0.0
                for (i in 0 until N) z += nw[i] * x[i]
                val p = 1.0 / (1.0 + exp(-z))
                val err = ys[k] - p
                for (i in 0 until N) nw[i] += lr * (err * x[i] - lambda * (nw[i] - prior[i]))
            }
        }
        w = nw
        sp.edit().putString("w", JSONArray(nw.toList()).toString()).apply()
    }

    private fun loadSamples(): JSONArray =
        try { JSONArray(sp.getString("samples", "[]")) } catch (e: Exception) { JSONArray() }

    private fun loadArr(key: String): DoubleArray? {
        val s = sp.getString(key, null) ?: return null
        return try {
            val a = JSONArray(s)
            if (a.length() != N) null else DoubleArray(N) { a.getDouble(it) }
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        const val N = 12
        const val MIN_SAMPLES = 15
        const val MAX_SAMPLES = 400
        // bias, R$/km, R$/h, busca, tamanho, sen(h), cos(h), fds, chuva, terra, fora, dinâmico
        val PRIOR = doubleArrayOf(-4.0, 2.0, 2.0, -0.5, 0.0, 0.0, 0.0, 0.0, 0.0, -1.0, -0.5, 0.3)
    }
}
