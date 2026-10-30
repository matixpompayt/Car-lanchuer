package pl.mati.carlauncher.data

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** Ślad GPS: upraszczanie (Douglas–Peucker) i kodowanie (format Google, precyzja 1e-5). */
object Polyline {

    fun encode(pts: List<DoubleArray>): String {
        val sb = StringBuilder()
        var pLat = 0L
        var pLon = 0L
        for (p in pts) {
            val lat = (p[0] * 1e5).roundToLong()
            val lon = (p[1] * 1e5).roundToLong()
            enc(lat - pLat, sb)
            enc(lon - pLon, sb)
            pLat = lat
            pLon = lon
        }
        return sb.toString()
    }

    private fun enc(v: Long, sb: StringBuilder) {
        var x = if (v < 0) (v shl 1).inv() else v shl 1
        while (x >= 0x20) {
            sb.append(((0x20L or (x and 0x1f)) + 63).toInt().toChar())
            x = x shr 5
        }
        sb.append((x + 63).toInt().toChar())
    }

    fun decode(s: String): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        var i = 0
        var lat = 0L
        var lon = 0L
        while (i < s.length) {
            for (k in 0..1) {
                var shift = 0
                var result = 0L
                while (true) {
                    val b = s[i++].code - 63
                    result = result or ((b and 0x1f).toLong() shl shift)
                    shift += 5
                    if (b < 0x20) break
                }
                val d = if (result and 1L != 0L) (result shr 1).inv() else result shr 1
                if (k == 0) lat += d else lon += d
            }
            out += doubleArrayOf(lat / 1e5, lon / 1e5)
        }
        return out
    }

    /** Upraszcza ślad z tolerancją w metrach. */
    fun simplify(pts: List<DoubleArray>, tolM: Double): List<DoubleArray> {
        if (pts.size < 3) return pts
        val keep = BooleanArray(pts.size)
        keep[0] = true
        keep[pts.size - 1] = true
        val kx = 111_320.0 * cos(Math.toRadians(pts[0][0]))
        val ky = 110_540.0
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, pts.size - 1))
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast().let { it[0] to it[1] }
            var maxD = 0.0
            var idx = -1
            val ax = pts[a][1] * kx; val ay = pts[a][0] * ky
            val bx = pts[b][1] * kx; val by = pts[b][0] * ky
            val dx = bx - ax; val dy = by - ay
            val len = sqrt(dx * dx + dy * dy)
            for (i in a + 1 until b) {
                val px = pts[i][1] * kx; val py = pts[i][0] * ky
                val d = if (len < 1e-6) sqrt((px - ax) * (px - ax) + (py - ay) * (py - ay))
                else abs(dy * px - dx * py + bx * ay - by * ax) / len
                if (d > maxD) { maxD = d; idx = i }
            }
            if (idx >= 0 && maxD > tolM) {
                keep[idx] = true
                stack.addLast(intArrayOf(a, idx))
                stack.addLast(intArrayOf(idx, b))
            }
        }
        return pts.filterIndexed { i, _ -> keep[i] }
    }

    /** Kodowany ślad nie dłuższy niż [maxChars] (stopniowo większa tolerancja). */
    fun compact(pts: List<DoubleArray>, maxChars: Int = 2400): String {
        var tol = 6.0
        var s = encode(simplify(pts, tol))
        while (s.length > maxChars && tol < 500) {
            tol *= 1.6
            s = encode(simplify(pts, tol))
        }
        return s
    }
}
