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

    private class Way(val id: Long, val limit: Int?, val name: String?, val lat: DoubleArray, val lon: DoubleArray)

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
        "https://overpass.kumi.systems/api/interpreter",
    )

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
            limit.value = null; over.value = false; return
        }
        val l = LocationRepository.location.value ?: return
        val now = System.currentTimeMillis()
        val moved = if (hasData) RouteRepository.dist(l.latitude, l.longitude, centerLat, centerLon) else Double.MAX_VALUE
        if (!fetching && moved > REFETCH_M && now - lastFetch > 15_000) {
            fetching = true
            lastFetch = now
            scope.launch(Dispatchers.IO) {
                try { fetch(l) } catch (_: Exception) {} finally { fetching = false }
            }
        }
        match(l)
        val speed = LocationRepository.gpsSpeed.value
        val lim = limit.value
        val isOver = lim != null && speed != null && speed > lim + Prefs.speedTolerance.value
        if (isOver && !over.value && Prefs.speedBeep.value) beep()
        over.value = isOver
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
            "[out:json][timeout:20];way(around:%d,%.6f,%.6f)[highway~\"^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|road)(_link)?$\"];out tags geom qt;",
            RADIUS, cLat, cLon,
        )
        var text: String? = null
        for (m in mirrors) {
            text = try { post(m, "data=" + URLEncoder.encode(q, "UTF-8")) } catch (_: Exception) { null }
            if (text != null) break
        }
        if (text == null) return
        val arr = JSONObject(text).optJSONArray("elements") ?: return
        val out = ArrayList<Way>(arr.length())
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
            out += Way(o.getLong("id"), limitOf(tags), name, lat, lon)
        }
        ways = out
        centerLat = cLat
        centerLon = cLon
        hasData = true
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
            ?: when (t.optString("highway")) {
                "motorway" -> 140
                "living_street" -> 20
                else -> null
            }
    }

    private fun beep() {
        try {
            val tg = tone ?: ToneGenerator(AudioManager.STREAM_MUSIC, 90).also { tone = it }
            tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
        } catch (_: Exception) {}
    }

    private fun post(url: String, body: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 25_000
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("User-Agent", "CarOS/1.1 (Android head unit launcher; pl.mati.carlauncher)")
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        try {
            c.outputStream.use { it.write(body.toByteArray()) }
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
