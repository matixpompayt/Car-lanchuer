package pl.mati.carlauncher.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Korki z TomTom (darmowy klucz: 2 500 zapytań i 50 000 kafelków dziennie).
 *  – na mapie: warstwa natężenia ruchu (tylko miejsca wolniejsze niż zwykle),
 *  – na trasie: trasę z OSRM odtwarzamy w TomTom (supportingPoints) z ruchem na żywo – stąd czas z korkami,
 *    opóźnienie i odcinki korków (kolorowane na trasie), odświeżane co 3 min w czasie jazdy.
 */
object TrafficRepository {

    data class Jam(
        val points: List<GeoPoint>,
        /** Gdzie zaczyna się na NASZEJ trasie (m od początku trasy, w przybliżeniu). */
        val startAlongM: Double,
        val lengthM: Double,
        val delayS: Int,
        val speedKmh: Int?,
        /** JAM, ROAD_WORK, ROAD_CLOSURE, OTHER */
        val kind: String,
        /** 0 nieznane, 1 małe, 2 średnie, 3 duże, 4 zamknięcie / bez końca */
        val magnitude: Int,
    )

    data class Info(
        val routeId: Long,
        /** Czas z ruchem na żywo od miejsca zapytania do celu. */
        val travelS: Int,
        val delayS: Int,
        /** Długość trasy TomTom od miejsca zapytania (do przeliczania pozostałego czasu). */
        val lengthM: Double,
        /** Miejsce zapytania na naszej trasie (m od początku). */
        val fromAlongM: Double,
        val jams: List<Jam>,
        val at: Long = System.currentTimeMillis(),
    )

    /** Aktywna trasa. */
    val info = MutableStateFlow<Info?>(null)
    /** Warianty w planowaniu: id trasy → dane. */
    val plan = MutableStateFlow<Map<Long, Info>>(emptyMap())
    /** Ostatni błąd (np. zły klucz) – do ustawień. */
    val error = MutableStateFlow<String?>(null)
    /** Licznik zapytań dzisiaj (limit darmowy 2 500). */
    val usedToday = MutableStateFlow(0)
    /** Zmienia się co 5 min – mapa odświeża wtedy warstwę korków. */
    val tick = MutableStateFlow(0L)

    private const val REFRESH_MS = 3 * 60_000L
    private const val DAILY_CAP = 2_000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false
    private var appCtx: Context? = null
    private var routeJob: Job? = null
    private var planJob: Job? = null
    private val announced = HashSet<Long>()

    val enabled get() = Prefs.tomtomKey.value.isNotBlank()

    fun start(ctx: Context) {
        if (started) return
        started = true
        appCtx = ctx.applicationContext
        loadCounter()
        // warianty w planowaniu
        scope.launch {
            RouteRepository.plan.map { p -> p?.alternatives?.map { it.id } }.distinctUntilChanged().collect { ids ->
                planJob?.cancel()
                if (ids.isNullOrEmpty()) { plan.value = emptyMap(); return@collect }
                if (!enabled || !Prefs.trafficEta.value) return@collect
                val alts = RouteRepository.plan.value?.alternatives ?: return@collect
                planJob = launch {
                    val out = HashMap<Long, Info>()
                    for (r in alts.take(4)) {
                        val i = try { query(r, 0.0, null) } catch (_: Exception) { null } ?: continue
                        out[r.id] = i
                        plan.value = HashMap(out)
                    }
                }
            }
        }
        // aktywna trasa – od razu i co 3 min z bieżącej pozycji
        scope.launch {
            RouteRepository.route.map { it?.id }.distinctUntilChanged().collect { id ->
                routeJob?.cancel()
                val r = RouteRepository.route.value
                if (id == null || r == null) { info.value = null; announced.clear(); return@collect }
                // wariant z planowania już ma świeże dane – użyj od razu
                plan.value[r.id]?.let { info.value = it }
                if (info.value?.routeId != r.id) info.value = null
                routeJob = launch {
                    var first = true
                    while (isActive) {
                        if (enabled && Prefs.trafficEta.value) {
                            val fresh = info.value?.let { it.routeId == r.id && System.currentTimeMillis() - it.at < REFRESH_MS - 10_000 } == true
                            if (!fresh || !first) {
                                try {
                                    val (along, here) = whereOn(r)
                                    query(r, along, here)?.let {
                                        info.value = it
                                        announce(it, along)
                                    }
                                } catch (_: Exception) {
                                }
                            }
                        }
                        first = false
                        delay(REFRESH_MS)
                    }
                }
            }
        }
        // odświeżanie warstwy korków na mapie
        scope.launch {
            while (isActive) {
                tick.value = System.currentTimeMillis() / (5 * 60_000L)
                delay(30_000)
            }
        }
    }

