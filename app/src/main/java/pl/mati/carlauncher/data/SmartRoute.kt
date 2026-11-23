package pl.mati.carlauncher.data

import pl.mati.caros.core.AlongRoute
import pl.mati.caros.core.FuelType
import pl.mati.caros.core.RoutePlan
import pl.mati.caros.core.StationPrices
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

    // ---------- tankowanie i przerwy (wspólne z telefonem: core/RoutePlan) ----------

    /**
     * Postój: „fuel” (tankowanie nr [n]) albo „break” (przerwa po [hours] h); [options] – propozycje,
     * przy tankowaniu najtańsze (w złotych) pierwsze, z ceną wybranego paliwa.
     */
    data class Stop(val kind: String, val n: Int, val hours: Int, val atKm: Double, val options: List<RoutePlan.Opt>)

    data class Plan(val stops: List<Stop>, val res: RoutePlan.Result)

    /**
     * Tankowania (gdy zasięg nie wystarcza – zapas z ustawień, domyślnie 100 km; na długich trasach kilka, po każdym pełny bak)
     * i przerwy co ok. 2 h – miejsca przy samej trasie (OpenStreetMap, a gdy nie odpowiada – TomTom), z cenami paliwa
     * z całej Europy. Rzuca IOException, gdy nic nie udało się sprawdzić.
     */
    suspend fun stops(ctx: android.content.Context, r: RouteRepository.Route, travelS: Double, rangeKm: Double?): Plan = withContext(Dispatchers.IO) {
        val n = r.points.size
        val lats = DoubleArray(n) { r.points[it].latitude }
        val lons = DoubleArray(n) { r.points[it].longitude }
        val fav = FuelPrices.fav.value
        val st = FuelRepository.stats.value
        val res = RoutePlan.stops(
            ctx, lats, lons, travelS, System.currentTimeMillis(), rangeKm, Prefs.minFuelRange.value,
            FuelType.of(fav.key), StationPrices.tkKey.value, Prefs.tomtomKey.value.trim().ifBlank { null },
            fullKmCar = RoutePlan.fullRangeKm(FuelRepository.tankL.value.toDouble(), st.avgL100),
            plMax = FuelPrices.data.value?.today?.get(fav),
        ) { TrafficRepository.count() }
        Plan(res.groups.filter { it.options.isNotEmpty() }.map { g -> Stop(g.kind, g.n, g.hours, g.atKm, g.options) }, res)
    }

    fun placeOf(o: RoutePlan.Opt) = RouteRepository.Place(o.place.name, o.place.detail, o.place.lat, o.place.lon)

    /** Dodaje postój między przystanki tak, żeby kolejność zgadzała się z trasą. */
    fun addStopInOrder(r: RouteRepository.Route, stops: List<RouteRepository.Place>, o: RoutePlan.Opt) {
        val n = r.points.size
        val lats = DoubleArray(n) { r.points[it].latitude }
        val lons = DoubleArray(n) { r.points[it].longitude }
        val idx = stops.indexOfFirst { AlongRoute.positionOf(it.lat, it.lon, lats, lons, r.cumulative) > o.atKm * 1000 }
        RouteRepository.insertStop(placeOf(o), if (idx < 0) stops.size else idx)
    }
}
