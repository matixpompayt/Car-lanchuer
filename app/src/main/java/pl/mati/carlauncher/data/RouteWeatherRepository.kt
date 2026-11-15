package pl.mati.carlauncher.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pogoda na trasie (Open-Meteo, bez klucza).
 *  – prognoza pobierana dla punktów co ok. 3 km na pozostałej części trasy (co 15 min dokładności, max 40 punktów),
 *  – przeliczana co 1 km jazdy: dla każdego punktu bierzemy pogodę na godzinę, o której tam dojedziesz
 *    (pozostały czas z korkami), i składamy odcinki: deszcz, śnieg, marznący deszcz, burza, mgła, wiatr, mróz,
 *  – nowe dane co 20 min albo po zmianie trasy.
 * Wynik trafia do [ForecastRepository.routeWeather] (mapa i ekran Pogoda).
 */
object RouteWeatherRepository {

    private class Sample(
        val along: Double, val lat: Double, val lon: Double,
        val times: LongArray, val precip: DoubleArray, val code: IntArray, val temp: DoubleArray,
        val vis: DoubleArray?, val gust: DoubleArray?,
    )

    private data class Cond(val kind: String, val name: String, val severity: Int)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    @Volatile private var samples: List<Sample> = emptyList()
    private var dataRoute = -1L
    private var fetchedAt = 0L
    private var lastAlong = -1e12
    private var lastCompute = 0L

    private const val REFETCH_MS = 20 * 60_000L
    private const val STEP_M = 1000.0

    fun start() {
        if (started) return
        started = true
        scope.launch {
            while (isActive) {
                try { tick() } catch (_: Exception) {}
                delay(4000)
            }
        }
    }

    private fun tick() {
        val r = RouteRepository.route.value
        if (r == null) {
            if (ForecastRepository.routeWeather.value != null) ForecastRepository.routeWeather.value = null
            samples = emptyList(); dataRoute = -1L; lastAlong = -1e12
            return
        }
        val l = LocationRepository.location.value
        val along = if (l != null) alongOf(r, l.latitude, l.longitude) else 0.0
        val now = System.currentTimeMillis()
        // nowe dane: inna trasa, stare dane albo wyjechaliśmy poza pobrany zakres
        val stale = dataRoute != r.id || now - fetchedAt > REFETCH_MS || samples.isEmpty() ||
            (samples.isNotEmpty() && along < samples.first().along - 3000)
        if (stale) {
            if (dataRoute != r.id) { ForecastRepository.routeWeather.value = null; lastAlong = -1e12 }
            if (fetch(r, along)) { dataRoute = r.id; fetchedAt = now; lastAlong = -1e12 }
            else if (dataRoute != r.id) return
        }
        // przeliczenie co 1 km (i co minutę – czas też płynie)
        if (kotlin.math.abs(along - lastAlong) >= STEP_M || now - lastCompute > 60_000) {
            lastAlong = along
            lastCompute = now
            compute(r, along, now)
        }
    }

    private fun alongOf(r: RouteRepository.Route, lat: Double, lon: Double): Double {
        var best = Double.MAX_VALUE
        var bi = 0
        for (i in r.points.indices) {
            val p = r.points[i]
            val d = RouteRepository.dist(lat, lon, p.latitude, p.longitude)
            if (d < best) { best = d; bi = i }
        }
        return r.cumulative[bi]
    }

    // ---------- pobieranie ----------

    private fun fetch(r: RouteRepository.Route, along: Double): Boolean {
        val remaining = r.distanceM - along
        if (remaining < 200) return false
        val n = ceil(remaining / 3000.0).toInt().coerceIn(1, 39)
        val spacing = remaining / n
        val pts = (0..n).map { i ->
            val a = min(r.distanceM, along + spacing * i)
            val idx = r.cumulative.indexOfFirst { it >= a }.let { if (it < 0) r.points.lastIndex else it }
            Triple(a, r.points[idx].latitude, r.points[idx].longitude)
        }
        val lats = pts.joinToString(",") { String.format(Locale.US, "%.3f", it.second) }
        val lons = pts.joinToString(",") { String.format(Locale.US, "%.3f", it.third) }
        val base = "https://api.open-meteo.com/v1/forecast?latitude=$lats&longitude=$lons&timeformat=unixtime&forecast_days=2"
        val hourly = "&hourly=precipitation,weather_code,temperature_2m,visibility,wind_gusts_10m"
        // najpierw z danymi co 15 min; gdyby serwer ich nie przyjął – same godzinowe
        val body = try {
            get(base + hourly + "&minutely_15=precipitation,weather_code,temperature_2m,visibility,wind_gusts_10m&forecast_minutely_15=96")
        } catch (_: Exception) {
            try { get(base + hourly) } catch (_: Exception) { return false }
        }
        val arr: JSONArray = body.trim().let { if (it.startsWith("[")) JSONArray(it) else JSONArray().put(JSONObject(it)) }
        val out = ArrayList<Sample>(pts.size)
        for (i in pts.indices) {
            val o = arr.optJSONObject(i) ?: continue
            // 15-minutowe tam, gdzie są; dalej godzinowe
            val q = o.optJSONObject("minutely_15")
            val h = o.optJSONObject("hourly")
            val s = merge(q, h) ?: continue
            out += Sample(pts[i].first, pts[i].second, pts[i].third, s.times, s.precip, s.code, s.temp, s.vis, s.gust)
        }
        if (out.isEmpty()) return false
        samples = out
        return true
    }

