package pl.mati.caros.core

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    /** Odległość po powierzchni Ziemi w metrach (haversine). */
    fun dist(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** Skumulowane odległości wzdłuż linii (m) – do AlongRoute. */
    fun cumulative(lats: DoubleArray, lons: DoubleArray): DoubleArray {
        val n = lats.size
        val cum = DoubleArray(n)
        for (i in 1 until n) cum[i] = cum[i - 1] + dist(lats[i - 1], lons[i - 1], lats[i], lons[i])
        return cum
    }
}
