package pl.mati.carlauncher.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Inteligentne rozplanowanie trasy w CAR OS (jak w CAR OS Connect):
 *  – omijanie korków i zamkniętych dróg: trasa TomTom (z ruchem na żywo) jako dodatkowy wariant,
 *    automatyczny wybór najszybszego bez zamknięć, a w czasie jazdy propozycja szybszej trasy,
 *  – kolejność przystanków (najszybsza, cel na końcu),
 *  – najlepsza godzina wyjazdu (prognoza ruchu co 15 min przez 3 h),
 *  – tankowanie i przerwy (stacja przed końcem zasięgu, przerwa co ok. 2 h).
 */
object SmartRoute {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ---------- pomocnicze ----------

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", "CarOS")
        try {
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun d(a: GeoPoint, b: GeoPoint) = RouteRepository.dist(a.latitude, a.longitude, b.latitude, b.longitude)

    /** Średnia odległość punktów [a] od trasy [b] (m). */
    fun shapeDist(a: List<GeoPoint>, b: List<GeoPoint>): Double {
        if (a.size < 2 || b.size < 2) return Double.MAX_VALUE
        val step = maxOf(1, b.size / 600)
        val n = 20
        var sum = 0.0
        for (k in 1..n) {
            val p = a[(a.size - 1) * k / (n + 1)]
            var m = Double.MAX_VALUE
            var i = 0
            while (i < b.size) { val x = d(p, b[i]); if (x < m) m = x; i += step }
            sum += m
        }
        return sum / n
    }

    /** 3 punkty kształtujące trasę (25/50/75 %). */
    fun shapingOf(pts: List<GeoPoint>): List<RouteRepository.Place> {
        val n = pts.size
        return listOf(n / 4, n / 2, 3 * n / 4).distinct().map { RouteRepository.Place("", "", pts[it].latitude, pts[it].longitude) }
    }

    private fun parsePoints(route: JSONObject): List<GeoPoint> {
        val out = ArrayList<GeoPoint>()
        val legs = route.getJSONArray("legs")
        for (i in 0 until legs.length()) {
            val p = legs.getJSONObject(i).getJSONArray("points")
            for (k in 0 until p.length()) { val o = p.getJSONObject(k); out += GeoPoint(o.getDouble("latitude"), o.getDouble("longitude")) }
        }
        return out
    }

    // ---------- trasa TomTom (omijanie korków) ----------

    data class TtRoute(val points: List<GeoPoint>, val travelS: Int, val delayS: Int, val lengthM: Double, val closures: Int)

    /** Najszybsza trasa z ruchem na żywo (TomTom sam omija zamknięcia i duże korki). */
    fun tomtomRoute(from: GeoPoint, to: GeoPoint): TtRoute? {
        val key = Prefs.tomtomKey.value.trim()
        if (key.isBlank() || !TrafficRepository.count()) return null
        return try {
            val url = String.format(
                Locale.US,
                "https://api.tomtom.com/routing/1/calculateRoute/%.6f,%.6f:%.6f,%.6f/json?key=%s&traffic=true&travelMode=car&routeType=fastest" +
                    "&sectionType=traffic&routeRepresentation=polyline",
                from.latitude, from.longitude, to.latitude, to.longitude, URLEncoder.encode(key, "UTF-8"),
            )
            val r = JSONObject(get(url)).getJSONArray("routes").getJSONObject(0)
            val sum = r.getJSONObject("summary")
            var closures = 0
            r.optJSONArray("sections")?.let { s -> for (i in 0 until s.length()) if (s.getJSONObject(i).optString("simpleCategory") == "ROAD_CLOSURE") closures++ }
            TtRoute(parsePoints(r), sum.optInt("travelTimeInSeconds"), sum.optInt("trafficDelayInSeconds"), sum.optDouble("lengthInMeters"), closures)
        } catch (_: Exception) {
            null
        }
    }

    /** Planowanie: trasa TomTom jako dodatkowy wariant, gdy jedzie inną drogą niż warianty OSRM. */
    fun addTomTomCandidate(from: GeoPoint) {
        if (!TrafficRepository.enabled || !Prefs.trafficEta.value) return
        scope.launch {
            val p = RouteRepository.plan.value ?: return@launch
            if (p.stops.isNotEmpty() || p.shape.isNotEmpty() || p.alternatives.isEmpty()) return@launch
            val tt = tomtomRoute(from, GeoPoint(p.dest.lat, p.dest.lon)) ?: return@launch
            if (tt.points.size < 10) return@launch
            if (p.alternatives.any { shapeDist(tt.points, it.points) < 150 }) return@launch
            RouteRepository.addShapedAlternative(p.dest, shapingOf(tt.points))
        }
    }

    // ---------- szybsza trasa w czasie jazdy ----------

    data class Offer(val routeId: Long, val savedS: Int, val shaping: List<RouteRepository.Place>, val closure: Boolean)

    /** Propozycja szybszej trasy (baner w CAR OS). */
    val offer = MutableStateFlow<Offer?>(null)
    private val declined = HashSet<Long>()

    /** Co kilka minut w czasie jazdy: czy TomTom ma trasę wyraźnie szybszą niż obecna (z korkami). */
    fun checkFaster(r: RouteRepository.Route, currentS: Double?, here: GeoPoint?, closureAhead: Boolean) {
        if (here == null || currentS == null || r.id in declined || r.stops.isNotEmpty()) return
        if (offer.value?.routeId == r.id) return
        val tt = tomtomRoute(here, GeoPoint(r.dest.lat, r.dest.lon)) ?: return
        if (tt.closures > 0 || tt.points.size < 10) return
        val saved = (currentS - tt.travelS).roundToInt()
        // co najmniej 5 min i 8 % szybciej, a inną drogą – albo obecna ma zamknięcie przed nami
        val better = (saved >= 300 && saved >= currentS * 0.08) || (closureAhead && saved >= 0)
        if (!better || shapeDist(tt.points, r.points) < 150) return
        offer.value = Offer(r.id, saved.coerceAtLeast(0), shapingOf(tt.points), closureAhead)
    }

    fun acceptOffer() {
        val o = offer.value ?: return
        offer.value = null
        RouteRepository.switchToShaped(o.shaping)
    }

    fun declineOffer() {
        offer.value?.let { declined += it.routeId }
        offer.value = null
    }

    // ---------- kolejność przystanków ----------

    /** Najszybsza kolejność przystanków (cel na końcu) i ile szybciej (s). */
    suspend fun optimizeStops(from: GeoPoint, stops: List<RouteRepository.Place>, dest: RouteRepository.Place): Pair<List<RouteRepository.Place>, Int>? =
        withContext(Dispatchers.IO) {
            if (stops.size < 2) return@withContext null
            try {
                val all = listOf(from) + stops.map { GeoPoint(it.lat, it.lon) } + GeoPoint(dest.lat, dest.lon)
                val coords = all.joinToString(";") { String.format(Locale.US, "%.6f,%.6f", it.longitude, it.latitude) }
                val trip = JSONObject(get("https://router.project-osrm.org/trip/v1/driving/$coords?source=first&destination=last&roundtrip=false&overview=false"))
                if (trip.optString("code") != "Ok") return@withContext null
                val wps = trip.getJSONArray("waypoints")
                val order = (1 until wps.length() - 1).sortedBy { wps.getJSONObject(it).getInt("waypoint_index") }
                val newStops = order.map { stops[it - 1] }
                val newS = trip.getJSONArray("trips").getJSONObject(0).getDouble("duration")
                val cur = JSONObject(get("https://router.project-osrm.org/route/v1/driving/$coords?overview=false"))
                val curS = cur.getJSONArray("routes").getJSONObject(0).getDouble("duration")
                newStops to (curS - newS).roundToInt()
            } catch (_: Exception) {
                null
            }
        }

    // ---------- najlepsza godzina wyjazdu ----------

    data class Slot(val depart: Long, val travelS: Int)

    /** Czas przejazdu trasą [r] przy wyjeździe co 15 min przez 3 h (prognoza ruchu TomTom). */
    suspend fun departures(r: RouteRepository.Route): List<Slot> = withContext(Dispatchers.IO) {
        val key = Prefs.tomtomKey.value.trim()
        if (key.isBlank()) return@withContext emptyList()
        val step = maxOf(1, r.points.size / 900)
        val sp = r.points.filterIndexed { i, _ -> i % step == 0 || i == r.points.lastIndex }
        val body = JSONObject().put("supportingPoints", JSONArray().apply { sp.forEach { put(JSONObject().put("latitude", it.latitude).put("longitude", it.longitude)) } })
        val a = sp.first(); val b = sp.last()
        val base = (System.currentTimeMillis() / 60_000) * 60_000
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        coroutineScope {
            (0..12).map { k ->
                async {
                    val t = base + k * 15 * 60_000L
                    if (!TrafficRepository.count()) return@async null
                    try {
                        val at = if (k == 0) "" else "&departAt=" + URLEncoder.encode(fmt.format(Date(t)), "UTF-8")
                        val url = String.format(
                            Locale.US,
                            "https://api.tomtom.com/routing/1/calculateRoute/%.6f,%.6f:%.6f,%.6f/json?key=%s&traffic=true&travelMode=car&routeType=fastest%s",
                            a.latitude, a.longitude, b.latitude, b.longitude, URLEncoder.encode(key, "UTF-8"), at,
                        )
                        val c = URL(url).openConnection() as HttpURLConnection
                        c.connectTimeout = 10_000; c.readTimeout = 20_000
                        c.requestMethod = "POST"; c.doOutput = true
                        c.setRequestProperty("Content-Type", "application/json")
                        try {
                            c.outputStream.use { it.write(body.toString().toByteArray()) }
                            if (c.responseCode !in 200..299) return@async null
                            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                            Slot(t, j.getJSONArray("routes").getJSONObject(0).getJSONObject("summary").getInt("travelTimeInSeconds"))
                        } finally {
                            c.disconnect()
                        }
                    } catch (_: Exception) {
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    // ---------- tankowanie i przerwy ----------

    data class Stop(val kind: String, val place: RouteRepository.Place, val atKm: Double, val etaS: Double, val note: String)

    suspend fun stops(r: RouteRepository.Route, travelS: Double, rangeKm: Double?): List<Stop> = withContext(Dispatchers.IO) {
        val out = ArrayList<Stop>()
        val totalKm = r.distanceM / 1000
        val speed = r.distanceM / travelS.coerceAtLeast(60.0)
        fun idxAt(km: Double) = r.cumulative.indexOfFirst { it >= km * 1000 }.let { if (it < 0) r.points.lastIndex else it }
        if (rangeKm != null && rangeKm < totalKm + 40) {
            val i = idxAt((rangeKm - 40).coerceIn(5.0, totalKm))
            findNear(r.points, i, "fuel")?.let { (p, at) -> out += Stop("fuel", p, r.cumulative[at] / 1000, r.cumulative[at] / speed, "Zasięg ok. ${rangeKm.roundToInt()} km") }
        }
        var t = 2 * 3600.0
        var n = 0
        while (t < travelS - 20 * 60 && n < 3) {
            val i = idxAt(t * speed / 1000)
            (findNear(r.points, i, "rest") ?: findNear(r.points, i, "fuel"))?.let { (p, at) ->
                if (out.none { abs(it.atKm - r.cumulative[at] / 1000) < 15 }) out += Stop("break", p, r.cumulative[at] / 1000, r.cumulative[at] / speed, "Przerwa po ok. ${(t / 3600).roundToInt()} h jazdy")
            }
            t += 2 * 3600.0
            n++
        }
        out.sortedBy { it.atKm }
    }

    private fun findNear(pts: List<GeoPoint>, idx: Int, kind: String): Pair<RouteRepository.Place, Int>? {
        val c = pts[idx]
        val filter = if (kind == "fuel") "[amenity=fuel]" else "[highway~\"^(services|rest_area)$\"]"
        val q = "[out:json][timeout:15];(node$filter(around:4000,${c.latitude},${c.longitude});way$filter(around:4000,${c.latitude},${c.longitude}););out center 25;"
        val els = try {
            JSONObject(get("https://overpass-api.de/api/interpreter?data=" + URLEncoder.encode(q, "UTF-8"))).optJSONArray("elements")
        } catch (_: Exception) { null } ?: return null
        var best: Pair<RouteRepository.Place, Int>? = null
        var bestScore = Double.MAX_VALUE
        val from = (idx - 400).coerceAtLeast(0)
        val to = (idx + 400).coerceAtMost(pts.lastIndex)
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            val lat = e.optDouble("lat", e.optJSONObject("center")?.optDouble("lat") ?: Double.NaN)
            val lon = e.optDouble("lon", e.optJSONObject("center")?.optDouble("lon") ?: Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue
            val g = GeoPoint(lat, lon)
            var dmin = Double.MAX_VALUE
            var at = idx
            var k = from
            while (k <= to) { val x = d(g, pts[k]); if (x < dmin) { dmin = x; at = k }; k += 2 }
            if (dmin > 1500) continue
            val tags = e.optJSONObject("tags")
            val name = listOfNotNull(tags?.optString("brand")?.ifBlank { null }, tags?.optString("name")?.ifBlank { null }).distinct().joinToString(" ")
            val score = dmin * 3 + abs(at - idx) * 2.0
            if (score < bestScore) {
                bestScore = score
                best = RouteRepository.Place(name.ifBlank { if (kind == "fuel") "Stacja paliw" else "Miejsce odpoczynku" }, if (kind == "fuel") "Stacja paliw" else "Przerwa", lat, lon) to at
            }
        }
        return best
    }
}
