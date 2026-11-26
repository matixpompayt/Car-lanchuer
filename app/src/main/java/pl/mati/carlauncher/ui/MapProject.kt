package pl.mati.carlauncher.ui

import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Projection
import kotlin.math.abs

/**
 * Wiele punktów mapy na ekran jednym wywołaniem (zamiast setek osobnych wywołań na klatkę –
 * numery domów, palmy). Kolejność współrzędnych w [Projection.toScreenLocations] sprawdzamy
 * raz na żywo z pojedynczym przeliczeniem; gdyby coś się nie zgadzało – liczymy po jednym, jak dawniej.
 */
internal object MapProject {
    private const val UNKNOWN = 0
    private const val LAT_LON = 1
    private const val LON_LAT = 2
    private const val SINGLE = 3

    @Volatile private var mode = UNKNOWN

    /**
     * [latLon] – pary (szer., dł.) dla [n] punktów; [out] – co najmniej 2·n miejsc na (x, y) w pikselach mapy.
     * Wywoływać z wątku mapy (rysowanie).
     */
    fun toScreen(proj: Projection, latLon: DoubleArray, n: Int, out: DoubleArray) {
        if (n <= 0) return
        // punkt testowy z różną szerokością i długością – inaczej obie kolejności dają to samo
        if (mode == UNKNOWN && abs(latLon[0] - latLon[1]) > 0.5) mode = detect(proj, latLon[0], latLon[1])
        try {
            when (mode) {
                LAT_LON -> {
                    val input = if (latLon.size == n * 2) latLon else latLon.copyOf(n * 2)
                    val o = if (out.size == n * 2) out else DoubleArray(n * 2)
                    proj.toScreenLocations(input, o)
                    if (o !== out) System.arraycopy(o, 0, out, 0, n * 2)
                    return
                }
                LON_LAT -> {
                    val input = DoubleArray(n * 2)
                    for (i in 0 until n) { input[i * 2] = latLon[i * 2 + 1]; input[i * 2 + 1] = latLon[i * 2] }
                    val o = if (out.size == n * 2) out else DoubleArray(n * 2)
                    proj.toScreenLocations(input, o)
                    if (o !== out) System.arraycopy(o, 0, out, 0, n * 2)
                    return
                }
            }
        } catch (_: Throwable) {
            mode = SINGLE
        }
        for (i in 0 until n) {
            val p = proj.toScreenLocation(LatLng(latLon[i * 2], latLon[i * 2 + 1]))
            out[i * 2] = p.x.toDouble()
            out[i * 2 + 1] = p.y.toDouble()
        }
    }

    private fun detect(proj: Projection, lat: Double, lon: Double): Int = try {
        val ref = proj.toScreenLocation(LatLng(lat, lon))
        val o = DoubleArray(2)
        fun same() = abs(o[0] - ref.x) < 1.5 && abs(o[1] - ref.y) < 1.5
        proj.toScreenLocations(doubleArrayOf(lat, lon), o)
        if (same()) LAT_LON
        else {
            proj.toScreenLocations(doubleArrayOf(lon, lat), o)
            if (same()) LON_LAT else SINGLE
        }
    } catch (_: Throwable) {
        SINGLE
    }
}