    private class Series(val times: LongArray, val precip: DoubleArray, val code: IntArray, val temp: DoubleArray, val vis: DoubleArray?, val gust: DoubleArray?)

    private fun series(o: JSONObject?, perHour: Boolean): Series? {
        o ?: return null
        val t = o.optJSONArray("time") ?: return null
        if (t.length() == 0) return null
        fun d(name: String): DoubleArray? = o.optJSONArray(name)?.let { a -> DoubleArray(t.length()) { a.optDouble(it, Double.NaN) } }
        val p = d("precipitation") ?: return null
        val c = o.optJSONArray("weather_code")?.let { a -> IntArray(t.length()) { a.optInt(it, 0) } } ?: return null
        val tmp = d("temperature_2m") ?: DoubleArray(t.length()) { Double.NaN }
        // opad 15-minutowy w mm na 15 min → mm/h, żeby progi były te same
        if (!perHour) for (k in p.indices) if (!p[k].isNaN()) p[k] *= 4.0
        return Series(LongArray(t.length()) { t.getLong(it) * 1000 }, p, c, tmp, d("visibility"), d("wind_gusts_10m"))
    }

    private fun merge(q: JSONObject?, h: JSONObject?): Series? {
        val a = series(q, perHour = false)
        val b = series(h, perHour = true)
        if (a == null) return b
        if (b == null) return a
        // 15-min do końca ich zakresu, potem godzinowe
        val end = a.times.last()
        val tail = b.times.indices.filter { b.times[it] > end }
        val n = a.times.size + tail.size
        val times = LongArray(n); val precip = DoubleArray(n); val code = IntArray(n); val temp = DoubleArray(n)
        val vis = if (a.vis != null && b.vis != null) DoubleArray(n) else null
        val gust = if (a.gust != null && b.gust != null) DoubleArray(n) else null
        for (k in a.times.indices) {
            times[k] = a.times[k]; precip[k] = a.precip[k]; code[k] = a.code[k]; temp[k] = a.temp[k]
            vis?.set(k, a.vis!![k]); gust?.set(k, a.gust!![k])
        }
        for ((j, k) in tail.withIndex()) {
            val x = a.times.size + j
            times[x] = b.times[k]; precip[x] = b.precip[k]; code[x] = b.code[k]; temp[x] = b.temp[k]
            vis?.set(x, b.vis!![k]); gust?.set(x, b.gust!![k])
        }
        return Series(times, precip, code, temp, vis, gust)
    }

    // ---------- przeliczanie ----------

    private fun cond(code: Int, precip: Double, temp: Double, vis: Double, gust: Double): Cond? {
        val p = if (precip.isNaN()) 0.0 else precip
        val snowCode = code in 71..77 || code in 85..86
        return when {
            code >= 95 -> Cond("storm", "Burza", 6)
            code in 56..57 || code in 66..67 || (p >= 0.1 && !temp.isNaN() && temp <= 0.5 && !snowCode) -> Cond("freezing", "Marznący deszcz – ślisko", 7)
            snowCode || (p >= 0.1 && !temp.isNaN() && temp <= -1.0) -> Cond("snow", if (code == 75 || code == 86 || p >= 2.0) "Intensywny śnieg" else "Śnieg", 5)
            code == 65 || code == 82 || p >= 4.0 -> Cond("rain", "Ulewa", 4)
            code in 61..64 || code in 80..81 || p >= 0.5 -> Cond("rain", "Deszcz", 3)
            code in 51..55 || p >= 0.1 -> Cond("drizzle", "Mżawka", 2)
            code in 45..48 || (!vis.isNaN() && vis < 400) -> Cond("fog", "Mgła", 3)
            !gust.isNaN() && gust >= 70 -> Cond("wind", "Silny wiatr", 3)
            !temp.isNaN() && temp <= 0.0 -> Cond("frost", "Mróz – możliwa ślizgawica", 1)
            else -> null
        }
    }

