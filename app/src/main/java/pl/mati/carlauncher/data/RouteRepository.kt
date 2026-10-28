package pl.mati.carlauncher.data

import android.content.Context
import android.content.Intent
import android.location.Location
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.max

/**
 * Planowanie i rysowanie trasy w CAR OS.
 * Wyszukiwanie: Nominatim (OpenStreetMap). Trasy: OSRM (warianty, przystanki).
 * Prowadzenie głosowe: Mapy Google – w tle, CAR OS wraca na ekran.
 */
object RouteRepository {

    data class Place(val name: String, val detail: String, val lat: Double, val lon: Double) {
        val point get() = GeoPoint(lat, lon)
        val coord get() = String.format(Locale.US, "%.6f,%.6f", lat, lon)
    }

    data class Route(
        val dest: Place,
        val stops: List<Place>,
        val points: List<GeoPoint>,
        val cumulative: DoubleArray,
        val distanceM: Double,
        val durationS: Double,
        val id: Long = System.nanoTime(),
    )

    /** Planowana trasa: cel, przystanki, warianty do wyboru. */
    data class Plan(
        val dest: Place,
        val stops: List<Place> = emptyList(),
        val alternatives: List<Route> = emptyList(),
        val selected: Int = 0,
        val computing: Boolean = true,
        val error: String? = null,
    ) {
        val chosen: Route? get() = alternatives.getOrNull(selected)
    }

    data class Progress(val remainingM: Double, val remainingS: Double, val arrived: Boolean)

    val route = MutableStateFlow<Route?>(null)
    val plan = MutableStateFlow<Plan?>(null)
    val progress = MutableStateFlow<Progress?>(null)
    val busy = MutableStateFlow(false)
    val results = MutableStateFlow<List<Place>>(emptyList())
    val error = MutableStateFlow<String?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitor: Job? = null
    private var planJob: Job? = null
    private var lastReroute = 0L
    private var offRouteCount = 0
    private val main = Handler(Looper.getMainLooper())
    private const val UA = "CarOS/1.0 (Android head unit launcher; pl.mati.carlauncher)"

    // ---------- wyszukiwanie ----------

    fun search(query: String, nearbyOnly: Boolean = false) {
        if (query.isBlank()) return
        scope.launch {
            busy.value = true
            error.value = null
            try {
                results.value = searchPlaces(query, nearbyOnly)
                if (results.value.isEmpty()) error.value = "Nic nie znaleziono dla „$query”"
            } catch (e: Exception) {
                error.value = "Brak połączenia z wyszukiwarką"
            } finally {
                busy.value = false
            }
        }
    }

    fun clearResults() {
        results.value = emptyList()
        error.value = null
    }