    /** Pozostały czas z korkami (s) dla bieżącej trasy – null, gdy brak danych. */
    fun remainingS(r: RouteRepository.Route, remainingM: Double): Double? {
        val i = info.value ?: return null
        if (i.routeId != r.id || i.lengthM < 1) return null
        if (System.currentTimeMillis() - i.at > 15 * 60_000L) return null
        // proporcjonalnie do drogi, która została od miejsca zapytania
        val remFromQuery = max(1.0, r.distanceM - i.fromAlongM)
        return i.travelS * (remainingM / remFromQuery).coerceIn(0.0, 1.5)
    }

    /** Opóźnienie (s) przez korki jeszcze przed autem. */
    fun delayAhead(alongM: Double): Int {
        val i = info.value ?: return 0
        return i.jams.filter { it.startAlongM + it.lengthM > alongM }.sumOf { it.delayS }
    }

    // ---------- zapytanie ----------

    private fun whereOn(r: RouteRepository.Route): Pair<Double, GeoPoint?> {
        val l = LocationRepository.location.value ?: return 0.0 to null
        // najbliższy punkt trasy (wystarczy po punktach – do ustalenia początku zapytania)
        var best = Double.MAX_VALUE
        var bi = 0
        for (i in r.points.indices) {
            val p = r.points[i]
            val d = RouteRepository.dist(l.latitude, l.longitude, p.latitude, p.longitude)
            if (d < best) { best = d; bi = i }
        }
        return r.cumulative[bi] to GeoPoint(l.latitude, l.longitude)
    }

