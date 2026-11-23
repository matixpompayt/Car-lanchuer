package pl.mati.caros.core

import java.security.SecureRandom
import java.util.Base64
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToLong

/**
 * Jazda na żywo przez link: strona [PAGE] (GitHub Pages) czyta zaszyfrowane wiadomości z przekaźnika.
 * Klucz jest tylko w linku po „#” – nie trafia do żadnego serwera, przekaźnik widzi sam szyfrogram.
 * Kanał: [RelayCrypto.topicOf] (klucz, "share"). Wiadomości (JSON, szyfrowane jak reszta – [RelayCrypto.encrypt]):
 *  - "sh"  – pozycja: lat, lon, spd (km/h), brg, t (ms), m (w ruchu), who, until, dest {n, lat, lon}, eta (ms), rem (m),
 *  - "shr" – trasa: id, pl (polyline, dokładność 1e-5), dest, stops [{n, lat, lon}],
 *  - "she" – koniec: t, why ("arrived" | "stopped" | "expired").
 */
object LiveShare {
    const val PAGE = "https://matixpompayt.github.io/Car-lanchuer/live/"
    const val CHANNEL = "share"
    const val POS = "sh"
    const val ROUTE = "shr"
    const val END = "she"

    private val rnd = SecureRandom()

    fun newKey(): ByteArray = ByteArray(32).also { rnd.nextBytes(it) }

    fun keyText(key: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(key)

    fun keyFrom(text: String?): ByteArray? = try {
        Base64.getUrlDecoder().decode(text?.trim().orEmpty()).takeIf { it.size == 32 }
    } catch (_: Exception) {
        null
    }

    fun link(key: ByteArray): String = PAGE + "#" + keyText(key)

    /** Klucz z linku (część po „#”). */
    fun keyFromLink(link: String): ByteArray? = keyFrom(link.substringAfter('#', "").substringBefore('&'))

    fun topic(key: ByteArray): String = RelayCrypto.topicOf(key, CHANNEL)

    /** Polyline Google (dokładność 1e-5) – trasa w małej wiadomości. */
    fun encodePolyline(lats: DoubleArray, lons: DoubleArray, idx: IntArray = IntArray(lats.size) { it }): String {
        val sb = StringBuilder()
        var pLat = 0L
        var pLon = 0L
        for (i in idx) {
            val la = (lats[i] * 1e5).roundToLong()
            val lo = (lons[i] * 1e5).roundToLong()
            enc(la - pLat, sb)
            enc(lo - pLon, sb)
            pLat = la
            pLon = lo
        }
        return sb.toString()
    }

    private fun enc(v: Long, sb: StringBuilder) {
        var x = if (v < 0) (v shl 1).inv() else v shl 1
        while (x >= 0x20) {
            sb.append(((0x20 or (x and 0x1f).toInt()) + 63).toChar())
            x = x shr 5
        }
        sb.append((x + 63).toInt().toChar())
    }

    fun decodePolyline(s: String): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        var i = 0
        var lat = 0L
        var lon = 0L
        fun next(): Long {
            var shift = 0
            var res = 0L
            while (true) {
                val b = s[i++].code - 63
                res = res or ((b and 0x1f).toLong() shl shift)
                shift += 5
                if (b < 0x20) break
            }
            return if (res and 1L != 0L) (res shr 1).inv() else res shr 1
        }
        while (i < s.length) {
            lat += next()
            lon += next()
            out += lat / 1e5 to lon / 1e5
        }
        return out
    }

    /**
     * Uproszczenie linii (Douglas–Peucker) do najwyżej [maxPts] punktów – tolerancja rośnie, aż się zmieści.
     * Zwraca indeksy zachowanych punktów (zawsze z pierwszym i ostatnim).
     */
    fun simplify(lats: DoubleArray, lons: DoubleArray, maxPts: Int = 220): IntArray {
        val n = lats.size
        if (n <= maxPts) return IntArray(n) { it }
        var tol = 8.0
        var keep = dp(lats, lons, tol)
        while (keep.size > maxPts && tol < 20_000) {
            tol *= 1.6
            keep = dp(lats, lons, tol)
        }
        return keep
    }

    private fun dp(lats: DoubleArray, lons: DoubleArray, tolM: Double): IntArray {
        val n = lats.size
        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to n - 1)
        val kx = 111_320.0 * cos(Math.toRadians(lats[n / 2]))
        val ky = 110_540.0
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            if (b <= a + 1) continue
            val ax = lons[a] * kx; val ay = lats[a] * ky
            val bx = lons[b] * kx; val by = lats[b] * ky
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            var best = -1.0
            var bi = -1
            for (i in a + 1 until b) {
                val px = lons[i] * kx - ax; val py = lats[i] * ky - ay
                val d = if (len2 == 0.0) kotlin.math.sqrt(px * px + py * py)
                else abs(px * dy - py * dx) / kotlin.math.sqrt(len2)
                if (d > best) { best = d; bi = i }
            }
            if (best > tolM && bi > 0) {
                keep[bi] = true
                stack.addLast(a to bi)
                stack.addLast(bi to b)
            }
        }
        return (0 until n).filter { keep[it] }.toIntArray()
    }
}
