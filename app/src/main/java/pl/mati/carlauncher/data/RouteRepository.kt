package pl.mati.carlauncher.data

import android.content.Context
import android.content.Intent
import android.location.Location
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
 * Własne wyznaczanie trasy, żeby była widoczna na mapie launchera.
 * Wyszukiwanie: Nominatim (OpenStreetMap). Trasa: OSRM (profil samochód).
 * Prowadzenie głosowe dalej robią Mapy Google – do tego samego punktu.
 */
object RouteRepository {

    data class Place(val name: String, val detail: String, val lat: Double, val lon: Double) {
        val point get() = GeoPoint(lat, lon)
    }

    data class Route(
        val dest: Place,
        val points: List<GeoPoint>,
        val cumulative: DoubleArray, // narastający dystans do każdego punktu [m]
        val distanceM: Double,
        val durationS: Double,
        val id: Long = System.nanoTime(),
    )

    data class Progress(val remainingM: Double, val remainingS: Double, val arrived: Boolean)

    val route = MutableStateFlow<Route?>(null)
    val progress = MutableStateFlow<Progress?>(null)
    val busy = MutableStateFlow(false)
    val results = MutableStateFlow<List<Place>>(emptyList())
    val error = MutableStateFlow<String?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitor: Job? = null
    private var lastReroute = 0L
    private var offRouteCount = 0
    private val UA = "CarOS/1.0 (Android head unit launcher; pl.mati.carlauncher)"

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
            val full = o.optString("display_name")
            val parts = full.split(", ")
            val name = o.optString("name").ifBlank { parts.firstOrNull().orEmpty() }
            val detail = parts.drop(1).take(3).joinToString(", ")
            out += Place(name, detail, o.getString("lat").toDouble(), o.getString("lon").toDouble())
        }
        // najbliższe najpierw, gdy znamy pozycję
        if (loc != null) {
            out.sortBy { dist(loc.latitude, loc.longitude, it.lat, it.lon) }
        }
        return out
    }

    // ---------- nawigacja ----------

    /** Wyznacza trasę, rysuje ją na mapie i uruchamia prowadzenie w Mapach Google. */
    fun navigate(ctx: Context, place: Place) {
        clearResults()
        launchGoogleMaps(ctx, place)
        scope.launch { buildRoute(place) }
    }

    /** Dom / Praca: najpierw zamiana adresu na współrzędne. */
    fun navigateToAddress(ctx: Context, address: String, label: String) {
        scope.launch {
            busy.value = true
            try {
                val p = searchPlaces(address, false).firstOrNull()
                if (p == null) {
                    error.value = "Nie znaleziono adresu: $address"
                    return@launch
                }
                withContext(Dispatchers.Main) { navigate(ctx, p.copy(name = label, detail = address)) }
            } catch (e: Exception) {
                error.value = "Brak połączenia z wyszukiwarką"
            } finally {
                busy.value = false
            }
        }
    }

    fun stop() {
        monitor?.cancel()
        route.value = null
        progress.value = null
    }

    private suspend fun buildRoute(place: Place, quiet: Boolean = false) {
        val from = LocationRepository.location.value
        if (from == null) {
            error.value = "Czekam na GPS – trasa pojawi się po ustaleniu pozycji"
            // spróbuj ponownie, gdy GPS złapie pozycję
            repeat(30) {
                delay(2000)
                if (LocationRepository.location.value != null) return buildRoute(place, quiet)
            }
            return
        }
        if (!quiet) busy.value = true
        try {
            val r = fetchRoute(from, place)
            route.value = r
            error.value = null
            offRouteCount = 0
            startMonitor()
        } catch (e: Exception) {
            if (!quiet) error.value = "Nie udało się wyznaczyć trasy"
        } finally {
            if (!quiet) busy.value = false
        }
    }

    private fun fetchRoute(from: Location, to: Place): Route {
        val url = String.format(
            Locale.US,
            "https://router.project-osrm.org/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=full&geometries=polyline6",
            from.longitude, from.latitude, to.lon, to.lat,
        )
        val json = JSONObject(get(url))
        if (json.optString("code") != "Ok") throw IOException(json.optString("code"))
        val r = json.getJSONArray("routes").getJSONObject(0)
        val pts = decodePolyline6(r.getString("geometry"))
        val cum = DoubleArray(pts.size)
        for (i in 1 until pts.size) cum[i] = cum[i - 1] + dist(pts[i - 1].latitude, pts[i - 1].longitude, pts[i].latitude, pts[i].longitude)
        return Route(to, pts, cum, r.getDouble("distance"), r.getDouble("duration"))
    }

    /** Śledzi postęp, wykrywa dojazd i zjechanie z trasy (wtedy przelicza). */
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
                    val toDest = dist(loc.latitude, loc.longitude, r.dest.lat, r.dest.lon)
                    val arrived = toDest < 40
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
                        offRouteCount = 0
                        buildRoute(r.dest, quiet = true)
                        break // nowy monitor startuje w buildRoute
                    }
                }
                // Zakończona nawigacja w Mapach Google → po chwili czyścimy trasę
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

    private fun launchGoogleMaps(ctx: Context, p: Place) {
        val q = String.format(Locale.US, "%.6f,%.6f", p.lat, p.lon)
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$q")).setPackage("com.google.android.apps.maps")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(i)
        } catch (_: Exception) {
            try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$q?q=$q")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
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