    private fun query(r: RouteRepository.Route, fromAlong: Double, here: GeoPoint?): Info? {
        val key = Prefs.tomtomKey.value.trim()
        if (key.isBlank()) return null
        if (!countRequest()) { error.value = "Dzienny limit zapytań TomTom wykorzystany"; return null }
        // reszta trasy od miejsca zapytania
        val start = r.cumulative.indexOfFirst { it >= fromAlong }.let { if (it < 0) r.points.lastIndex else it }
        val rest = ArrayList<GeoPoint>()
        here?.let { rest += it }
        for (i in start until r.points.size) rest += r.points[i]
        if (rest.size < 2) return null
        val step = max(1, rest.size / 900)
        val pts = rest.filterIndexed { i, _ -> i % step == 0 || i == rest.lastIndex }
        val a = pts.first()
        val b = pts.last()
        val body = JSONObject().put("supportingPoints", JSONArray().apply {
            pts.forEach { put(JSONObject().put("latitude", it.latitude).put("longitude", it.longitude)) }
        })
        val url = String.format(
            java.util.Locale.US,
            "https://api.tomtom.com/routing/1/calculateRoute/%.6f,%.6f:%.6f,%.6f/json?key=%s&traffic=true&travelMode=car&routeType=fastest" +
                "&sectionType=traffic&routeRepresentation=polyline&computeTravelTimeFor=all",
            a.latitude, a.longitude, b.latitude, b.longitude, java.net.URLEncoder.encode(key, "UTF-8"),
        )
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("User-Agent", "CarOS")
        val text = try {
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            if (code == 403 || code == 401) { error.value = "Klucz TomTom nieprawidłowy albo bez dostępu do Routing API"; return null }
            if (code == 429) { error.value = "TomTom: za dużo zapytań – spróbuję później"; return null }
            if (code !in 200..299) {
                val msg = try { c.errorStream?.bufferedReader()?.use { it.readText() } } catch (_: Exception) { null }
                error.value = "TomTom: błąd $code" + (msg?.let { " – " + it.take(120) } ?: "")
                throw IOException("HTTP $code")
            }
            c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
        error.value = null
        val route = JSONObject(text).getJSONArray("routes").getJSONObject(0)
        val sum = route.getJSONObject("summary")
        val tt = ArrayList<GeoPoint>()
        val legs = route.getJSONArray("legs")
        for (i in 0 until legs.length()) {
            val p = legs.getJSONObject(i).getJSONArray("points")
            for (k in 0 until p.length()) {
                val o = p.getJSONObject(k)
                tt += GeoPoint(o.getDouble("latitude"), o.getDouble("longitude"))
            }
        }
        val cum = DoubleArray(tt.size)
        for (k in 1 until tt.size) cum[k] = cum[k - 1] + RouteRepository.dist(tt[k - 1].latitude, tt[k - 1].longitude, tt[k].latitude, tt[k].longitude)
        val jams = ArrayList<Jam>()
        val secs = route.optJSONArray("sections")
        if (secs != null) for (i in 0 until secs.length()) {
            val s = secs.getJSONObject(i)
            if (s.optString("sectionType") != "TRAFFIC") continue
            val si = s.optInt("startPointIndex", -1)
            val ei = s.optInt("endPointIndex", -1)
            if (si < 0 || ei <= si || ei >= tt.size) continue
            jams += Jam(
                points = tt.subList(si, ei + 1).toList(),
                startAlongM = fromAlong + cum[si],
                lengthM = cum[ei] - cum[si],
                delayS = s.optInt("delayInSeconds", 0),
                speedKmh = if (s.has("effectiveSpeedInKmh")) s.optInt("effectiveSpeedInKmh") else null,
                kind = s.optString("simpleCategory", "JAM"),
                magnitude = s.optInt("magnitudeOfDelay", 0),
            )
        }
        return Info(
            routeId = r.id,
            travelS = sum.optInt("travelTimeInSeconds", r.durationS.roundToInt()),
            delayS = sum.optInt("trafficDelayInSeconds", 0),
            lengthM = sum.optDouble("lengthInMeters", cum.lastOrNull() ?: 0.0),
            fromAlongM = fromAlong,
            jams = jams,
        )
    }

    /** Głosem: nowy, duży korek przed autem (raz na korek). */
    private fun announce(i: Info, along: Double) {
        if (!Prefs.trafficVoice.value) return
        val j = i.jams.firstOrNull { it.startAlongM > along && it.startAlongM - along < 15_000 && (it.delayS >= 180 || it.kind == "ROAD_CLOSURE") } ?: return
        val k = (j.startAlongM / 200).roundToInt().toLong() // ten sam korek → ten sam klucz
        if (!announced.add(k)) return
        val m = j.startAlongM - along
        val where = if (m < 950) "${(m / 100).roundToInt().coerceAtLeast(1) * 100} metrów" else {
            val n = (m / 1000).roundToInt()
            when {
                n == 1 -> "kilometr"
                n % 10 in 2..4 && n % 100 !in 12..14 -> "$n kilometry"
                else -> "$n kilometrów"
            }
        }
        val min = (j.delayS / 60.0).roundToInt()
        val text = when (j.kind) {
            "ROAD_CLOSURE" -> "Uwaga, droga zamknięta za $where"
            "ROAD_WORK" -> "Roboty drogowe za $where, około $min minut opóźnienia"
            else -> "Korek za $where, około $min minut opóźnienia"
        }
        NavVoice.say(text)
    }

    // ---------- licznik dzienny ----------

    private fun day() = Calendar.getInstance().let { it.get(Calendar.YEAR) * 1000 + it.get(Calendar.DAY_OF_YEAR) }

    private fun loadCounter() {
        val p = appCtx?.getSharedPreferences("traffic", Context.MODE_PRIVATE) ?: return
        usedToday.value = if (p.getInt("day", 0) == day()) p.getInt("n", 0) else 0
    }

    @Synchronized
    private fun countRequest(): Boolean {
        val p = appCtx?.getSharedPreferences("traffic", Context.MODE_PRIVATE) ?: return true
        val d = day()
        val n = if (p.getInt("day", 0) == d) p.getInt("n", 0) else 0
        if (n >= DAILY_CAP) return false
        p.edit().putInt("day", d).putInt("n", n + 1).apply()
        usedToday.value = n + 1
        return true
    }

    /** Sprawdzenie klucza w ustawieniach: krótka trasa z ruchem. */
    fun testKey(onResult: (String) -> Unit) {
        scope.launch {
            val key = Prefs.tomtomKey.value.trim()
            val msg = try {
                val l = LocationRepository.location.value
                val lat = l?.latitude ?: 52.73
                val lon = l?.longitude ?: 15.24
                val url = String.format(java.util.Locale.US, "https://api.tomtom.com/routing/1/calculateRoute/%.5f,%.5f:%.5f,%.5f/json?key=%s&traffic=true",
                    lat, lon, lat + 0.02, lon + 0.02, java.net.URLEncoder.encode(key, "UTF-8"))
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 10_000; c.readTimeout = 15_000
                try {
                    countRequest()
                    when (val code = c.responseCode) {
                        in 200..299 -> { error.value = null; "Klucz działa – korki włączone" }
                        401, 403 -> "Klucz nieprawidłowy (HTTP $code)"
                        else -> "TomTom odpowiedział błędem $code"
                    }
                } finally { c.disconnect() }
            } catch (e: Exception) {
                "Brak połączenia z TomTom – sprawdź internet"
            }
            onResult(msg)
        }
    }

    /** Adres kafelków natężenia ruchu (tylko odcinki wolniejsze niż zwykle). */
    fun flowTilesUrl(): String? {
        val key = Prefs.tomtomKey.value.trim()
        if (key.isBlank()) return null
        return "https://api.tomtom.com/traffic/map/4/tile/flow/relative-delay/{z}/{x}/{y}.png?tileSize=512&thickness=8&key=" +
            java.net.URLEncoder.encode(key, "UTF-8") + "&t=" + tick.value
    }
}
