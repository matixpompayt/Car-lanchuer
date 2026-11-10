package pl.mati.carlauncher.data

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import pl.mati.carlauncher.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Ostrzeżenia o fotoradarach i odcinkowym pomiarze prędkości z danych OpenStreetMap.
 *  – fotoradary: węzły highway=speed_camera (te same dane co znaki na mapie, offline albo Overpass),
 *  – odcinki: relacje enforcement=average_speed (plik sections.json z wydania speed-data, co tydzień).
 * W Niemczech ostrzeganie kierowcy jest zabronione (§23 ust. 1c StVO) – tam wszystko jest wyłączone.
 */
object RoadWatch {

    data class Camera(val id: Long, val dist: Int, val limit: Int?, val over: Boolean)

    enum class SPhase { AHEAD, IN, DONE }

    data class Section(
        val id: Long, val name: String, val phase: SPhase,
        /** AHEAD: do początku; IN: do końca (m). */
        val dist: Int,
        val length: Int,
        val limit: Int?,
        /** Średnia od początku odcinka (km/h). */
        val avg: Int?,
        /** Ile można jechać do końca, żeby średnia zmieściła się w limicie (null – i tak się mieści). */
        val allowed: Int?,
        val progress: Float,
    )

    data class State(val camera: Camera? = null, val section: Section? = null, val blocked: String? = null)

    val state = MutableStateFlow(State())
    /** Do ustawień: ile odcinków znamy i skąd. */
    val info = MutableStateFlow("Odcinki: jeszcze nie pobrano")

    private class Sec(
        val id: Long, val name: String, val limit: Int?,
        val lat: DoubleArray, val lon: DoubleArray, val cum: DoubleArray,
    ) {
        val length get() = cum.last()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false
    private var appCtx: Context? = null
    @Volatile private var sections: List<Sec> = emptyList()
    private var tone: ToneGenerator? = null

    // fotoradar
    private var camId = -1L
    private var camLast = Double.MAX_VALUE
    private var camBeeped = false
    private val camDone = HashMap<Long, Long>()

    // odcinek
    private var secAhead: Sec? = null
    private var secIn: Sec? = null
    private var secT0 = 0L
    private var secS = 0.0
    private var secOff = 0
    private var secLastAvg: Int? = null
    private var secWarned = false
    private var secDoneAt = 0L
    private val secAnnounced = HashMap<Long, Long>()

    private var heading: Double? = null

    fun start(ctx: Context) {
        if (started) return
        started = true
        appCtx = ctx.applicationContext
        scope.launch { load() }
        scope.launch {
            while (isActive) {
                try { tick() } catch (_: Exception) {}
                delay(1000)
            }
        }
        scope.launch(Dispatchers.IO) {
            delay(20_000)
            while (isActive) {
                try { update() } catch (_: Exception) {}
                delay(6 * 3600_000L)
            }
        }
    }

    // ---------- dane odcinków ----------

    private fun file() = File(appCtx!!.filesDir, "sections.json")

    private fun load() {
        val f = file()
        if (!f.exists()) return
        try {
            val arr = JSONArray(f.readText())
            val out = ArrayList<Sec>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val p = o.getJSONArray("path")
                val n = p.length() / 2
                if (n < 2) continue
                val la = DoubleArray(n) { p.getDouble(it * 2) }
                val lo = DoubleArray(n) { p.getDouble(it * 2 + 1) }
                val cum = DoubleArray(n)
                for (k in 1 until n) cum[k] = cum[k - 1] + RouteRepository.dist(la[k - 1], lo[k - 1], la[k], lo[k])
                if (cum.last() < 300) continue
                out += Sec(o.optLong("id"), o.optString("name"), o.optInt("max", 0).takeIf { it > 0 }, la, lo, cum)
            }
            sections = out
            info.value = "Odcinki pomiaru: ${out.size} (OpenStreetMap, " +
                java.text.SimpleDateFormat("d.MM.yyyy", java.util.Locale.ROOT).format(java.util.Date(f.lastModified())) + ")"
        } catch (_: Exception) {
        }
    }