    private fun idxAt(s: Sample, t: Long): Int {
        var k = 0
        while (k < s.times.size - 1 && s.times[k + 1] <= t) k++
        return k
    }

    private fun compute(r: RouteRepository.Route, along: Double, now: Long) {
        val list = samples
        if (list.isEmpty()) return
        val remainingM = max(1.0, r.distanceM - along)
        val remS = RouteRepository.progress.value?.remainingS ?: (r.durationS * remainingM / max(1.0, r.distanceM))
        val spacing = if (list.size >= 2) list[1].along - list[0].along else 3000.0
        // punkty przed autem (i ten, w którym właśnie jesteśmy)
        val ahead = list.filter { it.along >= along - spacing / 2 }
        if (ahead.isEmpty()) { ForecastRepository.routeWeather.value = null; return }

        data class At(val km: Double, val time: Long, val c: Cond?, val code: Int, val temp: Double)
        val at = ahead.map { s ->
            val dist = max(0.0, s.along - along)
            val eta = now + (remS * dist / remainingM * 1000).toLong()
            val k = idxAt(s, eta)
            val c = cond(s.code[k], s.precip[k], s.temp[k], s.vis?.get(k) ?: Double.NaN, s.gust?.get(k) ?: Double.NaN)
            At(dist / 1000.0, eta, c, s.code[k], s.temp[k])
        }
        // sklej kolejne punkty z tym samym zjawiskiem w odcinki
        val events = ArrayList<ForecastRepository.WxEvent>()
        var i = 0
        while (i < at.size) {
            val c = at[i].c
            if (c == null) { i++; continue }
            var j = i
            var worst = at[i]
            while (j + 1 < at.size && at[j + 1].c?.kind == c.kind) {
                j++
                if ((at[j].c?.severity ?: 0) > (worst.c?.severity ?: 0)) worst = at[j]
            }
            val from = at[i].km
            val to = min(remainingM / 1000.0, at[j].km + spacing / 2000.0)
            events += ForecastRepository.WxEvent(
                kind = c.kind, name = worst.c?.name ?: c.name, fromKm = from, toKm = to, atTime = at[i].time,
                code = worst.code, temp = if (worst.temp.isNaN()) null else worst.temp,
            )
            i = j + 1
        }
        val destTemp = at.last().temp.takeIf { !it.isNaN() }
        // najważniejsze: pierwsze zjawisko inne niż mróz; mróz tylko, gdy nic innego
        val main = events.firstOrNull { it.kind != "frost" } ?: events.firstOrNull()
        val rw = if (main == null) {
            ForecastRepository.RouteWeather(false, 0, null, null, "Bez opadów do celu", events, destTemp, now)
        } else {
            val inside = main.fromKm < 0.6
            val text = when {
                main.kind == "frost" -> {
                    val low = events.filter { it.kind == "frost" }.mapNotNull { it.temp }.minOrNull()
                    "Mróz na trasie" + (low?.let { " (do ${it.roundToInt()}°)" } ?: "") + " – uważaj na ślizgawicę"
                }
                inside -> "${main.name} – jeszcze ok. ${kmText(main.toKm)}"
                else -> "${main.name} za ${kmText(main.fromKm)} (ok. ${hhmm(main.atTime)})" +
                    (if (main.toKm - main.fromKm >= 2) ", przez ${kmText(main.toKm - main.fromKm)}" else "")
            }
            ForecastRepository.RouteWeather(
                rain = main.kind != "frost", code = main.code, kmAhead = main.fromKm.roundToInt(), atTime = main.atTime,
                text = text, events = events, destTemp = destTemp, updatedAt = now, kind = main.kind,
            )
        }
        ForecastRepository.routeWeather.value = rw
    }

    private fun kmText(km: Double): String = when {
        km < 1 -> "${((km * 1000) / 100).roundToInt().coerceAtLeast(1) * 100} m"
        km < 10 -> String.format(Locale("pl"), "%.1f km", km).replace(",0 km", " km")
        else -> "${km.roundToInt()} km"
    }

    private fun hhmm(t: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = t }
        return String.format(Locale.ROOT, "%d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "CarOS (Android head unit launcher; pl.mati.carlauncher)")
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        try {
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
