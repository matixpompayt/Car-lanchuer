package pl.mati.caros.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTest {
    @Test fun distances() {
        // Kostrzyn → Berlin (Alexanderplatz) ok. 80 km w linii prostej
        val d = Geo.dist(52.588, 14.647, 52.5219, 13.4132)
        assertTrue("$d", d in 82_000.0..85_000.0)
        assertEquals(0.0, Geo.dist(52.0, 14.0, 52.0, 14.0), 1e-6)
        // 1° szerokości ≈ 111,2 km
        assertEquals(111_195.0, Geo.dist(52.0, 14.0, 53.0, 14.0), 100.0)
    }

    @Test fun cumulative() {
        val lats = doubleArrayOf(52.0, 52.01, 52.02)
        val lons = doubleArrayOf(14.0, 14.0, 14.0)
        val c = Geo.cumulative(lats, lons)
        assertEquals(0.0, c[0], 1e-9)
        assertEquals(c[1] * 2, c[2], 1.0)
    }

    /** Rzut punktu na trasę – miejsce na trasie i odległość od niej (AlongRoute). */
    @Test fun alongRouteProjection() {
        // trasa na północ, 2 km, punkty co 1 km
        val lats = doubleArrayOf(52.0, 52.009, 52.018)
        val lons = doubleArrayOf(14.0, 14.0, 14.0)
        val cum = Geo.cumulative(lats, lons)
        // stacja 300 m na wschód od połowy pierwszego odcinka
        val dLon = 300.0 / (111_320.0 * Math.cos(Math.toRadians(52.0045)))
        val (off, at) = AlongRoute.nearRoute(52.0045, 14.0 + dLon, lats, lons, cum, 0.0, cum.last())
        assertEquals(300.0, off, 5.0)
        assertEquals(cum[1] / 2, at, 10.0)
        assertEquals(cum[1], AlongRoute.positionOf(52.009, 14.0, lats, lons, cum), 1.0)
    }
}