    private fun update() {
        val ctx = appCtx ?: return
        val p = ctx.getSharedPreferences("road_watch", Context.MODE_PRIVATE)
        val c = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/tags/speed-data").openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "CarOS")
        val json = try {
            if (c.responseCode !in 200..299) return
            JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally { c.disconnect() }
        val assets = json.optJSONArray("assets") ?: return
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name") != "sections.json") continue
            val stamp = a.optString("updated_at")
            if (stamp == p.getString("stamp", null) && file().exists()) return
            val d = URL(a.optString("browser_download_url")).openConnection() as HttpURLConnection
            d.connectTimeout = 15_000; d.readTimeout = 30_000; d.instanceFollowRedirects = true
            d.setRequestProperty("User-Agent", "CarOS")
            val body = try {
                if (d.responseCode !in 200..299) return
                d.inputStream.bufferedReader().use { it.readText() }
            } finally { d.disconnect() }
            JSONArray(body) // sprawdź, czy to poprawny JSON
            val tmp = File(file().parentFile, "sections.json.part")
            tmp.writeText(body)
            if (!tmp.renameTo(file())) { file().delete(); tmp.renameTo(file()) }
            p.edit().putString("stamp", stamp).apply()
            load()
            return
        }
    }

    // ---------- co sekundę ----------

    private fun tick() {
        val camOn = Prefs.cameraAlerts.value
        val secOn = Prefs.sectionAlerts.value
        if (!camOn && !secOn) { reset(); return }
        val l = LocationRepository.location.value ?: return
        if (isGermany(l.latitude, l.longitude)) {
            reset()
            state.value = State(blocked = "W Niemczech ostrzeganie o fotoradarach jest zabronione – wyłączone")
            return
        }
        val kmh = LocationRepository.gpsSpeed.value ?: (l.speed * 3.6f).roundToInt()
        if (l.hasBearing() && l.speed > 2.5f) heading = l.bearing.toDouble()
        val head = heading
        val lim = SpeedLimitRepository.limit.value
        val over = lim != null && kmh > lim + Prefs.speedTolerance.value

        val sec = if (secOn) sectionTick(l.latitude, l.longitude, kmh, head) else { secIn = null; secAhead = null; null }
        val cam = if (camOn) cameraTick(l.latitude, l.longitude, kmh, head, lim, over, sec) else { camId = -1; null }
        state.value = State(camera = cam, section = sec)
    }

    private fun reset() {
        camId = -1; secIn = null; secAhead = null
        if (state.value != State()) state.value = State()
    }

    private fun cameraTick(lat: Double, lon: Double, kmh: Int, head: Double?, lim: Int?, over: Boolean, sec: Section?): Camera? {
        val now = SystemClock.elapsedRealtime()
        camDone.entries.removeAll { now - it.value > 10 * 60_000 }
        val cams = SpeedLimitRepository.signs.value.filter { it.kind == "camera" }
        if (camId >= 0) {
            val c = cams.firstOrNull { it.id == camId }
            val d = c?.let { RouteRepository.dist(lat, lon, it.lat, it.lon) }
            val rel = if (c != null && head != null) angle(head, bearing(lat, lon, c.lat, c.lon)) else 0.0
            // minięty: odległość rośnie tuż przy nim, został za autem albo zniknął z danych
            if (d == null || (d > camLast + 12 && camLast < 150) || d > 1600 || (rel > 100 && d < 400)) {
                camDone[camId] = now
                camId = -1
                return null
            }
            camLast = min(camLast, d)
            if (over && !camBeeped && d < 300) { camBeeped = true; if (Prefs.cameraSound.value) beep(2) }
            return Camera(camId, d.roundToInt(), lim, over)
        }
        if (head == null || kmh < 15) return null
        val look = (kmh * 12.0).coerceIn(500.0, 1000.0)
        var best: SpeedLimitRepository.Sign? = null
        var bestD = Double.MAX_VALUE
        for (c in cams) {
            if (camDone.containsKey(c.id)) continue
            val d = RouteRepository.dist(lat, lon, c.lat, c.lon)
            if (d > look || d < 25) continue
            val rel = angle(head, bearing(lat, lon, c.lat, c.lon))
            if (rel > 35) continue
            val lateral = d * sin(Math.toRadians(rel))
            if (lateral > max(45.0, d * 0.18)) continue
            // urządzenie odcinka – osobny komunikat
            if (sec != null && near(sec.id, c.lat, c.lon)) continue
            if (d < bestD) { bestD = d; best = c }
        }
        val b = best ?: return null
        camId = b.id
        camLast = bestD
        camBeeped = over
        if (Prefs.cameraSound.value) {
            beep(if (over) 3 else 2)
            val m = (bestD / 100).roundToInt().coerceAtLeast(1) * 100
            NavVoice.say("Fotoradar za $m metrów" + if (over && lim != null) ". Zwolnij, ograniczenie $lim" else "", force = true)
        }
        return Camera(b.id, bestD.roundToInt(), lim, over)
    }

