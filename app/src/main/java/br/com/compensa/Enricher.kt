package br.com.compensa

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Consulta o mapa (OpenStreetMap) e o clima (Open-Meteo) para o destino:
 *  - a rua do destino é de terra/cascalho?
 *  - está chovendo lá?
 *  - o destino é fora da sua cidade (risco de voltar vazio)?
 * Roda em segundo plano: o verde/vermelho aparece na hora e é refinado logo depois.
 * Serviços gratuitos, sem chave e sem cadastro.
 */
object Enricher {

    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val cache = ConcurrentHashMap<String, Extras>()
    private var baseBox: DoubleArray? = null
    private var baseBoxCity: String? = null

    private val UNPAVED = setOf(
        "unpaved", "dirt", "gravel", "ground", "earth", "sand", "compacted",
        "grass", "mud", "fine_gravel", "pebblestone", "rock", "woodchips", "grass_paver",
    )

    fun enrich(dest: String, baseCity: String, driverLat: Double?, driverLon: Double?, cb: (Extras) -> Unit) {
        // a chave inclui a região do motorista, pois o "fora da cidade" depende de onde ele está
        val key = norm(dest) + "@" + (driverLat?.let { "%.2f".format(it) } ?: "") + "," + (driverLon?.let { "%.2f".format(it) } ?: "")
        cache[key]?.let { cb(it); return }
        exec.execute {
            val ex = try {
                work(dest, baseCity, driverLat, driverLon)
            } catch (t: Throwable) {
                Extras(note = "📡 Mapa indisponível agora")
            }
            if (ex.note == null) cache[key] = ex
            main.post { cb(ex) }
        }
    }

    private fun work(dest: String, baseCity: String, driverLat: Double?, driverLon: Double?): Extras {
        // cidade base = onde o motorista está AGORA (pelo GPS); se não tiver GPS, usa a cidade das configurações
        var baseNow = baseCity
        val box: DoubleArray? = if (driverLat != null && driverLon != null) {
            try { cityOf(driverLat, driverLon)?.let { baseNow = it } } catch (e: Exception) { }
            doubleArrayOf(driverLon - 0.6, driverLat + 0.6, driverLon + 0.6, driverLat - 0.6)
        } else {
            baseViewbox(baseCity)
        }

        val place = geocode(dest, box) ?: geocode("$dest, $baseNow", box)
            ?: return Extras(note = "📍 Destino não achado no mapa")

        val lat = place.getString("lat").toDouble()
        val lon = place.getString("lon").toDouble()
        val addr = place.optJSONObject("address")
        val city = addr?.let {
            it.optString("city").ifEmpty { null } ?: it.optString("town").ifEmpty { null }
                ?: it.optString("municipality").ifEmpty { null } ?: it.optString("village").ifEmpty { null }
        }
        val outside = city?.let { norm(it) != norm(baseNow) }

        val unpaved = try { surface(lat, lon) } catch (e: Exception) { null }
        val rain = try { raining(lat, lon) } catch (e: Exception) { null }
        return Extras(unpaved = unpaved, rain = rain, outsideBase = outside, city = city)
    }

    private fun baseViewbox(baseCity: String): DoubleArray? {
        if (baseBoxCity == baseCity) return baseBox
        val b = try { geocode("$baseCity, Brasil", null) } catch (e: Exception) { null } ?: return null
        val lat = b.getString("lat").toDouble()
        val lon = b.getString("lon").toDouble()
        baseBox = doubleArrayOf(lon - 0.6, lat + 0.6, lon + 0.6, lat - 0.6)
        baseBoxCity = baseCity
        return baseBox
    }

    private fun geocode(q: String, box: DoubleArray?): JSONObject? {
        var url = "https://nominatim.openstreetmap.org/search?format=json&limit=1&addressdetails=1&countrycodes=br&accept-language=pt-BR&q=" +
            URLEncoder.encode(q, "UTF-8")
        if (box != null) url += "&viewbox=" + box.joinToString(",")
        val arr = JSONArray(http(url))
        return if (arr.length() > 0) arr.getJSONObject(0) else null
    }

    /** Descobre a cidade de um ponto (latitude/longitude). */
    private fun cityOf(lat: Double, lon: Double): String? {
        val url = "https://nominatim.openstreetmap.org/reverse?format=json&zoom=10&addressdetails=1&accept-language=pt-BR&lat=$lat&lon=$lon"
        val addr = JSONObject(http(url)).optJSONObject("address") ?: return null
        return addr.optString("city").ifEmpty { null } ?: addr.optString("town").ifEmpty { null }
            ?: addr.optString("municipality").ifEmpty { null } ?: addr.optString("village").ifEmpty { null }
    }

    /** true = terra/cascalho, false = asfalto, null = mapa sem informação. */
    private fun surface(lat: Double, lon: Double): Boolean? {
        val q = "[out:json][timeout:6];way(around:120,$lat,$lon)[highway];out tags;"
        val body = "data=" + URLEncoder.encode(q, "UTF-8")
        val els = JSONObject(http("https://overpass-api.de/api/interpreter", body)).optJSONArray("elements") ?: return null
        var tagged = 0
        var unpaved = 0
        var track = false
        for (i in 0 until els.length()) {
            val t = els.getJSONObject(i).optJSONObject("tags") ?: continue
            val hw = t.optString("highway")
            if (hw == "track") track = true
            val s = t.optString("surface")
            if (s.isNotEmpty()) {
                tagged++
                if (s in UNPAVED) unpaved++
            }
        }
        if (track) return true
        if (tagged == 0) return null
        return unpaved * 2 >= tagged
    }

    private fun raining(lat: Double, lon: Double): Boolean? {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=precipitation"
        val cur = JSONObject(http(url)).optJSONObject("current") ?: return null
        return cur.optDouble("precipitation", 0.0) >= 0.2
    }

    private fun http(url: String, post: String? = null): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 3000
        c.readTimeout = 5000
        c.setRequestProperty("User-Agent", "Compensa/1.0 (app pessoal de motorista, Android)")
        if (post != null) {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(post.toByteArray()) }
        }
        try {
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
