package pl.mati.carlauncher.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
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
 * Źródła: OpenStreetMap (Overpass, kilka serwerów równolegle), a gdy żaden nie odpowie – TomTom (jeśli jest klucz).
 * Szuka w pasie wzdłuż fragmentu trasy (nie wokół jednego punktu) i liczy odległość od odcinków trasy,
 * więc stacja tuż przy autostradzie jest znajdowana także tam, gdzie punkty trasy są co kilka km.
 */
object AlongRoute {

    /** [atM] – w którym miejscu trasy (m od startu), [offM] – jak daleko od trasy. */
    class Hit(val name: String, val lat: Double, val lon: Double, val atM: Double, val offM: Double, val rest: Boolean, val brand: String = "")

    private const val UA = "CarOS/1.1 (Android head unit launcher; pl.mati.carlauncher)"
    private val mirrors = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
    )
    @Volatile private var goodMirror = 0

    /** Fragment trasy, w którym szukamy: [targetM − backM, targetM + fwdM]; [kind] = "fuel" albo "rest". */
    class Window(val targetM: Double, val backM: Double, val fwdM: Double, val kind: String)

    /** Najlepsze miejsce w jednym oknie (zgodność ze starszym kodem). */
    fun find(
        lats: DoubleArray, lons: DoubleArray, cum: DoubleArray,
        targetM: Double, backM: Double, fwdM: Double, kind: String,
        tomtomKey: String?, allowTomTom: () -> Boolean = { true },
    ): Hit? = kotlinx.coroutines.runBlocking {
        findAll(lats, lons, cum, listOf(Window(targetM, backM, fwdM, kind)), 1, tomtomKey, allowTomTom).firstOrNull()?.firstOrNull()
    }

    /**
     * Wszystkie okna naraz – JEDNO zapytanie do OpenStreetMap (zamiast osobnego na każdy postój),
     * wysyłane równolegle do kilku serwerów (kolejny startuje po 2,5 s albo od razu po błędzie poprzedniego),
     * wygrywa pierwsza odpowiedź. Gdy żaden nie odpowie – TomTom (wszystkie okna równolegle).
     * Wynik: dla każdego okna do [perWindow] najlepszych miejsc (najlepsze pierwsze).
     * Rzuca [IOException], gdy żadne źródło nie odpowiedziało.
     */
    suspend fun findAll(
        lats: DoubleArray, lons: DoubleArray, cum: DoubleArray,
        windows: List<Window>, perWindow: Int,
        tomtomKey: String?, allowTomTom: () -> Boolean = { true },
        /** Tylko stacje z tym paliwem (tag OSM fuel:…): "diesel", "octane_95", "octane_98", "lpg"; null – wszystkie. */
        fuelTag: String? = null,
    ): List<List<Hit>> {
        val n = lats.size
        if (n < 2 || windows.isEmpty()) return windows.map { emptyList() }
        val total = cum[n - 1]
        class W(val w: Window, val lo: Double, val hi: Double, val from: Int, val to: Int)
        val ws = windows.map { w ->
            val lo = (w.targetM - w.backM).coerceIn(0.0, total)
            val hi = (w.targetM + w.fwdM).coerceIn(0.0, total)
            var from = cum.indexOfFirst { it >= lo }.coerceAtLeast(0)
            var to = cum.indexOfFirst { it >= hi }.let { if (it < 0) n - 1 else it }
            from = (from - 1).coerceAtLeast(0)
            to = (to + 1).coerceAtMost(n - 1)
            W(w, lo, hi, from, maxOf(to, from + 1).coerceAtMost(n - 1))
        }
        val cands: List<Cand> = try {
            val parts = StringBuilder()
            for (w in ws) {
                val line = lineOf(lats, lons, cum, w.from, w.to)
                parts.append(FUEL_Q.replace("#", line))
                if (w.w.kind == "rest") parts.append(REST_Q.replace("#", line))
            }
            overpass("[out:json][timeout:20];(" + parts + ");out center tags qt;", fuelTag)
        } catch (e: IOException) {
            val key = tomtomKey?.trim().orEmpty()
            if (key.isBlank()) throw e
            kotlinx.coroutines.coroutineScope {
                ws.map { w ->
                    async(kotlinx.coroutines.Dispatchers.IO) {
                        if (!allowTomTom()) emptyList()
                        else try { tomtom(key, lats, lons, cum, w.w.targetM, maxOf(w.w.backM, w.w.fwdM) + 3000, w.w.kind) } catch (_: Exception) { null }
                    }
                }.awaitAll()
            }.let { res -> if (res.all { it == null }) throw e else res.filterNotNull().flatten() }
        }
        return ws.map { w ->
            val maxOff = if (w.w.kind == "fuel") 1200.0 else 1600.0
            val scored = ArrayList<Pair<Double, Hit>>()
            for (c in cands) {
                if (w.w.kind == "fuel" && (c.rest || !c.fuelOk)) continue
                val (off, at) = project(c.lat, c.lon, lats, lons, cum, w.from, w.to)
                if (off > maxOff || at < w.lo - 500 || at > w.hi + 500) continue
                var score = off * 3 + abs(at - w.w.targetM) * 0.25
                if (w.w.kind == "fuel" && at > w.w.targetM) score += (at - w.w.targetM) * 1.5 // po końcu zasięgu – gorzej
                if (w.w.kind == "rest" && !c.rest) score += 2500                           // MOP lepszy niż sama stacja
                if (c.named) score -= 300
                scored += score to Hit(c.name, c.lat, c.lon, at, off, c.rest, c.brand)
            }
            // najlepsze, bez duplikatów (ten sam obiekt jako punkt i obszar, dwa wpisy tej samej stacji)
            val out = ArrayList<Hit>()
            for ((_, h) in scored.sortedBy { it.first }) {
                if (out.any { abs(it.atM - h.atM) < 250 && (it.name == h.name || abs(it.lat - h.lat) + abs(it.lon - h.lon) < 0.0015) }) continue
                out += h
                if (out.size >= perWindow) break
            }
            out
        }
    }

    // ---------- źródła ----------

    private const val FUEL_Q = "nwr[\"amenity\"=\"fuel\"][\"access\"!~\"^(private|no)$\"](around:1200,#);"
    private const val REST_Q = "nwr[\"highway\"~\"^(services|rest_area)$\"](around:1600,#);"

    private class Cand(val name: String, val lat: Double, val lon: Double, val rest: Boolean, val named: Boolean, val brand: String = "", val fuelOk: Boolean = true)

    /** Linia wzdłuż fragmentu trasy (punkt co ~800 m, najwyżej ~120 punktów). */
    private fun lineOf(lats: DoubleArray, lons: DoubleArray, cum: DoubleArray, from: Int, to: Int): String {
        val len = cum[to] - cum[from]
        val step = maxOf(800.0, len / 120)
        val line = StringBuilder()
        var last = -1e12
        for (i in from..to) {
            if (cum[i] - last >= step || i == to) {
                if (line.isNotEmpty()) line.append(',')
                line.append(String.format(Locale.US, "%.5f,%.5f", lats[i], lons[i]))
                last = cum[i]
            }
        }
        return line.toString()
    }

    private val racer = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    /** Zapytanie Overpass „na wyścigi” do kilku serwerów; pierwsza poprawna odpowiedź wygrywa. */
    private suspend fun overpass(q: String, fuelTag: String? = null): List<Cand> {
        val body = "data=" + URLEncoder.encode(q, "UTF-8")
        val res = kotlinx.coroutines.CompletableDeferred<JSONObject>()
        val started = java.util.concurrent.atomic.AtomicInteger(0)
        val failed = java.util.concurrent.atomic.AtomicInteger(0)
        val nM = mirrors.size
        val base = goodMirror
        var lastErr = "brak odpowiedzi"
        fun startNext() {
            val k = started.getAndIncrement()
            if (k >= nM || res.isCompleted) return
            racer.launch {
                val m = (base + k) % nM
                try {
                    val js = JSONObject(post(mirrors[m], body))
                    val els = js.optJSONArray("elements")
                    val remark = js.optString("remark")
                    if ((els == null || els.length() == 0) && (remark.contains("error", true) || remark.contains("timeout", true))) {
                        throw IOException("Overpass: $remark")
                    }
                    if (res.complete(js)) goodMirror = m
                } catch (e: Exception) {
                    lastErr = e.message ?: e.javaClass.simpleName
                    if (failed.incrementAndGet() >= nM) res.completeExceptionally(IOException(lastErr))
                    else startNext()
                }
            }
        }
        startNext()
        val hedge = racer.launch {
            repeat(nM - 1) { kotlinx.coroutines.delay(2500); startNext() }
        }
        val js = try {
            kotlinx.coroutines.withTimeoutOrNull(22_000) { res.await() } ?: throw IOException("serwery map nie odpowiedziały w 22 s")
        } finally {
            hedge.cancel()
        }
        val out = ArrayList<Cand>()
        val els = js.optJSONArray("elements") ?: return out
        for (i in 0 until els.length()) {
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
            // paliwo: stacja oznaczona „nie ma” – odpada; LPG tylko tam, gdzie jest oznaczone
            val tag = if (fuelTag == null) "" else t?.optString("fuel:$fuelTag").orEmpty()
            val fuelOk = when {
                fuelTag == null || isRest -> true
                fuelTag == "lpg" -> tag == "yes" || name.contains("LPG", true)
                else -> tag != "no"
            }
            out += Cand(label, lat, lon, isRest, name.isNotEmpty() || brand.isNotEmpty(), brand.ifEmpty { name }, fuelOk)
        }
        return out
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

    /** Odległość punktu od trasy (m) i miejsce na trasie (m od startu) – rzut na odcinki od from do to. */
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
    /** Odległość od trasy (m) i miejsce na trasie (m od startu) dla punktu, szukając tylko na odcinku [loM, hiM]. */
    fun nearRoute(lat: Double, lon: Double, lats: DoubleArray, lons: DoubleArray, cum: DoubleArray, loM: Double, hiM: Double): Pair<Double, Double> {
        val n = lats.size
        if (n < 2) return Double.MAX_VALUE to 0.0
        val from = (cum.indexOfFirst { it >= loM }.coerceAtLeast(0) - 1).coerceAtLeast(0)
        val to = (cum.indexOfFirst { it >= hiM }.let { if (it < 0) n - 1 else it } + 1).coerceAtMost(n - 1)
        return project(lat, lon, lats, lons, cum, from, maxOf(to, from + 1).coerceAtMost(n - 1))
    }

    fun positionOf(lat: Double, lon: Double, lats: DoubleArray, lons: DoubleArray, cum: DoubleArray): Double =
        if (lats.size < 2) 0.0 else project(lat, lon, lats, lons, cum, 0, lats.size - 1).second

    // ---------- HTTP ----------

    private fun post(url: String, body: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
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
