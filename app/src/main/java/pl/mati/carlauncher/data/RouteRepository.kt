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
        val steps: List<Guidance.Step> = emptyList(),
        val id: Long = System.nanoTime(),
        /** Trasa przeliczona w czasie jazdy (bez podglądu całej trasy na mapie). */
        val rerouted: Boolean = false,
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

    /** Przejechane metry bieżącej trasy (do korków przed autem). */
    val alongNow = MutableStateFlow(0.0)

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

    /** Adres → miejsce (pierwszy wynik). Wołać poza wątkiem głównym. */
    fun geocode(address: String): Place? = try { searchPlaces(address, false).firstOrNull() } catch (_: Exception) { null }

    /** Czas przejazdu A → B w sekundach (OSRM) albo null. Wołać poza wątkiem głównym. */
    fun routeDuration(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Double? = try {
        val url = String.format(Locale.US, "https://router.project-osrm.org/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=false", fromLon, fromLat, toLon, toLat)
        val json = JSONObject(get(url))
        if (json.optString("code") != "Ok") null else json.getJSONArray("routes").getJSONObject(0).getDouble("duration")
    } catch (_: Exception) {
        null
    }

    /** Sama linia trasy A → B (OSRM, uproszczona). Wołać poza wątkiem głównym. */
    fun routeLine(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): List<GeoPoint> {
        val url = String.format(Locale.US, "https://router.project-osrm.org/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=simplified&geometries=polyline6", fromLon, fromLat, toLon, toLat)
        val json = JSONObject(get(url))
        if (json.optString("code") != "Ok") return emptyList()
        return decodePolyline6(json.getJSONArray("routes").getJSONObject(0).getString("geometry"))
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
        // dom / praca z zapisanymi współrzędnymi – bez szukania adresu
        val pr = Prefs.profile.value
        val known = when {
            label == "Dom" && address == pr.home && pr.homeLat != null && pr.homeLon != null -> Place(label, address, pr.homeLat, pr.homeLon)
            label == "Praca" && address == pr.work && pr.workLat != null && pr.workLon != null -> Place(label, address, pr.workLat, pr.workLon)
            else -> null
        }
        if (known != null) {
            planTo(known)
            return
        }
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

    /** Plan do miejsca i od razu start najszybszym wariantem (np. z komendy głosowej). */
    fun quickStartPlace(ctx: Context, place: Place) {
        planTo(place)
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
        PlacesRepository.addRecent(p.dest)
        offRouteCount = 0
        appCtx = ctx.applicationContext
        if (ownGuidance) {
            // prowadzi CAR OS: strzałki na mapie i głos po polsku, bez Map Google
            Guidance.begin(ctx, r, rerouted = false)
            startMonitor()
            return
        }
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
        if (ownGuidance) {
            stop()
            pl.mati.carlauncher.ui.Toaster.show("Nawigacja zakończona")
            return
        }
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
        Guidance.end()
    }

    private var appCtx: Context? = null
    private val ownGuidance get() = Prefs.navEngine.value == "caros"

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
        val json = JSONObject(get("https://router.project-osrm.org/route/v1/driving/$coords?overview=full&geometries=polyline6&steps=true$alt"))
        if (json.optString("code") != "Ok") throw IOException(json.optString("code"))
        val arr = json.getJSONArray("routes")
        val out = ArrayList<Route>()
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            val pts = decodePolyline6(r.getString("geometry"))
            if (pts.size < 2) continue
            val cum = DoubleArray(pts.size)
            for (k in 1 until pts.size) cum[k] = cum[k - 1] + dist(pts[k - 1].latitude, pts[k - 1].longitude, pts[k].latitude, pts[k].longitude)
            val steps = try { Guidance.parseSteps(r, pts, cum) } catch (_: Exception) { emptyList() }
            out += Route(dest, stops, pts, cum, r.getDouble("distance"), r.getDouble("duration"), steps)
        }
        return out
    }

    private suspend fun reroute(r: Route) {
        val from = LocationRepository.location.value ?: return
        try {
            val nr = fetchRoutes(from, r.stops, r.dest).firstOrNull()?.copy(rerouted = true) ?: return
            route.value = nr
            offRouteCount = 0
            if (ownGuidance) appCtx?.let { Guidance.begin(it, nr, rerouted = true) }
            startMonitor()
        } catch (_: Exception) {
        }
    }

    private data class Near(val dist: Double, val index: Int, val along: Double)

    /** Najbliższy punkt na odcinkach trasy [from, to): odległość, indeks odcinka, przejechane metry trasy. */
    private fun nearestOnRoute(r: Route, lat: Double, lon: Double, from: Int, to: Int): Near {
        val pts = r.points
        val a = from.coerceIn(0, pts.size - 1)
        val b = to.coerceIn(a + 1, pts.size)
        val kx = 111_320.0 * Math.cos(Math.toRadians(lat))
        val ky = 110_540.0
        var best = Double.MAX_VALUE
        var bi = a
        var along = r.cumulative[a]
        for (i in a until b - 1) {
            val ax = (pts[i].longitude - lon) * kx
            val ay = (pts[i].latitude - lat) * ky
            val dx = (pts[i + 1].longitude - lon) * kx - ax
            val dy = (pts[i + 1].latitude - lat) * ky - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 < 0.01) 0.0 else ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0)
            val d = Math.hypot(ax + t * dx, ay + t * dy)
            if (d < best) {
                best = d
                bi = i
                along = r.cumulative[i] + t * (r.cumulative[i + 1] - r.cumulative[i])
            }
        }
        if (b - a == 1) best = dist(lat, lon, pts[a].latitude, pts[a].longitude)
        return Near(best, bi, along)
    }

    /** Postęp, dojazd, zjechanie z trasy (przeliczenie), koniec nawigacji Google. */
    private fun startMonitor() {
        monitor?.cancel()
        monitor = scope.launch {
            var navSeen = false
            var navGoneSince = 0L
            var lastIdx = 0
            val own = ownGuidance
            while (true) {
                val r = route.value ?: break
                val loc = LocationRepository.location.value
                if (loc != null) {
                    // najbliższy ODCINEK trasy (nie punkt – na prostych punkty są co kilkaset metrów):
                    // najpierw w okolicy poprzedniego miejsca (szybko, bez skoków na pętlach), w razie czego cała trasa
                    var near = nearestOnRoute(r, loc.latitude, loc.longitude, lastIdx - 20, lastIdx + 300)
                    if (near.dist > 60) {
                        val all = nearestOnRoute(r, loc.latitude, loc.longitude, 0, r.points.size)
                        if (all.dist < near.dist) near = all
                    }
                    val best = near.dist
                    lastIdx = near.index
                    val remaining = max(0.0, r.distanceM - near.along)
                    val arrived = dist(loc.latitude, loc.longitude, r.dest.lat, r.dest.lon) < 40
                    // czas z korkami (TomTom), a bez danych – proporcjonalnie z OSRM
                    val remS = TrafficRepository.remainingS(r, remaining) ?: (r.durationS * (remaining / max(1.0, r.distanceM)))
                    progress.value = Progress(remaining, remS, arrived)
                    alongNow.value = near.along
                    if (own) Guidance.update(r, near.along, loc)
                    if (arrived) {
                        delay(15_000)
                        stop()
                        break
                    }
                    // zjechanie z trasy: wyraźnie dalej niż dokładność GPS, przez kilka sekund z rzędu
                    val acc = if (loc.hasAccuracy()) loc.accuracy.toDouble() else 20.0
                    val limit = if (own) maxOf(50.0, acc + 30.0) else 120.0
                    offRouteCount = if (best > limit) offRouteCount + 1 else 0
                    val now = System.currentTimeMillis()
                    if (offRouteCount >= (if (own) 6 else 3) && now - lastReroute > 30_000) {
                        lastReroute = now
                        reroute(r)
                        break
                    }
                }
                if (!own) {
                    val nav = NavRepository.state.value
                    if (nav != null) { navSeen = true; navGoneSince = 0L }
                    else if (navSeen) {
                        if (navGoneSince == 0L) navGoneSince = System.currentTimeMillis()
                        else if (System.currentTimeMillis() - navGoneSince > 20_000) { stop(); break }
                    }
                }
                delay(if (own) 1000 else 2000)
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
