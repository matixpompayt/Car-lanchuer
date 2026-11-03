package pl.mati.carlauncher.data

import android.location.Location
import android.media.AudioManager
import android.media.ToneGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Ograniczenie prędkości z OpenStreetMap (Overpass).
 * Pobiera drogi w promieniu ~700 m (przed autem), dopasowuje auto do najbliższej drogi lokalnie co 1 s.
 */
object SpeedLimitRepository {

    /** Aktualne ograniczenie w km/h albo null, gdy nieznane. */
    val limit = MutableStateFlow<Int?>(null)
    val roadName = MutableStateFlow<String?>(null)
    /** Prędkość przekracza ograniczenie + tolerancję. */
    val over = MutableStateFlow(false)
    /** Ograniczenie szacowane z przepisów (droga bez znaku w OSM). */
    val estimated = MutableStateFlow(false)

    /** Znak / sygnalizacja na mapie. kind: signals, stop, give_way, crossing, rail, camera. */
    data class Sign(val id: Long, val kind: String, val lat: Double, val lon: Double)
    val signs = MutableStateFlow<List<Sign>>(emptyList())
    /** Stan pobierania danych OSM – do podglądu w ustawieniach. */
    val status = MutableStateFlow("Brak danych – czekam na GPS")

    class Way(val id: Long, val limit: Int?, val name: String?, val lat: DoubleArray, val lon: DoubleArray, val est: Boolean = false)
    private class Place(val lat: Double, val lon: Double, val radius: Double)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false
    @Volatile private var ways: List<Way> = emptyList()
    private var centerLat = 0.0
    private var centerLon = 0.0
    private var hasData = false
    private var lastFetch = 0L
    private var fetching = false
    private var currentWay: Long = -1
    private var unknownSince = 0L
    private var tone: ToneGenerator? = null

