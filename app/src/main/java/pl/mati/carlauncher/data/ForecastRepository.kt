package pl.mati.carlauncher.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.roundToInt

/** Prognoza (Open-Meteo, bez klucza) dla pozycji auta i opady na wyznaczonej trasie. */
object ForecastRepository {

    data class Hour(val time: Long, val temp: Double, val precip: Double, val prob: Int, val code: Int)
    data class Day(val time: Long, val max: Double, val min: Double, val precipSum: Double, val code: Int)
    data class Forecast(val temp: Double, val code: Int, val wind: Double, val hours: List<Hour>, val days: List<Day>, val fetched: Long)

    /** Pogoda na trasie: pierwszy odcinek z opadem (km od startu, przybliżona godzina). */
    data class RouteWeather(val rain: Boolean, val code: Int, val kmAhead: Int?, val atTime: Long?, val text: String)

    val forecast = MutableStateFlow<Forecast?>(null)
    val routeWeather = MutableStateFlow<RouteWeather?>(null)
    val error = MutableStateFlow<String?>(null)
    /** Nazwa miejscowości dla prognozy (Nominatim reverse). */
    val place = MutableStateFlow<String?>(null)
    private var placeLat = 0.0
    private var placeLon = 0.0
    /** Granice obecnej miejscowości: minLat, maxLat, minLon, maxLon. */
    private var placeBox: DoubleArray? = null
    private var lastReverse = 0L
    private var lastForecast = 0L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            while (true) {
                val ok = refreshNow()
                delay(if (ok) 30 * 60_000L else 60_000L)
            }
        }
        // Miejscowość: od razu po wyjeździe z jej granic, w zapasie co 2,5 km
        scope.launch {
            while (true) {
                val loc = LocationRepository.location.value
                if (loc != null) checkPlace(loc.latitude, loc.longitude)
                delay(5000)
            }
        }
        scope.launch {
            RouteRepository.route.collect { r ->
                if (r == null) routeWeather.value = null else computeRoute(r)
            }
        }
    }

    fun refresh() {
        scope.launch { refreshNow() }
    }

    private fun refreshNow(): Boolean {
        val loc = LocationRepository.location.value ?: return false
        return try {
            val url = String.format(
                Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f" +
                    "&current=temperature_2m,weather_code,wind_speed_10m" +
                    "&hourly=temperature_2m,precipitation,precipitation_probability,weather_code" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum" +
                    "&timeformat=unixtime&timezone=auto&forecast_days=6",
                loc.latitude, loc.longitude,
            )
            val j = JSONObject(get(url))
            val cur = j.getJSONObject("current")
            val h = j.getJSONObject("hourly")
            val ht = h.getJSONArray("time")
            val now = System.currentTimeMillis()
            val hours = (0 until ht.length()).map { i ->
                Hour(
                    ht.getLong(i) * 1000,
                    h.getJSONArray("temperature_2m").optDouble(i, 0.0),
                    h.getJSONArray("precipitation").optDouble(i, 0.0),
                    h.getJSONArray("precipitation_probability").optInt(i, 0),
                    h.getJSONArray("weather_code").optInt(i, 0),
                )
            }.filter { it.time + 3_600_000 > now }.take(24)
            val d = j.getJSONObject("daily")
            val dt = d.getJSONArray("time")
            val days = (0 until dt.length()).map { i ->
                Day(
                    dt.getLong(i) * 1000,
                    d.getJSONArray("temperature_2m_max").optDouble(i, 0.0),
                    d.getJSONArray("temperature_2m_min").optDouble(i, 0.0),
                    d.getJSONArray("precipitation_sum").optDouble(i, 0.0),
                    d.getJSONArray("weather_code").optInt(i, 0),
                )
            }
            val f = Forecast(cur.optDouble("temperature_2m"), cur.optInt("weather_code"), cur.optDouble("wind_speed_10m"), hours, days, now)
            forecast.value = f
            lastForecast = System.currentTimeMillis()
            WeatherRepository.temperature.value = f.temp.roundToInt()
            error.value = null
            true
        } catch (e: Exception) {
            error.value = "Brak połączenia z serwerem pogody"
            false
        }
    }

    private fun checkPlace(lat: Double, lon: Double) {
        val now = System.currentTimeMillis()
        val box = placeBox
        val margin = 0.003 // ~300 m tolerancji na krawędzi
        val outside = box != null && (lat < box[0] - margin || lat > box[1] + margin || lon < box[2] - margin || lon > box[3] + margin)
        val far = RouteRepository.dist(placeLat, placeLon, lat, lon) > 2500
        if (place.value != null && !outside && !far) return
        if (now - lastReverse < 15_000) return // limit zapytań
        lastReverse = now
        try {
            val j = JSONObject(
                get(
                    String.format(
                        Locale.US,
                        "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=13&accept-language=pl&lat=%.5f&lon=%.5f",
                        lat, lon,
                    ),
                ),
            )
            val a = j.optJSONObject("address")
            val name = listOf("city", "town", "village", "hamlet", "suburb", "municipality", "county")
                .firstNotNullOfOrNull { k -> a?.optString(k)?.takeIf { it.isNotBlank() } }
            placeLat = lat
            placeLon = lon
            val bb = j.optJSONArray("boundingbox")
            // granice tylko gdy to prawdziwy obszar (punkt miejscowości ma „pudełko” kilkudziesięciu metrów)
            placeBox = if (bb != null && bb.length() == 4) {
                val box = doubleArrayOf(bb.getString(0).toDouble(), bb.getString(1).toDouble(), bb.getString(2).toDouble(), bb.getString(3).toDouble())
                if (box[1] - box[0] > 0.008 && box[3] - box[2] > 0.012) box else null
            } else null
            val changed = name != null && name != place.value
            if (name != null) place.value = name
            // nowa miejscowość → świeża pogoda (najwyżej co 5 min)
            if ((changed || far) && now - lastForecast > 5 * 60_000) refreshNow()
        } catch (_: Exception) {
        }
    }

    private fun computeRoute(r: RouteRepository.Route) {
        scope.launch {
            try {
                val fractions = listOf(0.15, 0.3, 0.45, 0.6, 0.75, 0.9, 1.0)
                val pts = fractions.map { f ->
                    val target = r.distanceM * f
                    val idx = r.cumulative.indexOfFirst { it >= target }.let { if (it < 0) r.points.lastIndex else it }
                    Triple(f, r.points[idx], r.cumulative[idx])
                }
                val lats = pts.joinToString(",") { String.format(Locale.US, "%.3f", it.second.latitude) }
                val lons = pts.joinToString(",") { String.format(Locale.US, "%.3f", it.second.longitude) }
                val body = get(
                    "https://api.open-meteo.com/v1/forecast?latitude=$lats&longitude=$lons" +
                        "&hourly=precipitation,weather_code&timeformat=unixtime&forecast_days=2",
                )
                val arr = JSONArray(body)
                val start = System.currentTimeMillis()
                for (i in pts.indices) {
                    val (f, _, distAt) = pts[i]
                    val eta = start + (r.durationS * f * 1000).toLong()
                    val h = arr.getJSONObject(i).getJSONObject("hourly")
                    val t = h.getJSONArray("time")
                    var k = 0
                    while (k < t.length() - 1 && t.getLong(k + 1) * 1000 <= eta) k++
                    val p = h.getJSONArray("precipitation").optDouble(k, 0.0)
                    val code = h.getJSONArray("weather_code").optInt(k, 0)
                    if (p >= 0.2 || code in 51..67 || code in 71..86 || code >= 95) {
                        val km = (distAt / 1000).roundToInt()
                        val what = when {
                            code >= 95 -> "Burza"
                            code in 71..77 || code in 85..86 -> "Śnieg"
                            code in 51..57 -> "Mżawka"
                            else -> "Deszcz"
                        }
                        routeWeather.value = RouteWeather(true, code, km, eta, if (km <= 1) "$what na starcie" else "$what za ok. $km km")
                        return@launch
                    }
                }
                routeWeather.value = RouteWeather(false, 0, null, null, "Bez opadów na trasie")
            } catch (_: Exception) {
                routeWeather.value = null
            }
        }
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "CarOS/1.1 (Android head unit launcher; pl.mati.carlauncher)")
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        try {
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
