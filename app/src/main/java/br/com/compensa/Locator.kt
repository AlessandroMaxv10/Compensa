package br.com.compensa

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager

/**
 * Pega a localização atual do motorista (onde ele está agora).
 * Usa a última posição conhecida do sistema — como a Uber/99 ficam usando o GPS o tempo todo,
 * essa posição está sempre fresquinha, sem gastar bateria com pedidos próprios.
 */
object Locator {

    fun hasPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun lastKnown(ctx: Context): Location? {
        if (!hasPermission(ctx)) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        var best: Location? = null
        try {
            for (p in lm.getProviders(true)) {
                val l = lm.getLastKnownLocation(p) ?: continue
                if (best == null || l.time > best!!.time) best = l
            }
        } catch (e: SecurityException) {
            return null
        }
        // só vale se for recente (menos de 10 minutos)
        return best?.takeIf { System.currentTimeMillis() - it.time < 10 * 60_000 }
    }
}