    private const val RADIUS = 700
    private const val REFETCH_M = 420.0
    private val mirrors = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
    )
    /** Serwer, który ostatnio odpowiedział – od niego zaczynamy. */
    @Volatile private var goodMirror = 0
    /** Drogi z poprzednich pobrań (id -> droga), żeby przerwa w internecie nie kasowała limitu. */
    private val cache = LinkedHashMap<Long, Way>()

    fun start() {
        if (started) return
        started = true
        scope.launch {
            while (isActive) {
                try { tick() } catch (_: Exception) {}
                delay(1000)
            }
        }
    }

    private fun tick() {
        if (!Prefs.speedLimits.value) {
            limit.value = null; over.value = false
            if (!Prefs.mapSigns.value) return
        }
        val l = LocationRepository.location.value ?: return
        // najpierw dane offline (plik z GitHuba); online tylko poza ich obszarem
        val off = try { OfflineSpeed.around(l.latitude, l.longitude) } catch (_: Exception) { null }
        if (off != null) {
            if (off !== lastOff) {
                lastOff = off
                ways = off.ways
                signs.value = off.signs
                val inf = OfflineSpeed.info.value
                val known = off.ways.count { !it.est }
                status.value = "Offline" + (inf?.let { " (dane z " + java.text.SimpleDateFormat("d.MM", Locale.ROOT).format(java.util.Date(it.builtAt)) + ")" } ?: "") +
                    " – ${off.ways.size} dróg w okolicy, $known ze znakiem"
            }
        } else {
            if (lastOff != null) { lastOff = null; hasData = false; ways = synchronized(cache) { cache.values.toList() } }
            onlineTick(l)
        }
        match(l)
        val speed = LocationRepository.gpsSpeed.value
        val lim = limit.value
        // sygnał dźwiękowy tylko przy znanym ograniczeniu (nie przy szacowanym)
        val isOver = lim != null && speed != null && speed > lim + Prefs.speedTolerance.value && !estimated.value
        if (isOver && !over.value && Prefs.speedBeep.value) beep()
        over.value = isOver
    }

    @Volatile private var lastOff: OfflineSpeed.Area? = null

    private fun onlineTick(l: Location) {
        val now = System.currentTimeMillis()
        val moved = if (hasData) RouteRepository.dist(l.latitude, l.longitude, centerLat, centerLon) else Double.MAX_VALUE
        if (!fetching && moved > REFETCH_M && now - lastFetch > 15_000) {
            fetching = true
            lastFetch = now
            if (!hasData) status.value = "Pobieram dane z OpenStreetMap…"
            scope.launch(Dispatchers.IO) {
                try { fetch(l) } catch (_: Exception) {} finally { fetching = false }
            }
        }
    }

    private fun match(l: Location) {
        val list = ways
        if (list.isEmpty()) return
        val kx = 111_320.0 * cos(Math.toRadians(l.latitude))
        val ky = 110_540.0
        val bearing = if (l.hasBearing() && l.hasSpeed() && l.speed > 2.5f) l.bearing.toDouble() else null
        var best: Way? = null
        var bestScore = Double.MAX_VALUE
        var curScore = Double.MAX_VALUE
        for (w in list) {
            var wBest = Double.MAX_VALUE
            for (i in 0 until w.lat.size - 1) {
                val ax = (w.lon[i] - l.longitude) * kx; val ay = (w.lat[i] - l.latitude) * ky
                val bx = (w.lon[i + 1] - l.longitude) * kx; val by = (w.lat[i + 1] - l.latitude) * ky
                val dx = bx - ax; val dy = by - ay
                val len2 = dx * dx + dy * dy
                val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
                val px = ax + t * dx; val py = ay + t * dy
                var d = sqrt(px * px + py * py)
                if (bearing != null && len2 > 1.0) {
                    val segB = (Math.toDegrees(Math.atan2(dx, dy)) + 360) % 360
                    var diff = abs(segB - bearing) % 180
                    if (diff > 90) diff = 180 - diff
                    if (diff > 40) d += 25
                }
                if (d < wBest) wBest = d
            }
            if (wBest < bestScore) { bestScore = wBest; best = w }
            if (w.id == currentWay) curScore = wBest
        }
        // trzymaj się obecnej drogi, chyba że inna jest wyraźnie bliżej (np. skrzyżowanie)
        val chosen = if (curScore < 30 && curScore <= bestScore + 8) list.first { it.id == currentWay } else if (bestScore < 30) best else null
        if (chosen == null) {
            if (unknownSince == 0L) unknownSince = System.currentTimeMillis()
            if (System.currentTimeMillis() - unknownSince > 8000) { limit.value = null; roadName.value = null; currentWay = -1 }
            return
        }
        unknownSince = 0L
        currentWay = chosen.id
        roadName.value = chosen.name
        limit.value = chosen.limit
        estimated.value = chosen.est
    }

    private fun fetch(l: Location) {
        // środek zapytania lekko przed autem, w kierunku jazdy
        var cLat = l.latitude
        var cLon = l.longitude
        if (l.hasBearing() && l.hasSpeed() && l.speed > 5f) {
            val r = Math.toRadians(l.bearing.toDouble())
            cLat += 300 * cos(r) / 110_540.0
            cLon += 300 * Math.sin(r) / (111_320.0 * cos(Math.toRadians(l.latitude)))
        }
        val q = String.format(
            Locale.US,
            "[out:json][timeout:20];" +
                "way(around:%1\$d,%2\$.6f,%3\$.6f)[highway~\"^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|road)(_link)?$\"];out tags geom qt;" +
                "node(around:%1\$d,%2\$.6f,%3\$.6f)[highway~\"^(traffic_signals|stop|give_way|crossing|speed_camera)$\"];out qt;" +
                "node(around:%1\$d,%2\$.6f,%3\$.6f)[railway=level_crossing];out qt;" +
                "node(around:3000,%2\$.6f,%3\$.6f)[place~\"^(city|town|village|hamlet|suburb|quarter|neighbourhood)$\"];out qt;",
            RADIUS, cLat, cLon,
        )
        var text: String? = null
        val errors = ArrayList<String>()
        for (k in mirrors.indices) {
            val idx = (goodMirror + k) % mirrors.size
            val m = mirrors[idx]
            text = try { post(m, "data=" + URLEncoder.encode(q, "UTF-8")) } catch (e: Exception) { errors += host(m) + ": " + (e.message ?: "błąd"); null }
            // serwer przeciążony potrafi zwrócić 200 z pustym wynikiem i komunikatem "remark"
            if (text != null && text.contains("\"remark\"") && !text.contains("\"geometry\"")) {
                errors += host(m) + ": przeciążony"; text = null
            }
            if (text != null) { goodMirror = idx; break }
        }
        if (text == null) {
            status.value = "Błąd pobierania (" + time() + ") – " + errors.joinToString("; ") + if (cache.isNotEmpty()) " · używam zapisanych ${cache.size} dróg" else ""
            lastFetch = System.currentTimeMillis() - 5_000 // ponów szybciej
            return
        }
        val arr = JSONObject(text).optJSONArray("elements") ?: return
        val out = ArrayList<Way>(arr.length())
        // najpierw miejscowości i znaki (węzły)
        val places = ArrayList<Place>()
        val signList = ArrayList<Sign>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("type") != "node") continue
            val t = o.optJSONObject("tags") ?: continue
            val la = o.optDouble("lat"); val lo = o.optDouble("lon")
            val place = t.optString("place")
            if (place.isNotBlank()) {
                val r = when (place) { "city" -> 3500.0; "town" -> 1800.0; "suburb", "quarter" -> 1200.0; "village" -> 700.0; "hamlet" -> 300.0; else -> 500.0 }
                places += Place(la, lo, r)
                continue
            }
            val kind = when {
                t.optString("railway") == "level_crossing" -> "rail"
                else -> when (t.optString("highway")) {
                    "traffic_signals" -> "signals"
                    "stop" -> "stop"
                    "give_way" -> "give_way"
                    "speed_camera" -> "camera"
                    "crossing" -> if (t.optString("crossing") in setOf("unmarked", "no", "informal", "traffic_signals")) null else "crossing"
                    else -> null
                }
            } ?: continue
            signList += Sign(o.optLong("id"), kind, la, lo)
        }
        if (lastOff == null) signs.value = signList
        val germany = isGermany(cLat, cLon)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val geom = o.optJSONArray("geometry") ?: continue
            if (geom.length() < 2) continue
            val tags = o.optJSONObject("tags") ?: JSONObject()
            val lat = DoubleArray(geom.length())
            val lon = DoubleArray(geom.length())
            for (k in 0 until geom.length()) {
                val g = geom.getJSONObject(k)
                lat[k] = g.getDouble("lat"); lon[k] = g.getDouble("lon")
            }
            val name = tags.optString("ref").ifBlank { null }?.let { ref -> tags.optString("name").ifBlank { null }?.let { "$ref · $it" } ?: ref }
                ?: tags.optString("name").ifBlank { null }
            val exact = limitOf(tags)
            if (exact != null) {
                out += Way(o.getLong("id"), exact, name, lat, lon)
            } else {
                val mid = lat.size / 2
                val urban = tags.optString("zone:traffic").contains("urban", true) ||
                    tags.optString("highway") == "residential" ||
                    places.any { RouteRepository.dist(lat[mid], lon[mid], it.lat, it.lon) < it.radius }
                out += Way(o.getLong("id"), defaultLimit(tags, urban, germany), name, lat, lon, est = true)
            }
        }
        // scal z wcześniejszymi i wyrzuć drogi dalej niż 6 km
        synchronized(cache) {
            for (w in out) cache[w.id] = w
            val it = cache.values.iterator()
            while (it.hasNext()) {
                val w = it.next()
                if (RouteRepository.dist(w.lat[0], w.lon[0], cLat, cLon) > 6000) it.remove()
            }
            if (lastOff == null) ways = cache.values.toList()
        }
        val known = out.count { !it.est }
        if (lastOff == null) status.value = "OK (" + time() + ", " + host(mirrors[goodMirror]) + ") – ${out.size} dróg, $known ze znakiem, ${out.size - known} wg przepisów"
        centerLat = cLat
        centerLon = cLon
        hasData = true
    }

    /**
     * Gdy droga nie ma znaku w OSM – ograniczenie z przepisów:
     * PL: teren zabudowany 50, poza 90, ekspresowa 120 (jednojezdniowa 100), autostrada 140;
     * DE: w mieście 50, poza 100, autostrada bez limitu (nie pokazujemy).
     */
    private fun defaultLimit(t: JSONObject, urban: Boolean, germany: Boolean): Int? {
        val hw = t.optString("highway").removeSuffix("_link")
        val ref = t.optString("ref")
        val oneway = t.optString("oneway") == "yes"
        if (hw == "living_street") return if (germany) 7 else 20
        if (hw == "motorway") return if (germany) null else 140
        val expressway = !germany && (t.optString("motorroad") == "yes" || ref.startsWith("S"))
        if (expressway) return if (oneway) 120 else 100
        if (urban) return 50
        if (germany && t.optString("motorroad") == "yes") return 100
        return if (germany) 100 else 90
    }

    /** Czy punkt jest po niemieckiej stronie granicy (Odra / Nysa Łużycka – w przybliżeniu). */
    fun isGermany(lat: Double, lon: Double): Boolean {
        if (lon < 13.9) return true
        if (lon > 15.1) return false
        val border = listOf(
            54.10 to 14.22, 53.75 to 14.27, 53.45 to 14.40, 53.25 to 14.44, 53.05 to 14.30, 52.90 to 14.14,
            52.75 to 14.30, 52.65 to 14.50, 52.58 to 14.64, 52.35 to 14.55, 52.15 to 14.68, 51.95 to 14.72,
            51.55 to 14.73, 51.30 to 14.97, 51.15 to 14.99, 50.85 to 14.82,
        )
        val sorted = border.sortedBy { it.first }
        val b = when {
            lat <= sorted.first().first -> sorted.first().second
            lat >= sorted.last().first -> sorted.last().second
            else -> {
                val k = sorted.indexOfFirst { it.first >= lat }
                val (a1, o1) = sorted[k - 1]
                val (a2, o2) = sorted[k]
                o1 + (o2 - o1) * (lat - a1) / (a2 - a1)
            }
        }
        return lon < b
    }

    /** maxspeed z tagów OSM (+ polskie wartości domyślne). */
    private fun limitOf(t: JSONObject): Int? {
        fun parse(v: String?): Int? {
            if (v.isNullOrBlank()) return null
            val s = v.trim()
            s.toIntOrNull()?.let { return it }
            if (s.endsWith("mph")) return s.removeSuffix("mph").trim().toIntOrNull()?.let { (it * 1.609).toInt() }
            return when (s.uppercase(Locale.ROOT)) {
                "PL:URBAN" -> 50
                "PL:RURAL" -> 90
                "PL:EXPRESSWAY" -> 120
                "PL:MOTORWAY" -> 140
                "PL:LIVING_STREET", "PL:ZONE20" -> 20
                "PL:ZONE30" -> 30
                "DE:URBAN" -> 50
                "DE:RURAL" -> 100
                "DE:LIVING_STREET" -> 7
                else -> Regex("""(\d{2,3})""").find(s)?.value?.toIntOrNull()
            }
        }
        return parse(t.optString("maxspeed").ifBlank { null })
            ?: parse(t.optString("maxspeed:forward").ifBlank { null })
            ?: parse(t.optString("zone:maxspeed").ifBlank { null })
            ?: parse(t.optString("maxspeed:type").ifBlank { null })
            ?: parse(t.optString("source:maxspeed").ifBlank { null })
            ?: null
    }

    private fun host(u: String) = try { URL(u).host } catch (_: Exception) { u }
    private fun time() = java.text.SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(java.util.Date())

    private fun beep() {
        try {
            val tg = tone ?: ToneGenerator(AudioManager.STREAM_MUSIC, 90).also { tone = it }
            tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
        } catch (_: Exception) {}
    }

    private fun post(url: String, body: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 22_000
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("User-Agent", "CarOS/1.1 (Android head unit launcher; pl.mati.carlauncher)")
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        try {
            c.outputStream.use { it.write(body.toByteArray()) }
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}" + if (c.responseCode == 429) " (limit zapytań)" else if (c.responseCode == 504) " (przeciążony)" else "")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