    private fun near(secId: Long, lat: Double, lon: Double): Boolean {
        val s = sections.firstOrNull { it.id == secId } ?: return false
        return RouteRepository.dist(lat, lon, s.lat[0], s.lon[0]) < 200 || RouteRepository.dist(lat, lon, s.lat.last(), s.lon.last()) < 200
    }

    private fun sectionTick(lat: Double, lon: Double, kmh: Int, head: Double?): Section? {
        val now = SystemClock.elapsedRealtime()
        secAnnounced.entries.removeAll { now - it.value > 15 * 60_000 }
        val cur = secIn
        if (cur != null) {
            val (s, lateral) = project(cur, lat, lon)
            if (lateral > 150) secOff++ else { secOff = 0; secS = max(secS, s) }
            if (secOff >= 20) { secIn = null; return null } // zjechał z odcinka
            val t = (now - secT0) / 1000.0
            val lim = cur.limit ?: SpeedLimitRepository.limit.value
            val avg = if (secS > 150 && t > 8) (secS / t * 3.6).roundToInt() else null
            val rem = (cur.length - secS).coerceAtLeast(0.0)
            if (rem < 25 || RouteRepository.dist(lat, lon, cur.lat.last(), cur.lon.last()) < 30) {
                secIn = null
                secDoneAt = now
                val total = (cur.length / t.coerceAtLeast(1.0) * 3.6).roundToInt()
                secLastAvg = total
                if (Prefs.cameraSound.value) NavVoice.say("Koniec odcinkowego pomiaru. Średnia $total kilometrów na godzinę", force = true)
                return Section(cur.id, cur.name, SPhase.DONE, 0, cur.length.roundToInt(), lim, total, null, 1f)
            }
            var allowed: Int? = null
            if (lim != null) {
                val need = cur.length / (lim / 3.6) - t // ile sekund jeszcze „trzeba” jechać
                allowed = if (need <= 0) null else (rem / need * 3.6).roundToInt().takeIf { it < lim }
                if (avg != null && avg > lim + 2 && !secWarned) {
                    secWarned = true
                    if (Prefs.cameraSound.value) { beep(2); NavVoice.say("Średnia za wysoka. Zwolnij do ${allowed ?: lim}", force = true) }
                }
                if (avg != null && avg <= lim) secWarned = false
            }
            return Section(cur.id, cur.name, SPhase.IN, rem.roundToInt(), cur.length.roundToInt(), lim, avg, allowed, (secS / cur.length).toFloat().coerceIn(0f, 1f))
        }
        // podsumowanie po wyjeździe z odcinka – 8 s
        if (secDoneAt > 0 && now - secDoneAt < 8000) {
            val st = state.value.section
            if (st != null && st.phase == SPhase.DONE) return st
        }
        if (head == null || kmh < 10) { secAhead = null; return null }
        val list = sections
        if (list.isEmpty()) return null
        // wjazd: auto na początku odcinka i jedzie w jego kierunku
        for (s in list) {
            val d0 = RouteRepository.dist(lat, lon, s.lat[0], s.lon[0])
            if (d0 > 1500) continue
            val (pos, lateral) = project(s, lat, lon)
            val dir = bearing(s.lat[0], s.lon[0], s.lat[min(1, s.lat.lastIndex)], s.lon[min(1, s.lon.lastIndex)])
            if (pos < 200 && lateral < 35 && d0 < 220 && angle(head, segBearing(s, pos)) < 50) {
                secIn = s
                secAhead = null
                secS = pos
                secOff = 0
                secWarned = false
                // spóźnienie wykrycia (1 Hz) – wjazd liczymy od faktycznego przejazdu przez początek
                secT0 = now - (pos / (kmh.coerceAtLeast(10) / 3.6) * 1000).toLong()
                if (Prefs.cameraSound.value && secAnnounced[s.id] == null) {
                    beep(1)
                    NavVoice.say("Początek odcinkowego pomiaru" + (s.limit?.let { ", ograniczenie $it" } ?: ""), force = true)
                }
                secAnnounced[s.id] = now
                return Section(s.id, s.name, SPhase.IN, s.length.roundToInt(), s.length.roundToInt(), s.limit ?: SpeedLimitRepository.limit.value, null, null, 0f)
            }
            // przed odcinkiem: początek przed autem i kierunek zgodny z odcinkiem
            if (d0 in 30.0..1200.0 && angle(head, bearing(lat, lon, s.lat[0], s.lon[0])) < 40 && angle(head, dir) < 60) {
                if (secAhead?.id != s.id) {
                    secAhead = s
                    if (Prefs.cameraSound.value && secAnnounced[s.id] == null) {
                        secAnnounced[s.id] = now
                        beep(2)
                        val m = (d0 / 100).roundToInt().coerceAtLeast(1) * 100
                        NavVoice.say("Odcinkowy pomiar prędkości za $m metrów" + (s.limit?.let { ", ograniczenie $it" } ?: ""), force = true)
                    }
                }
                return Section(s.id, s.name, SPhase.AHEAD, d0.roundToInt(), s.length.roundToInt(), s.limit, null, null, 0f)
            }
        }
        secAhead = null
        return null
    }