    private fun searchPlaces(query: String, nearbyOnly: Boolean): List<Place> {
        val loc = LocationRepository.location.value
        val sb = StringBuilder("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=8&addressdetails=0&accept-language=pl")
        sb.append("&q=").append(URLEncoder.encode(query, "UTF-8"))
        if (loc != null) {
            val d = if (nearbyOnly) 0.12 else 1.5
            sb.append(String.format(Locale.US, "&viewbox=%.4f,%.4f,%.4f,%.4f", loc.longitude - d, loc.latitude + d, loc.longitude + d, loc.latitude - d))
            if (nearbyOnly) sb.append("&bounded=1")
        }
        val arr = JSONArray(get(sb.toString()))
        val out = ArrayList<Place>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val parts = o.optString("display_name").split(", ")
            val name = o.optString("name").ifBlank { parts.firstOrNull().orEmpty() }
            out += Place(name, parts.drop(1).take(3).joinToString(", "), o.getString("lat").toDouble(), o.getString("lon").toDouble())
        }
        if (loc != null) out.sortBy { dist(loc.latitude, loc.longitude, it.lat, it.lon) }
        return out
    }

    // ---------- planowanie ----------

    /** Zaczyna planowanie trasy do celu (warianty pojawią się na mapie). */
    fun planTo(place: Place) {
        clearResults()
        plan.value = Plan(dest = place)
        recompute()
    }

    /** Dom / Praca: adres → współrzędne → planowanie. */
    fun planToAddress(address: String, label: String) {
        scope.launch {
            busy.value = true
            try {
                val p = searchPlaces(address, false).firstOrNull()
                if (p == null) error.value = "Nie znaleziono adresu: $address"
                else planTo(p.copy(name = label, detail = address))
            } catch (e: Exception) {
                error.value = "Brak połączenia z wyszukiwarką"
            } finally {
                busy.value = false
            }
        }
    }

    /** Szybki start (np. „Jedziesz do domu?”): planuje i od razu rusza najszybszym wariantem. */
    fun quickStart(ctx: Context, address: String, label: String) {
        planToAddress(address, label)
        scope.launch {
            repeat(60) {
                delay(500)
                val p = plan.value
                if (p != null && !p.computing && p.chosen != null) {
                    main.post { start(ctx) }
                    return@launch
                }
            }
        }
    }

    fun addStop(place: Place) {
        val p = plan.value ?: return
        clearResults()
        plan.value = p.copy(stops = (p.stops + place).take(3), alternatives = emptyList(), selected = 0, computing = true)
        recompute()
    }

    fun removeStop(index: Int) {
        val p = plan.value ?: return
        plan.value = p.copy(stops = p.stops.filterIndexed { i, _ -> i != index }, alternatives = emptyList(), selected = 0, computing = true)
        recompute()
    }

    fun select(index: Int) {
        val p = plan.value ?: return
        if (index in p.alternatives.indices) plan.value = p.copy(selected = index)
    }

    fun cancelPlan() {
        planJob?.cancel()
        plan.value = null
    }

    private fun recompute() {
        planJob?.cancel()
        planJob = scope.launch {
            var from = LocationRepository.location.value
            var waited = 0
            while (from == null && waited < 30) {
                plan.value = plan.value?.copy(computing = true, error = "Czekam na GPS…")
                delay(1000); waited++
                from = LocationRepository.location.value
            }
            val p = plan.value ?: return@launch
            if (from == null) {
                plan.value = p.copy(computing = false, error = "Brak pozycji GPS")
                return@launch
            }
            try {
                val alts = fetchRoutes(from, p.stops, p.dest)
                plan.value = plan.value?.copy(alternatives = alts, selected = 0, computing = false, error = if (alts.isEmpty()) "Nie znaleziono trasy" else null)
            } catch (e: Exception) {
                plan.value = plan.value?.copy(computing = false, error = "Nie udało się wyznaczyć trasy – sprawdź internet")
            }
        }
    }

    /** Start: wybrany wariant staje się aktywną trasą, Mapy Google prowadzą (w tle). */
    fun start(ctx: Context) {
        val p = plan.value ?: return
        val r = p.chosen ?: return
        plan.value = null
        route.value = r
        offRouteCount = 0
        startMonitor()

        // Wymuszenie wybranego wariantu w Mapach Google: punkty „przez” z wybranej trasy.
        val forced = if (p.selected > 0 && p.stops.isEmpty()) viaPoints(r) else emptyList()
        launchGoogleMaps(ctx, r.dest, p.stops.map { it.coord } + forced)

        if (Prefs.mapsInBackground.value) {
            if (canReturn(ctx)) {
                main.postDelayed({ bringLauncherToFront(ctx) }, 7000)
            }
        }
    }

    /** Anulowanie przez kierowcę: kończy też nawigację w Mapach Google. */
    fun cancel(ctx: Context) {
        val hadGoogle = NavRepository.state.value != null
        stop()
        if (!hadGoogle) return
        if (NavRepository.stopNavigation()) {
            pl.mati.carlauncher.ui.Toaster.show("Trasa anulowana – nawigacja w Mapach Google zakończona")
        } else {
            pl.mati.carlauncher.ui.Toaster.show("Zakończ nawigację w Mapach Google przyciskiem X")
            val i = ctx.packageManager.getLaunchIntentForPackage(NavRepository.state.value?.pkg ?: "com.google.android.apps.maps")
            if (i != null) try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        }
    }

    fun stop() {
        monitor?.cancel()
        route.value = null
        progress.value = null
    }

    /** Czy Android pozwoli launcherowi wrócić na ekran z tła (zgoda „nad innymi aplikacjami”). */
    fun canReturn(ctx: Context) = Settings.canDrawOverlays(ctx)

    fun requestReturnPermission(ctx: Context) {
        val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(i) } catch (_: Exception) {}
    }

    private fun bringLauncherToFront(ctx: Context) {
        val i = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return
        i.action = "pl.mati.carlauncher.RETURN"
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        try { ctx.startActivity(i) } catch (_: Exception) {}
    }

    private fun viaPoints(r: Route): List<String> {
        val out = ArrayList<String>()
        for (f in listOf(0.35, 0.7)) {
            val target = r.distanceM * f
            val idx = r.cumulative.indexOfFirst { it >= target }.takeIf { it >= 0 } ?: continue
            val pt = r.points[idx]
            out += String.format(Locale.US, "%.6f,%.6f", pt.latitude, pt.longitude)
        }
        return out
    }

    private fun launchGoogleMaps(ctx: Context, dest: Place, waypoints: List<String>) {
        val uri = if (waypoints.isEmpty()) {
            Uri.parse("google.navigation:q=${dest.coord}&mode=d")
        } else {
            Uri.parse(
                "https://www.google.com/maps/dir/?api=1&travelmode=driving&dir_action=navigate" +
                    "&destination=" + Uri.encode(dest.coord) +
                    "&waypoints=" + Uri.encode(waypoints.joinToString("|")),
            )
        }
        val i = Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.maps").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(i)
        } catch (_: Exception) {
            try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${dest.coord}?q=${dest.coord}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
            }
        }
    }

    // ---------- trasy (OSRM) ----------

    private fun fetchRoutes(from: Location, stops: List<Place>, dest: Place): List<Route> {
        val coords = buildList {
            add(String.format(Locale.US, "%.6f,%.6f", from.longitude, from.latitude))
            stops.forEach { add(String.format(Locale.US, "%.6f,%.6f", it.lon, it.lat)) }
            add(String.format(Locale.US, "%.6f,%.6f", dest.lon, dest.lat))
        }.joinToString(";")
        val alt = if (stops.isEmpty()) "&alternatives=3" else ""
        val json = JSONObject(get("https://router.project-osrm.org/route/v1/driving/$coords?overview=full&geometries=polyline6$alt"))
        if (json.optString("code") != "Ok") throw IOException(json.optString("code"))
        val arr = json.getJSONArray("routes")
        val out = ArrayList<Route>()
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            val pts = decodePolyline6(r.getString("geometry"))
            if (pts.size < 2) continue
            val cum = DoubleArray(pts.size)
            for (k in 1 until pts.size) cum[k] = cum[k - 1] + dist(pts[k - 1].latitude, pts[k - 1].longitude, pts[k].latitude, pts[k].longitude)
            out += Route(dest, stops, pts, cum, r.getDouble("distance"), r.getDouble("duration"))
        }
        return out
    }

    private suspend fun reroute(r: Route) {
        val from = LocationRepository.location.value ?: return
        try {
            val nr = fetchRoutes(from, r.stops, r.dest).firstOrNull() ?: return
            route.value = nr
            offRouteCount = 0
            startMonitor()
        } catch (_: Exception) {
        }
    }

    /** Postęp, dojazd, zjechanie z trasy (przeliczenie), koniec nawigacji Google. */
    private fun startMonitor() {
        monitor?.cancel()
        monitor = scope.launch {
            var navSeen = false
            var navGoneSince = 0L
            while (true) {
                val r = route.value ?: break
                val loc = LocationRepository.location.value
                if (loc != null) {
                    var best = Double.MAX_VALUE
                    var idx = 0
                    for (i in r.points.indices) {
                        val d = dist(loc.latitude, loc.longitude, r.points[i].latitude, r.points[i].longitude)
                        if (d < best) { best = d; idx = i }
                    }
                    val remaining = max(0.0, r.distanceM - r.cumulative[idx]) + best.coerceAtMost(200.0)
                    val arrived = dist(loc.latitude, loc.longitude, r.dest.lat, r.dest.lon) < 40
                    progress.value = Progress(remaining, r.durationS * (remaining / max(1.0, r.distanceM)), arrived)
                    if (arrived) {
                        delay(15_000)
                        stop()
                        break
                    }
                    offRouteCount = if (best > 120) offRouteCount + 1 else 0
                    val now = System.currentTimeMillis()
                    if (offRouteCount >= 3 && now - lastReroute > 30_000) {
                        lastReroute = now
                        reroute(r)
                        break
                    }
                }
                val nav = NavRepository.state.value
                if (nav != null) { navSeen = true; navGoneSince = 0L }
                else if (navSeen) {
                    if (navGoneSince == 0L) navGoneSince = System.currentTimeMillis()
                    else if (System.currentTimeMillis() - navGoneSince > 20_000) { stop(); break }
                }
                delay(2000)
            }
        }
    }

    // ---------- narzędzia ----------

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", UA)
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    fun dist(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val out = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, out)
        return out[0].toDouble()
    }

    private fun decodePolyline6(s: String): List<GeoPoint> {
        val pts = ArrayList<GeoPoint>()
        var i = 0
        var lat = 0
        var lon = 0
        while (i < s.length) {
            var shift = 0
            var result = 0
            var b: Int
            do { b = s[i++].code - 63; result = result or ((b and 0x1f) shl shift); shift += 5 } while (b >= 0x20)
            lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1
            shift = 0; result = 0
            do { b = s[i++].code - 63; result = result or ((b and 0x1f) shl shift); shift += 5 } while (b >= 0x20)
            lon += if (result and 1 != 0) (result shr 1).inv() else result shr 1
            pts += GeoPoint(lat / 1e6, lon / 1e6)
        }
        return pts
    }
}
