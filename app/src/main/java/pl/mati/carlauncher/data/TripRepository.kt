package pl.mati.carlauncher.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import kotlin.math.max

/**
 * Dziennik tras: jazda zaczyna się sama po ruszeniu, kończy po 3 min postoju.
 * Bieżąca jazda zapisywana co 30 s – radio może zgasnąć bez ostrzeżenia przy wyłączeniu zapłonu.
 *
 * Zabezpieczenia (żeby zapisywało się za każdym razem):
 * – pętla nie może „umrzeć” od błędu: każdy krok w try/catch, a cała pętla jest wznawiana,
 * – po uśpieniu radia (zapłon wył.) albo przerwie > 5 min stara jazda jest kończona, zanim zacznie się nowa
 *   (wcześniej mogła „doklejać” kolejne dni do jednej niekończącej się jazdy),
 * – prędkość: świeży GPS i licznik auta sprawdzane razem (zamrożony odczyt CAN nie blokuje startu ani końca),
 * – zapis od razu na dysk (commit) w dwóch miejscach: ustawienia + plik trips/trips.json; przy odczycie bierzemy pełniejszy,
 * – ręczne „Zakończ jazdę” i krótki dziennik zdarzeń w ekranie Dziennik tras.
 */
object TripRepository {

    data class Trip(
        val start: Long,
        val end: Long,
        val distanceM: Double,
        val movingS: Long,
        val maxSpeed: Int,
        val maxCoolant: Int?,
        /** Gwałtowne przyspieszenia / hamowania (z prędkości GPS). */
        val accel: Int = 0,
        val brake: Int = 0,
    ) {
        val avgKmh: Double get() = if (movingS > 0) distanceM / movingS * 3.6 else 0.0
        val durationS: Long get() = (end - start) / 1000
        /** Płynność jazdy 0–100: mniej gwałtownych manewrów na 10 km = więcej punktów. */
        val score: Int get() = smoothness(accel, brake, distanceM)

        fun toJson(): JSONObject = JSONObject()
            .put("s", start).put("e", end).put("d", distanceM).put("m", movingS).put("v", maxSpeed)
            .put("c", maxCoolant ?: JSONObject.NULL).put("ha", accel).put("hb", brake)

        companion object {
            fun from(o: JSONObject) = Trip(
                o.getLong("s"), o.getLong("e"), o.getDouble("d"), o.getLong("m"), o.getInt("v"),
                if (o.isNull("c")) null else o.getInt("c"),
                o.optInt("ha", 0), o.optInt("hb", 0),
            )
        }
    }

    fun smoothness(accel: Int, brake: Int, distanceM: Double): Int {
        val per10 = (accel + brake * 1.5) / (maxOf(distanceM / 1000.0, 5.0) / 10.0)
        return (100 - per10 * 12).toInt().coerceIn(0, 100)
    }

    data class Summary(
        val count: Int, val distanceM: Double, val durationS: Long,
        val movingS: Long = 0, val maxSpeed: Int = 0, val accel: Int = 0, val brake: Int = 0,
    ) {
        val avgKmh: Double get() = if (movingS > 0) distanceM / movingS * 3.6 else 0.0
        val score: Int get() = smoothness(accel, brake, distanceM)
    }

    val trips = MutableStateFlow<List<Trip>>(emptyList())
    val current = MutableStateFlow<Trip?>(null)
    /** Co robi rejestrator (do ekranu Dziennik tras). */
    val status = MutableStateFlow("uruchamiam…")
    /** Ostatnie zdarzenia (start, koniec, błędy) – najnowsze na górze. */
    val events = MutableStateFlow<List<String>>(emptyList())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false
    private const val MAX_TRIPS = 500
    /** Przerwa w działaniu pętli (uśpienie radia), po której bieżąca jazda jest kończona. */
    private const val GAP_MS = 60_000L
    /** Jazda bez aktualizacji dłużej niż to – też kończona. */
    private const val STALE_MS = 5 * 60_000L
    @Volatile private var finishRequested = false

