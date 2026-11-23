package pl.mati.caros.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class LiveShareTest {
    @Test fun linkRoundTrip() {
        val k = LiveShare.newKey()
        val link = LiveShare.link(k)
        assertTrue(link.startsWith(LiveShare.PAGE + "#"))
        assertEquals(43, link.substringAfter('#').length) // 32 B base64url bez „=”
        assertArrayEquals(k, LiveShare.keyFromLink(link))
        assertNull(LiveShare.keyFromLink(LiveShare.PAGE))
        assertNull(LiveShare.keyFrom("abc"))
    }

    @Test fun topicIsSeparateChannel() {
        val k = ByteArray(32) { it.toByte() }
        val t = LiveShare.topic(k)
        assertTrue(t.startsWith("caros_") && t.length == 34)
        assertTrue(t != RelayCrypto.topicOf(k))
        // ten sam wynik co na stronie: sha256(klucz | "share"), 28 znaków hex
        assertEquals(RelayCrypto.topicOf(k, "share"), t)
    }

    @Test fun googlePolylineExample() {
        // przykład z dokumentacji Google
        val lats = doubleArrayOf(38.5, 40.7, 43.252)
        val lons = doubleArrayOf(-120.2, -120.95, -126.453)
        val s = LiveShare.encodePolyline(lats, lons)
        assertEquals("_p~iF~ps|U_ulLnnqC_mqNvxq`@", s)
        val back = LiveShare.decodePolyline(s)
        assertEquals(3, back.size)
        assertEquals(43.252, back[2].first, 1e-6)
        assertEquals(-126.453, back[2].second, 1e-6)
    }

    @Test fun simplifyKeepsEndsAndShape() {
        val n = 5000
        val lats = DoubleArray(n) { 52.0 + it * 0.0004 }
        val lons = DoubleArray(n) { 14.0 + 0.05 * sin(it / 300.0) }
        val idx = LiveShare.simplify(lats, lons, 200)
        assertTrue(idx.size in 3..200)
        assertEquals(0, idx.first())
        assertEquals(n - 1, idx.last())
        assertTrue(idx.toList() == idx.sorted())
        // mieści się w wiadomości przekaźnika (4 KB, po szyfrowaniu i base64)
        val enc = RelayCrypto.encrypt(ByteArray(32), LiveShare.encodePolyline(lats, lons, idx))
        assertTrue(enc.length < 3000)
        // krótka trasa bez zmian
        assertEquals(10, LiveShare.simplify(lats.copyOf(10), lons.copyOf(10), 200).size)
    }
}
