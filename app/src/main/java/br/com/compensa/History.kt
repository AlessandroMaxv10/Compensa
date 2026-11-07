package br.com.compensa

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Histórico das últimas ofertas analisadas (fica só no celular). */
object History {
    private const val MAX = 150

    private fun sp(ctx: Context) = ctx.getSharedPreferences("compensa_hist", Context.MODE_PRIVATE)

    fun list(ctx: Context): JSONArray =
        try { JSONArray(sp(ctx).getString("h", "[]")) } catch (e: Exception) { JSONArray() }

    fun add(ctx: Context, o: Offer, v: Verdict): Long {
        val id = System.currentTimeMillis()
        val arr = list(ctx)
        arr.put(
            JSONObject()
                .put("id", id)
                .put("app", o.app)
                .put("fare", o.fare)
                .put("km", o.pickupKm + o.tripKm)
                .put("dest", o.destination ?: "")
                .put("good", v.good)
                .put("perKm", v.perKm)
                .put("perHour", v.perHour)
                .put("profit", v.profit)
        )
        save(ctx, arr)
        return id
    }

    fun update(ctx: Context, id: Long, v: Verdict? = null, feedback: Boolean? = null) {
        val arr = list(ctx)
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            if (e.optLong("id") != id) continue
            if (v != null) e.put("good", v.good).put("perHour", v.perHour).put("profit", v.profit)
            if (feedback != null) e.put("fb", feedback)
        }
        save(ctx, arr)
    }

    private fun save(ctx: Context, arr: JSONArray) {
        val out = JSONArray()
        val start = maxOf(0, arr.length() - MAX)
        for (i in start until arr.length()) out.put(arr.get(i))
        sp(ctx).edit().putString("h", out.toString()).apply()
    }

    fun clear(ctx: Context) = sp(ctx).edit().clear().apply()
}