    /** Rzut pozycji na trasę odcinka: (metry od początku, odległość od trasy). */
    private fun project(s: Sec, lat: Double, lon: Double): Pair<Double, Double> {
        val kx = 111_320.0 * cos(Math.toRadians(lat))
        val ky = 110_540.0
        var best = Double.MAX_VALUE
        var bestPos = 0.0
        for (i in 0 until s.lat.lastIndex) {
            val ax = (s.lon[i] - lon) * kx; val ay = (s.lat[i] - lat) * ky
            val bx = (s.lon[i + 1] - lon) * kx; val by = (s.lat[i + 1] - lat) * ky
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 < 1e-6) 0.0 else ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0)
            val px = ax + dx * t; val py = ay + dy * t
            val d = Math.sqrt(px * px + py * py)
            if (d < best) { best = d; bestPos = s.cum[i] + (s.cum[i + 1] - s.cum[i]) * t }
        }
        return bestPos to best
    }

    private fun segBearing(s: Sec, pos: Double): Double {
        var i = 0
        while (i < s.cum.lastIndex - 1 && s.cum[i + 1] < pos) i++
        return bearing(s.lat[i], s.lon[i], s.lat[i + 1], s.lon[i + 1])
    }

    // ---------- pomocnicze ----------

    private fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    private fun angle(a: Double, b: Double): Double {
        val d = abs(a - b) % 360
        return if (d > 180) 360 - d else d
    }

    private fun beep(n: Int) {
        scope.launch {
            try {
                val tg = tone ?: ToneGenerator(AudioManager.STREAM_MUSIC, 90).also { tone = it }
                repeat(n) {
                    tg.startTone(ToneGenerator.TONE_PROP_BEEP, 160)
                    delay(260)
                }
            } catch (_: Exception) {
            }
        }
    }

    /** Granica Polska–Niemcy (w przybliżeniu, wystarcza do decyzji „po której stronie”). */
    private val BORDER = arrayOf(
        doubleArrayOf(53.93, 14.23), doubleArrayOf(53.55, 14.27), doubleArrayOf(53.33, 14.40), doubleArrayOf(53.25, 14.45),
        doubleArrayOf(53.03, 14.30), doubleArrayOf(52.81, 14.13), doubleArrayOf(52.59, 14.62), doubleArrayOf(52.35, 14.55),
        doubleArrayOf(52.07, 14.75), doubleArrayOf(51.95, 14.72), doubleArrayOf(51.53, 14.72), doubleArrayOf(51.15, 14.99),
        doubleArrayOf(50.88, 14.83),
    )

    fun isGermany(lat: Double, lon: Double): Boolean {
        if (lat < 47.2 || lat > 55.1 || lon < 5.8 || lon > 15.1) return false
        if (lat < 50.9) return lon < 12.6 // na południe – Czechy od wschodu; tu tylko orientacyjnie
        if (lon < 14.1) return true
        if (lon > 15.05) return false
        // szukaj odcinka granicy na tej szerokości
        for (i in 0 until BORDER.lastIndex) {
            val a = BORDER[i]; val b = BORDER[i + 1]
            val hi = max(a[0], b[0]); val lo = min(a[0], b[0])
            if (lat in lo..hi) {
                val t = if (hi - lo < 1e-9) 0.0 else (lat - a[0]) / (b[0] - a[0])
                val bl = a[1] + (b[1] - a[1]) * t
                return lon < bl
            }
        }
        return lat > 53.93 && lon < 14.23
    }
}
