package pl.mati.caros.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class CarMeshTest {
    private val types = listOf(null, "hatchback", "sedan", "kombi", "suv", "van", "pickup", "coupe", "minivan")

    @Test fun everyBodyBuildsAClosedLookingCar() {
        for (t in types) {
            val f = CarMesh.car(t, 0xFF1F4E8C.toInt())
            assertTrue("$t: za mało ścian", f.size > 400)
            val (w, l) = CarMesh.size(t)
            val xs = f.flatMap { it.pts }.map { it.x }
            val ys = f.flatMap { it.pts }.map { it.y }
            val zs = f.flatMap { it.pts }.map { it.z }
            // obrys mieści się w wymiarach (lusterka i koła trochę wystają)
            assertTrue("$t: szerokość", xs.max() - xs.min() < w + 0.35)
            assertTrue("$t: długość", abs((ys.max() - ys.min()) - l) < 0.05)
            assertTrue("$t: stoi na ziemi", abs(zs.min()) < 0.02)
            // normalne jednostkowe
            for (x in f) assertEquals(1.0, sqrt(x.nx * x.nx + x.ny * x.ny + x.nz * x.nz), 1e-6)
            // szyby, lampy, opony, felgi
            for (m in listOf(CarMesh.GLASS, CarMesh.LIGHT_F, CarMesh.LIGHT_R, CarMesh.TIRE, CarMesh.RIM)) {
                assertTrue("$t: brak materiału $m", f.any { it.mat == m })
            }
        }
    }

    @Test fun roofIsPaintedAndWindshieldIsGlass() {
        val f = CarMesh.car("sedan", 0xFFB71C1C.toInt())
        // dach sedana: między słupkami (y od -0.8 do 0.3 m)
        val top = f.filter { it.nz > 0.97 && it.pts.all { p -> p.z > 1.3 && p.y > -0.8 && p.y < 0.3 } }
        assertTrue(top.isNotEmpty() && top.all { it.mat == CarMesh.PAINT })
        assertTrue(f.any { it.mat == CarMesh.GLASS && it.ny > 0.3 && it.nz > 0.3 }) // przednia szyba: do przodu i w górę
    }

    @Test fun rasterizeDrawsTheCarInTheMiddle() {
        val n = 200
        val px = CarMesh.rasterize(CarMesh.car("minivan", 0xFF1F4E8C.toInt()), 212.0, 20.0, n, 6.0)
        val filled = px.count { it != 0 }
        assertTrue("za mało pikseli: $filled", filled > n * n / 12)
        assertTrue(px[(n / 2 - 10) * n + n / 2] != 0)   // środek kadru zajęty
        assertEquals(0, px[0])                          // róg pusty
        // obrys dla ciemnych lakierów
        val rim = CarMesh.rasterize(CarMesh.car("sedan", 0xFF101010.toInt()), 35.0, 38.0, n, 6.0, rim = 0xFFC8D2DE.toInt())
        assertTrue(rim.count { it == 0xFFC8D2DE.toInt() } > 50)
    }

    @Test fun personStillWorks() {
        val f = CarMesh.person(0xFF2FD3F0.toInt())
        assertTrue(f.size > 50)
        assertTrue(CarMesh.rasterize(f, 25.0, 30.0, 120, 2.4, 0.88).count { it != 0 } > 300)
    }
}
