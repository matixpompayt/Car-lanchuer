package pl.mati.carlauncher.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import kotlin.math.max

/**
 * Dziennik tras: jazda zaczyna się sama po ruszeniu, kończy po 3 min postoju.
 * Bieżąca jazda zapisywana co 30 s – radio może zgasnąć bez ostrzeżenia przy wyłączeniu zapłonu.
 */
object TripRepository {

    data class Trip(
        val start: Long,
        val end: Long,
        val distanceM: Double,
        val movingS: Long,
        val maxSpeed: Int,
        val maxCoolant: Int?,
    ) {
        val avgKmh: Double get() = if (movingS > 0) distanceM / movingS * 3.6 else 0.0
        val durationS: Long get() = (end - start) / 1000

        fun toJson(): JSONObject = JSONObject()
            .put("s", start).put("e", end).put("d", distanceM).put("m", movingS).put("v", maxSpeed)
            .put("c", maxCoolant ?: JSONObject.NULL)

        companion object {
            fun from(o: JSONObject) = Trip(
                o.getLong("s"), o.getLong("e"), o.getDouble("d"), o.getLong("m"), o.getInt("v"),
                if (o.isNull("c")) null else o.getInt("c"),
            )
        }
    }

    data class Summary(val count: Int, val distanceM: Double, val durationS: Long)

    val trips = MutableStateFlow<List<Trip>>(emptyList())
    val current = MutableStateFlow<Trip?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false
    private const val MAX_TRIPS = 300

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
        load()
        scope.launch { loop() }
    }

    private suspend fun loop() {
        var lastMeters = LocationRepository.tripMeters.value
        var stillSince = 0L
        var movingCount = 0
        var lastSave = 0L
        while (true) {
            delay(2000)
            val now = System.currentTimeMillis()
            val obd = ObdManager.data.value
            val spd = obd.speed ?: LocationRepository.gpsSpeed.value ?: 0
            val meters = LocationRepository.tripMeters.value
            val gpsDelta = (meters - lastMeters).coerceAtLeast(0f)
            lastMeters = meters
            // bez GPS liczymy z prędkości z OBD
            val delta = if (gpsDelta > 0f) gpsDelta.toDouble() else spd / 3.6 * 2.0

            val cur = current.value
            if (cur == null) {
                movingCount = if (spd >= 8) movingCount + 1 else 0
                if (movingCount >= 2) {
                    current.value = Trip(now, now, 0.0, 0, spd, obd.coolant)
                    stillSince = 0L
                    recordPoint(reset = true)
                }
                continue
            }
            val moving = spd >= 3
            current.value = cur.copy(
                end = now,
                distanceM = cur.distanceM + if (moving || gpsDelta > 5f) delta else 0.0,
                movingS = cur.movingS + if (moving) 2 else 0,
                maxSpeed = max(cur.maxSpeed, spd),
                maxCoolant = listOfNotNull(cur.maxCoolant, obd.coolant).maxOrNull(),
            )
            if (moving) ServiceRepository.addMeters(delta)
            recordPoint(reset = false)
            if (!moving) {
                if (stillSince == 0L) stillSince = now
                if (now - stillSince > 180_000) {
                    finish()
                    stillSince = 0L
                    movingCount = 0
                }
            } else {
                stillSince = 0L
            }
            if (now - lastSave > 30_000) {
                saveCurrent()
                ServiceRepository.save()
                lastSave = now
            }
        }
    }

    private fun finish() {
        val cur = current.value ?: return
        current.value = null
        if (cur.distanceM >= 300) {
            trips.value = (listOf(cur) + trips.value).take(MAX_TRIPS)
            saveTrips()
            val pts = synchronized(path) { ArrayList(path) }
            val enc = if (pts.size >= 2) Polyline.compact(pts) else null
            if (enc != null) {
                try {
                    dir?.let { d ->
                        d.mkdirs()
                        java.io.File(d, "${cur.start}.pl").writeText(enc)
                        // stare ślady (poza listą tras) – usuń
                        val keep = trips.value.map { "${it.start}.pl" }.toSet()
                        d.listFiles()?.forEach { f -> if (f.name !in keep) f.delete() }
                    }
                } catch (_: Exception) {
                }
            }
            ConnectRepository.publishTrip(cur, enc)
            ConnectRepository.publishStatusSoon()
        }
        synchronized(path) { path.clear() }
        Prefs.store?.edit()?.remove("trip_current")?.remove("trip_current_path")?.apply()
        ServiceRepository.save()
    }

    fun clearAll() {
        trips.value = emptyList()
        saveTrips()
    }

    fun summary(sinceMs: Long): Summary {
        val list = trips.value.filter { it.start >= sinceMs } + listOfNotNull(current.value?.takeIf { it.start >= sinceMs })
        return Summary(list.size, list.sumOf { it.distanceM }, list.sumOf { it.durationS })
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

    private fun load() {
        val sp = Prefs.store ?: return
        try {
            val arr = JSONArray(sp.getString("trips", "[]"))
            trips.value = (0 until arr.length()).map { Trip.from(arr.getJSONObject(it)) }
        } catch (_: Exception) {
        }
        // jazda przerwana zgaszeniem radia
        val curStr = sp.getString("trip_current", null) ?: return
        try {
            sp.getString("trip_current_path", null)?.let { p ->
                synchronized(path) { path.clear(); path.addAll(Polyline.decode(p)) }
            }
            val t = Trip.from(JSONObject(curStr))
            if (System.currentTimeMillis() - t.end < 10 * 60_000) {
                current.value = t // krótki postój (np. stacja) – kontynuujemy tę samą jazdę
            } else {
                current.value = t
                finish()
            }
        } catch (_: Exception) {
            sp.edit().remove("trip_current").apply()
        }
    }

    private fun saveTrips() {
        val arr = JSONArray()
        trips.value.forEach { arr.put(it.toJson()) }
        Prefs.store?.edit()?.putString("trips", arr.toString())?.apply()
    }

    private fun saveCurrent() {
        val c = current.value ?: return
        val pts = synchronized(path) { ArrayList(path) }
        Prefs.store?.edit()
            ?.putString("trip_current", c.toJson().toString())
            ?.putString("trip_current_path", Polyline.encode(pts))
            ?.apply()
    }
}
