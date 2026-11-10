package pl.mati.carlauncher.data

import android.content.Context
import android.location.Geocoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale

/**
 * Podpowiedź celu z Dziennika tras: skąd i o której zwykle jedziesz w taki dzień, dokąd wtedy dojeżdżasz.
 * Np. w dzień roboczy rano spod domu → praca, wieczorem z pracy → dom. Liczy się po każdym zapłonie.
 */
object DestinationHints {

    enum class Kind { HOME, WORK, PLACE }

    data class Hint(val kind: Kind, val name: String, val detail: String, val lat: Double, val lon: Double, val minutes: Int, val times: Int)

    val hint = MutableStateFlow<Hint?>(null)

    private class Ends(val sLat: Double, val sLon: Double, val eLat: Double, val eLon: Double)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** start trasy → punkty początku i końca (z pliku śladu, liczone raz). */
    private val ends = HashMap<Long, Ends?>()
    private var job: kotlinx.coroutines.Job? = null

    /** Po zapłonie (AutostartManager). */
    fun onIgnition(ctx: Context) {
        if (!Prefs.destSuggest.value) return
        job?.cancel()
        job = scope.launch {
            hint.value = null
            // poczekaj na pozycję (GPS po zapłonie potrzebuje chwili)
            var found: android.location.Location? = null
            val until = System.currentTimeMillis() + 90_000
            while (found == null && System.currentTimeMillis() < until) {
                val l = LocationRepository.location.value
                if (l != null && l.accuracy < 300 && System.currentTimeMillis() - l.time < 120_000) found = l else delay(1500)
            }
            val loc = found ?: return@launch
            val h = compute(ctx, loc.latitude, loc.longitude, System.currentTimeMillis()) ?: return@launch
            delay(5000)
            // grafik zmian / trasa już wyznaczona – nie wchodzimy w drogę
            if (AutostartManager.suggestion.value != null || RouteRepository.route.value != null) return@launch
            if (LocationRepository.location.value?.let { RouteRepository.dist(it.latitude, it.longitude, loc.latitude, loc.longitude) > 800 } == true) return@launch
            hint.value = h
            delay(40_000)
            if (hint.value == h) hint.value = null
        }
    }

    fun dismiss() { hint.value = null }

    private fun endsOf(t: TripRepository.Trip): Ends? {
        synchronized(ends) { if (ends.containsKey(t.start)) return ends[t.start] }
        val e = try {
            val enc = TripRepository.pathOf(t)
            val pts = enc?.let { Polyline.decode(it) }
            if (pts == null || pts.size < 2) null else Ends(pts.first()[0], pts.first()[1], pts.last()[0], pts.last()[1])
        } catch (_: Exception) {
            null
        }
        synchronized(ends) { ends[t.start] = e }
        return e
    }

    private fun minuteOfDay(ms: Long): Int {
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    private fun weekend(ms: Long): Boolean {
        val d = Calendar.getInstance().apply { timeInMillis = ms }.get(Calendar.DAY_OF_WEEK)
        return d == Calendar.SATURDAY || d == Calendar.SUNDAY
    }

    fun compute(ctx: Context, lat: Double, lon: Double, now: Long): Hint? {
        val nowMin = minuteOfDay(now)
        val we = weekend(now)
        val since = now - 120L * 24 * 3600_000
        val cand = ArrayList<Pair<TripRepository.Trip, Ends>>()
        for (t in TripRepository.trips.value) {
            if (t.start < since || t.distanceM < 1000) continue
            if (weekend(t.start) != we) continue
            val dm = Math.abs(minuteOfDay(t.start) - nowMin).let { minOf(it, 1440 - it) }
            if (dm > 75) continue
            val e = endsOf(t) ?: continue
            if (RouteRepository.dist(lat, lon, e.sLat, e.sLon) > 1000) continue
            if (RouteRepository.dist(lat, lon, e.eLat, e.eLon) < 1000) continue // pętla / powrót w to samo miejsce
            cand += t to e
        }
        if (cand.size < 2) return null
        // grupuj końce w promieniu 400 m – wybierz najliczniejszą grupę
        var best: List<Pair<TripRepository.Trip, Ends>> = emptyList()
        for ((_, e) in cand) {
            val g = cand.filter { RouteRepository.dist(e.eLat, e.eLon, it.second.eLat, it.second.eLon) < 400 }
            if (g.size > best.size || (g.size == best.size && g.maxOf { it.first.start } > (best.maxOfOrNull { it.first.start } ?: 0L))) best = g
        }
        if (best.size < 2 || best.size * 10 < cand.size * 4) return null
        val cLat = best.map { it.second.eLat }.average()
        val cLon = best.map { it.second.eLon }.average()
        val mins = best.map { it.first.durationS / 60 }.sorted().let { it[it.size / 2] }.toInt().coerceAtLeast(1)
        val (kind, name) = nameFor(ctx, cLat, cLon)
        val detail = "zwykle $mins min · ${best.size}× o tej porze"
        return Hint(kind, name, detail, cLat, cLon, mins, best.size)
    }

    private fun nameFor(ctx: Context, lat: Double, lon: Double): Pair<Kind, String> {
        val p = Prefs.profile.value
        if (p.homeLat != null && p.homeLon != null && RouteRepository.dist(lat, lon, p.homeLat, p.homeLon) < 350) return Kind.HOME to "Dom"
        if (p.workLat != null && p.workLon != null && RouteRepository.dist(lat, lon, p.workLat, p.workLon) < 350) return Kind.WORK to "Praca"
        PlacesRepository.favorites.value.firstOrNull { RouteRepository.dist(lat, lon, it.lat, it.lon) < 300 }?.let { return Kind.PLACE to it.name }
        val addr = try {
            @Suppress("DEPRECATION")
            Geocoder(ctx, Locale("pl", "PL")).getFromLocation(lat, lon, 1)?.firstOrNull()
        } catch (_: Exception) {
            null
        }
        val street = addr?.thoroughfare?.let { s -> addr.subThoroughfare?.let { "$s $it" } ?: s }
        val town = addr?.locality ?: addr?.subAdminArea
        val n = listOfNotNull(street, town).joinToString(", ").ifBlank { "Częsty cel" }
        return Kind.PLACE to n
    }
}
