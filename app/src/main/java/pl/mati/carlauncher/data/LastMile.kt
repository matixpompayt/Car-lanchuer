package pl.mati.carlauncher.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Ostatnia mila: cel ostatniej trasy (do 3 h po jeździe). Gdy auto stanie 150 m – 3 km od celu,
 * wiadomość „park” niesie cel – CAR OS Connect poprowadzi dalej pieszo chodnikami.
 */
object LastMile {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    fun init() {
        if (started) return
        started = true
        scope.launch {
            RouteRepository.route.filterNotNull().collect { r ->
                Prefs.store?.edit()
                    ?.putString("lm_dest", JSONObject().put("n", r.dest.name).put("d", r.dest.detail).put("lat", r.dest.lat).put("lon", r.dest.lon).toString())
                    ?.putLong("lm_at", System.currentTimeMillis())?.apply()
            }
        }
    }

    /** Cel do dojścia pieszo od miejsca postoju (lat, lon) albo null. */
    fun destFor(lat: Double, lon: Double): JSONObject? {
        val sp = Prefs.store ?: return null
        // trasa trwa – ostatni zapis to teraz; po jeździe – najwyżej 3 h
        val at = if (RouteRepository.route.value != null) System.currentTimeMillis() else sp.getLong("lm_at", 0L)
        if (System.currentTimeMillis() - at > 3 * 3600_000L) return null
        val d = try { JSONObject(sp.getString("lm_dest", null) ?: return null) } catch (_: Exception) { return null }
        val dl = d.optDouble("lat", Double.NaN)
        val dn = d.optDouble("lon", Double.NaN)
        if (dl.isNaN() || dn.isNaN()) return null
        val dist = RouteRepository.dist(lat, lon, dl, dn)
        if (dist < 150 || dist > 3000) return null
        // dom – pod domem zwykle bez prowadzenia
        val p = Prefs.profile.value
        if (p.homeLat != null && p.homeLon != null && RouteRepository.dist(dl, dn, p.homeLat, p.homeLon) < 150) return null
        return d.put("m", dist.toInt())
    }
}
