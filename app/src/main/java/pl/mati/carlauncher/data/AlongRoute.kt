package pl.mati.carlauncher.data

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Stacje paliw i miejsca na przerwę przy samej trasie.
 *
 * Źródła: OpenStreetMap (Overpass, kilka serwerów po kolei), a gdy żaden nie odpowie – TomTom (jeśli jest klucz).
 * Szuka w pasie wzdłuż fragmentu trasy (nie wokół jednego punktu) i liczy odległość od odcinków trasy,
 * więc stacja tuż przy autostradzie jest znajdowana także tam, gdzie punkty trasy są co kilka km.
 */
object AlongRoute {

    /** [atM] – w którym miejscu trasy (m od startu), [offM] – jak daleko od trasy. */
    class Hit(val name: String, val lat: Double, val lon: Double, val atM: Double, val offM: Double, val rest: Boolean)

    private const val UA = "CarOS/1.1 (Android head unit launcher; pl.mati.carlauncher)"
    private val mirrors = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
    )
    @Volatile private var goodMirror = 0

    /**
     * Najlepsze miejsce w oknie [targetM − backM, targetM + fwdM] trasy.
     * [kind] = "fuel" (stacja) albo "rest" (MOP / miejsce obsługi podróżnych, w razie braku – stacja).
     * Zwraca null, gdy nic nie ma przy trasie; rzuca [IOException], gdy żadne źródło nie odpowiedziało.
     */
    fun find(
        lats: DoubleArray, lons: DoubleArray, cum: DoubleArray,
        targetM: Double, backM: Double, fwdM: Double, kind: String,
        tomtomKey: String?, allowTomTom: () -> Boolean = { true },
    ): Hit? {
        val n = lats.size
        if (n < 2) return null
        val total = cum[n - 1]
        val lo = (targetM - backM).coerceIn(0.0, total)
        val hi = (targetM + fwdM).coerceIn(0.0, total)
        var from = cum.indexOfFirst { it >= lo }.coerceAtLeast(0)
        var to = cum.indexOfFirst { it >= hi }.let { if (it < 0) n - 1 else it }
        from = (from - 1).coerceAtLeast(0)
        to = (to + 1).coerceAtMost(n - 1)
        if (to <= from) return null

        val maxOff = if (kind == "fuel") 1200.0 else 1600.0
        val cands: List<Cand> = try {
            overpass(lats, lons, cum, from, to, kind)
        } catch (e: IOException) {
            val key = tomtomKey?.trim().orEmpty()
            if (key.isBlank() || !allowTomTom()) throw e
            tomtom(key, lats, lons, cum, targetM, maxOf(backM, fwdM) + 3000, kind)
        }
        var best: Hit? = null
        var bestScore = Double.MAX_VALUE
        for (c in cands) {
            val (off, at) = project(c.lat, c.lon, lats, lons, cum, from, to)
            if (off > maxOff || at < lo - 500 || at > hi + 500) continue
            var score = off * 3 + abs(at - targetM) * 0.25
            if (kind == "fuel" && at > targetM) score += (at - targetM) * 1.5 // po końcu zasięgu – gorzej
            if (kind == "rest" && !c.rest) score += 2500                       // MOP lepszy niż sama stacja
            if (c.named) score -= 300
            if (score < bestScore) {
                bestScore = score
                best = Hit(c.name, c.lat, c.lon, at, off, c.rest)
            }
        }
        return best
    }

    // ---------- źródła ----------

    private class Cand(val name: String, val lat: Double, val lon: Double, val rest: Boolean, val named: Boolean)

    private fun overpass(lats: DoubleArray, lons: DoubleArray, cum: DoubleArray, from: Int, to: Int, kind: String): List<Cand> {
        // linia wzdłuż fragmentu trasy (punkt co ~700 m, najwyżej ~160 punktów)
        val len = cum[to] - cum[from]
        val step = maxOf(700.0, len / 160)
        val line = StringBuilder()
        var last = -1e12
        for (i in from..to) {
            if (cum[i] - last >= step || i == to) {
                if (line.isNotEmpty()) line.append(',')
                line.append(String.format(Locale.US, "%.5f,%.5f", lats[i], lons[i]))
                last = cum[i]
            }
        }
        val fuel = "nwr[\"amenity\"=\"fuel\"][\"access\"!~\"^(private|no|customers)$\"](around:1200,$line);"
        val rest = "nwr[\"highway\"~\"^(services|rest_area)$\"](around:1600,$line);"
        val q = "[out:json][timeout:25];(" + (if (kind == "fuel") fuel else rest + fuel) + ");out center tags 120;"
        val body = "data=" + URLEncoder.encode(q, "UTF-8")
        var err: IOException? = null
        for (k in mirrors.indices) {
            val m = (goodMirror + k) % mirrors.size
            try {
                val js = JSONObject(post(mirrors[m], body))
                val els = js.optJSONArray("elements")
                val remark = js.optString("remark")
                if ((els == null || els.length() == 0) && (remark.contains("error", true) || remark.contains("timeout", true))) {
                    throw IOException("Overpass: $remark")
                }
                goodMirror = m
                val out = ArrayList<Cand>()
                if (els != null) for (i in 0 until els.length()) {
                    val e = els.getJSONObject(i)
                    val c = e.optJSONObject("center")
                    val lat = if (e.has("lat")) e.optDouble("lat") else c?.optDouble("lat") ?: Double.NaN
                    val lon = if (e.has("lon")) e.optDouble("lon") else c?.optDouble("lon") ?: Double.NaN
                    if (lat.isNaN() || lon.isNaN()) continue
                    val t = e.optJSONObject("tags")
                    val hw = t?.optString("highway").orEmpty()
                    val isRest = hw == "services" || hw == "rest_area"
                    val brand = t?.optString("brand")?.trim().orEmpty()
                    val name = t?.optString("name")?.trim().orEmpty()
                    val label = when {
                        brand.isNotEmpty() && name.isNotEmpty() && !name.contains(brand, true) -> "$brand $name"
                        name.isNotEmpty() -> name
                        brand.isNotEmpty() -> brand
                        hw == "services" -> "Miejsce obsługi podróżnych"
                        hw == "rest_area" -> "Parking (MOP)"
                        else -> "Stacja paliw"
                    }
                    out += Cand(label, lat, lon, isRest, name.isNotEmpty() || brand.isNotEmpty())
                }
                return out
            } catch (e: Exception) {
                err = e as? IOException ?: IOException(e.message ?: "Overpass")
            }
        }
        throw err ?: IOException("Overpass")
    }

    private fun tomtom(key: String, lats: DoubleArray, lons: DoubleArray, cum: DoubleArray, targetM: Double, radius: Double, kind: String): List<Cand> {
        val i = cum.indexOfFirst { it >= targetM }.let { if (it < 0) lats.size - 1 else it }
        val cats = if (kind == "fuel") "7311" else "7395,7311"
        val url = String.format(
            Locale.US,
            "https://api.tomtom.com/search/2/nearbySearch/.json?key=%s&lat=%.6f&lon=%.6f&radius=%d&limit=100&categorySet=%s&language=pl-PL",
            URLEncoder.encode(key, "UTF-8"), lats[i], lons[i], radius.toInt().coerceIn(2000, 50_000), cats,
        )
        val res = JSONObject(get(url)).optJSONArray("results") ?: return emptyList()
        val out = ArrayList<Cand>()
        for (k in 0 until res.length()) {
            val r = res.getJSONObject(k)
            val pos = r.optJSONObject("position") ?: continue
            val poi = r.optJSONObject("poi")
            val name = poi?.optString("name")?.trim().orEmpty()
            val brand = poi?.optJSONArray("brands")?.optJSONObject(0)?.optString("name")?.trim().orEmpty()
            val catIds = poi?.optJSONArray("categorySet")
            var isRest = false
            if (catIds != null) for (j in 0 until catIds.length()) if (catIds.optJSONObject(j)?.optInt("id") == 7395) isRest = true
            val label = when {
                brand.isNotEmpty() && name.isNotEmpty() && !name.contains(brand, true) -> "$brand $name"
                name.isNotEmpty() -> name
                brand.isNotEmpty() -> brand
                isRest -> "Parking (MOP)"
                else -> "Stacja paliw"
            }
            out += Cand(label, pos.optDouble("lat"), pos.optDouble("lon"), isRest, name.isNotEmpty())
        }
        return out
    }

    // ---------- geometria ----------

    /** Odległość punktu od trasy (m) i miejsce na trasie (m od startu) – rzut na odcinki [from..to]. */
    private fun project(lat: Double, lon: Double, lats: DoubleArray, lons: DoubleArray, cum: DoubleArray, from: Int, to: Int): Pair<Double, Double> {
        val ky = 111_320.0
        val kx = ky * cos(Math.toRadians(lat))
        var best = Double.MAX_VALUE
        var at = cum[from]
        for (k in from until to) {
            val ax = (lons[k] - lon) * kx
            val ay = (lats[k] - lat) * ky
            val dx = (lons[k + 1] - lon) * kx - ax
            val dy = (lats[k + 1] - lat) * ky - ay
            val l2 = dx * dx + dy * dy
            val t = if (l2 < 1e-6) 0.0 else (-(ax * dx + ay * dy) / l2).coerceIn(0.0, 1.0)
            val px = ax + t * dx
            val py = ay + t * dy
            val d = sqrt(px * px + py * py)
            if (d < best) {
                best = d
                at = cum[k] + t * (cum[k + 1] - cum[k])
            }
        }
        return best to at
    }

    /** Miejsce punktu na trasie (m od startu) – do wstawienia postoju we właściwej kolejności. */
    fun positionOf(lat: Double, lon: Double, lats: DoubleArray, lons: DoubleArray, cum: DoubleArray): Double =
        if (lats.size < 2) 0.0 else project(lat, lon, lats, lons, cum, 0, lats.size - 1).second

    // ---------- HTTP ----------

    private fun post(url: String, body: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 30_000
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", "*/*")
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        try {
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", "application/json")
        try {
            val code = c.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
