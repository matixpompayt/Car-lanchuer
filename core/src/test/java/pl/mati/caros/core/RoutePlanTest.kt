package pl.mati.caros.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoutePlanTest {
    private fun opt(name: String, pln: Double?, src: String? = null, cheap: Boolean = false, open: Boolean = true) =
        RoutePlan.Opt(RoutePlan.Place(name, "", 0.0, 0.0), 10.0, 0L, 0.0, false, price = pln, cur = "PLN", pln = pln, cheap = cheap, open = open, src = src)

    @Test fun cheapestFirst() {
        val l = listOf(opt("A", 6.49), opt("B", 6.19, "station"), opt("C", null), opt("D", 6.20, "max"))
        assertEquals(listOf("B", "D", "A", "C"), RoutePlan.sortByPrice(l).map { it.place.name })
    }

    @Test fun closedLastAndStationBeforeEstimate() {
        val l = listOf(opt("zamknięta", 5.0, "station", open = false), opt("średnia", 6.21, "avg"), opt("stacja", 6.20, "station"))
        // 6,20 i 6,21 to ten sam przedział 5 gr → cena ze stacji pierwsza
        assertEquals(listOf("stacja", "średnia", "zamknięta"), RoutePlan.sortByPrice(l).map { it.place.name })
    }

    @Test fun fullTank() {
        assertEquals(800.0, RoutePlan.fullRangeKm(56.0, 7.0)!!, 0.01)
        assertNull(RoutePlan.fullRangeKm(56.0, null))
        assertNull(RoutePlan.fullRangeKm(0.0, 6.0))
    }

    @Test fun rangeLeftAfterRefuel() {
        val g1 = RoutePlan.Group("fuel", 1, 300.0, listOf(opt("S", 6.0).copy(atKm = 290.0)), 1)
        val res = RoutePlan.Result(listOf(g1), 900.0, FuelType.ON, fullKm = 800.0)
        assertEquals(50.0, RoutePlan.rangeLeftAt(res, 340.0, 290.0), 0.01) // przed tankowaniem
        assertEquals(700.0, RoutePlan.rangeLeftAt(res, 340.0, 390.0), 0.01) // po: pełny bak − 100 km
    }

    @Test fun priceTexts() {
        assertEquals("6,29 zł/l", RoutePlan.priceText(opt("x", 6.29)))
        val eur = opt("y", null).copy(price = 1.719, cur = "EUR", pln = 7.31)
        assertEquals("1,719 € (7,31 zł)/l", RoutePlan.priceText(eur))
    }
}