    private fun log(msg: String) {
        val t = java.text.SimpleDateFormat("dd.MM HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        events.value = (listOf("$t  $msg") + events.value).take(40)
        try {
            dir?.let { d ->
                d.mkdirs()
                val f = java.io.File(d, "log.txt")
                val old = if (f.exists()) f.readLines().take(200) else emptyList()
                f.writeText((listOf("$t  $msg") + old).joinToString("\n"))
            }
        } catch (_: Exception) {
        }
    }

    /** Ręczne zakończenie bieżącej jazdy (przycisk w Dzienniku tras). */
    fun finishNow() { finishRequested = true }

    /** Folder na ślady GPS tras (ustawiany w MainActivity). */
    var dir: java.io.File? = null
    /** Ślad bieżącej jazdy: [lat, lon]. */
    private val path = ArrayList<DoubleArray>()

    /** Ślad zapisanej trasy (kodowany) albo null. */
    fun pathOf(t: Trip): String? = try {
        dir?.let { java.io.File(it, "${t.start}.pl") }?.takeIf { it.exists() }?.readText()
    } catch (_: Exception) {
        null
    }

    private fun recordPoint(reset: Boolean) {
        val l = LocationRepository.location.value ?: return
        if (reset) synchronized(path) { path.clear() }
        if (l.provider != android.location.LocationManager.GPS_PROVIDER || l.accuracy > 35f) return
        if (System.currentTimeMillis() - l.time > 10_000) return
        synchronized(path) {
            val last = path.lastOrNull()
            if (last != null) {
                val out = FloatArray(1)
                android.location.Location.distanceBetween(last[0], last[1], l.latitude, l.longitude, out)
                if (out[0] < 20f) return
            }
            path += doubleArrayOf(l.latitude, l.longitude)
        }
    }

    fun start() {
        if (started) return
        started = true
        try { load() } catch (e: Exception) { log("Błąd odczytu: ${e.javaClass.simpleName}") }
        // pętla jest wznawiana, gdyby cokolwiek ją przerwało
        scope.launch {
            while (true) {
                try {
                    loop()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    log("Pętla przerwana (${e.javaClass.simpleName}: ${e.message?.take(80)}) – wznawiam")
                }
                delay(3000)
            }
        }
        scope.launch {
            while (true) {
                try { harshLoop() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Throwable) {}
                delay(3000)
            }
        }
        // zapłon wyłączony: od razu zapisz bieżącą jazdę na dysk (radio może zaraz zgasnąć)
        scope.launch {
            var offAt = 0L
            TsCarBridge.acc.collect { on ->
                try {
                    if (on == false) {
                        offAt = System.currentTimeMillis()
                        saveCurrent()
                        if (current.value != null) log("Zapłon wyłączony – jazda zapisana")
                    } else if (on == true && offAt > 0 && System.currentTimeMillis() - offAt > 180_000 && current.value != null) {
                        log("Zapłon włączony po przerwie – kończę poprzednią jazdę")
                        finishRequested = true
                    }
                } catch (_: Throwable) {
                }
            }
        }
    }

    /** Prędkość do wykrywania jazdy: świeży GPS + licznik auta (rozbieżny odczyt CAN = pewnie zamrożony). */
    private fun speedNow(): Int {
        val g = LocationRepository.freshGpsSpeed()
        val v = Vehicle.data.value.speed
        return when {
            g == null -> v ?: 0
            v == null -> g
            kotlin.math.abs(v - g) <= 25 -> max(v, g)
            else -> g
        }
    }

    /**
     * Gwałtowne manewry z prędkości GPS (1 odczyt/s): przyspieszenie > 3 m/s² albo
     * hamowanie < −3,5 m/s² (ok. 13 km/h na sekundę). Między zdarzeniami min. 4 s.
     */
    private suspend fun harshLoop() {
        var lastV = -1f
        var lastT = 0L
        var lastEvent = 0L
        LocationRepository.location.collect { l ->
            if (l == null || !l.hasSpeed()) return@collect
            val t = l.time
            val v = l.speed
            val dt = (t - lastT) / 1000f
            if (lastV >= 0 && dt in 0.5f..2.5f && l.accuracy < 30f) {
                val a = (v - lastV) / dt
                val cur = current.value
                if (cur != null && t - lastEvent > 4000) {
                    if (a > 3.0f && v > 3f) {
                        current.update { c -> c?.copy(accel = c.accel + 1) }; lastEvent = t
                    } else if (a < -3.5f && lastV > 4f) {
                        current.update { c -> c?.copy(brake = c.brake + 1) }; lastEvent = t
                    }
                }
            }
            lastV = v
            lastT = t
        }
    }

    private suspend fun loop() {
        var lastMeters = LocationRepository.tripMeters.value
        var stillSince = 0L
        var movingCount = 0
        var lastSave = 0L
        var lastTick = android.os.SystemClock.elapsedRealtime()
        var lastKickCheck = 0L
        while (true) {
            delay(2000)
            try {
                val now = System.currentTimeMillis()
                val tick = android.os.SystemClock.elapsedRealtime()
                val gap = tick - lastTick
                lastTick = tick
                val obd = Vehicle.data.value
                val spd = speedNow()
                val meters = LocationRepository.tripMeters.value
                val gpsDelta = (meters - lastMeters).coerceAtLeast(0f)
                lastMeters = meters
                // bez GPS liczymy z prędkości (licznik auta / OBD)
                val delta = if (gpsDelta > 0f) gpsDelta.toDouble() else spd / 3.6 * 2.0
                if (tick - lastKickCheck > 30_000) { lastKickCheck = tick; LocationRepository.kickIfStale() }

                var cur = current.value
                // radio spało / pętla stała albo ręczne zakończenie → zamknij starą jazdę, zanim policzymy nową
                if (cur != null && (finishRequested || gap > GAP_MS || now - cur.end > STALE_MS)) {
                    val why = when {
                        finishRequested -> "ręcznie"
                        gap > GAP_MS -> "po uśpieniu radia (${gap / 1000} s)"
                        else -> "brak aktualizacji ${(now - cur.end) / 60_000} min"
                    }
                    finishRequested = false
                    finish("koniec $why")
                    stillSince = 0L
                    movingCount = 0
                    cur = null
                }
                finishRequested = false

                if (cur == null) {
                    movingCount = if (spd >= 8) movingCount + 1 else 0
                    status.value = if (spd > 0) "czekam na ruszenie (${spd} km/h)" else "czekam na ruszenie"
                    if (movingCount >= 2) {
                        current.value = Trip(now, now, 0.0, 0, spd, obd.coolant)
                        stillSince = 0L
                        recordPoint(reset = true)
                        log("Start jazdy (${spd} km/h)")
                        saveCurrent()
                        lastSave = now
                    }
                    continue
                }
                val moving = spd >= 3
                current.update { c ->
                    c?.copy(
                        end = now,
                        distanceM = c.distanceM + if (moving || gpsDelta > 5f) delta else 0.0,
                        movingS = c.movingS + if (moving) 2 else 0,
                        maxSpeed = max(c.maxSpeed, spd),
                        maxCoolant = listOfNotNull(c.maxCoolant, obd.coolant).maxOrNull(),
                    )
                }
                if (moving) try { ServiceRepository.addMeters(delta) } catch (_: Exception) {}
                recordPoint(reset = false)
                if (!moving) {
                    if (stillSince == 0L) stillSince = now
                    status.value = "postój ${(now - stillSince) / 1000} s (koniec po 180 s)"
                    if (now - stillSince > 180_000) {
                        finish("koniec po 3 min postoju")
                        stillSince = 0L
                        movingCount = 0
                    }
                } else {
                    stillSince = 0L
                    status.value = "jazda trwa (${spd} km/h)"
                }
                if (now - lastSave > 30_000) {
                    saveCurrent()
                    try { ServiceRepository.save() } catch (_: Exception) {}
                    lastSave = now
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                log("Błąd w kroku: ${e.javaClass.simpleName}: ${e.message?.take(80)}")
            }
        }
    }

    private fun finish(why: String = "koniec") {
        val cur = current.value ?: return
        current.value = null
        val pts = synchronized(path) { ArrayList(path) }
        synchronized(path) { path.clear() }
        if (cur.distanceM >= 300) {
            // najważniejsze: zapis jazdy (od razu na dysk)
            trips.value = (listOf(cur) + trips.value.filter { it.start != cur.start }).take(MAX_TRIPS)
            saveTrips()
            log("Zapisano jazdę ${"%.1f".format(cur.distanceM / 1000)} km ($why)")
            val enc = try { if (pts.size >= 2) Polyline.compact(pts) else null } catch (_: Exception) { null }
            if (enc != null) {
                try {
                    dir?.let { d ->
                        d.mkdirs()
                        java.io.File(d, "${cur.start}.pl").writeText(enc)
                        // stare ślady (poza listą tras) – usuń
                        val keep = trips.value.map { "${it.start}.pl" }.toSet() + setOf("trips.json", "trips.json.tmp", "log.txt")
                        d.listFiles()?.forEach { f -> if (f.name !in keep) f.delete() }
                    }
                } catch (_: Exception) {
                }
            }
            try { ConnectRepository.publishTrip(cur, enc) } catch (_: Exception) {}
            try { ConnectRepository.publishStatusSoon() } catch (_: Exception) {}
        } else {
            log("Pominięto krótką jazdę ${cur.distanceM.toInt()} m ($why)")
        }
        try { FuelRepository.recompute() } catch (_: Exception) {}
        try { ConnectRepository.publishLiveStop() } catch (_: Exception) {}
        try { Prefs.store?.edit()?.remove("trip_current")?.remove("trip_current_path")?.commit() } catch (_: Exception) {}
        try { ServiceRepository.save() } catch (_: Exception) {}
    }

    fun clearAll() {
        trips.value = emptyList()
        saveTrips()
    }

    fun summary(sinceMs: Long): Summary {
        val list = trips.value.filter { it.start >= sinceMs } + listOfNotNull(current.value?.takeIf { it.start >= sinceMs })
        return Summary(
            list.size, list.sumOf { it.distanceM }, list.sumOf { it.durationS },
            list.sumOf { it.movingS }, list.maxOfOrNull { it.maxSpeed } ?: 0, list.sumOf { it.accel }, list.sumOf { it.brake },
        )
    }

    /** Kilometry w kolejnych dniach od [fromMs] (np. tydzień: 7 wartości). */
    fun perDay(fromMs: Long, days: Int): List<Double> {
        val all = trips.value + listOfNotNull(current.value)
        return (0 until days).map { i ->
            val a = fromMs + i * 86_400_000L
            val b = a + 86_400_000L
            all.filter { it.start in a until b }.sumOf { it.distanceM } / 1000.0
        }
    }

    fun startOfDay(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun startOfWeek(): Long = Calendar.getInstance().apply {
        firstDayOfWeek = Calendar.MONDAY
        set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        if (timeInMillis > System.currentTimeMillis()) add(Calendar.WEEK_OF_YEAR, -1)
    }.timeInMillis

    fun startOfMonth(): Long = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    // ---------- zapis ----------

    private fun parse(text: String?): List<Trip> = try {
        val arr = JSONArray(text ?: "[]")
        (0 until arr.length()).mapNotNull { i -> try { Trip.from(arr.getJSONObject(i)) } catch (_: Exception) { null } }
    } catch (_: Exception) {
        emptyList()
    }

    private fun backupFile() = dir?.let { java.io.File(it, "trips.json") }

    private fun load() {
        val sp = Prefs.store ?: return
        // dwa zapisy – bierzemy sumę (gdyby któryś się uszkodził albo nie zdążył zapisać)
        val a = parse(sp.getString("trips", "[]"))
        val b = try { backupFile()?.takeIf { it.exists() }?.readText()?.let { parse(it) }.orEmpty() } catch (_: Exception) { emptyList() }
        val merged = (a + b).distinctBy { it.start }.sortedByDescending { it.start }.take(MAX_TRIPS)
        trips.value = merged
        if (b.size > a.size || a.size > b.size) saveTrips()
        log("Wczytano ${merged.size} jazd")
        // jazda przerwana zgaszeniem radia
        val curStr = sp.getString("trip_current", null) ?: return
        try {
            sp.getString("trip_current_path", null)?.let { p ->
                synchronized(path) { path.clear(); path.addAll(Polyline.decode(p)) }
            }
            val t = Trip.from(JSONObject(curStr))
            current.value = t
            if (System.currentTimeMillis() - t.end < 10 * 60_000) {
                log("Kontynuuję jazdę po krótkiej przerwie")
            } else {
                finish("koniec po wyłączeniu radia")
            }
        } catch (_: Exception) {
            sp.edit().remove("trip_current").commit()
        }
    }

    private fun saveTrips() {
        val arr = JSONArray()
        trips.value.forEach { arr.put(it.toJson()) }
        val text = arr.toString()
        try { Prefs.store?.edit()?.putString("trips", text)?.commit() } catch (_: Exception) {}
        // kopia w pliku – zapis przez plik tymczasowy, żeby nie zostawić połowy przy zaniku prądu
        try {
            dir?.let { d ->
                d.mkdirs()
                val tmp = java.io.File(d, "trips.json.tmp")
                tmp.writeText(text)
                val f = java.io.File(d, "trips.json")
                if (!tmp.renameTo(f)) { f.writeText(text); tmp.delete() }
            }
        } catch (_: Exception) {
        }
    }

    private fun saveCurrent() {
        val c = current.value ?: return
        val pts = synchronized(path) { ArrayList(path) }
        try {
            Prefs.store?.edit()
                ?.putString("trip_current", c.toJson().toString())
                ?.putString("trip_current_path", Polyline.encode(pts))
                ?.commit()
        } catch (_: Exception) {
        }
    }
}
