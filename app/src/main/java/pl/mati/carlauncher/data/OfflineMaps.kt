package pl.mati.carlauncher.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import pl.mati.carlauncher.ui.CarMapStyle

/**
 * Mapy bez internetu (MapLibre): większa pamięć oglądanych okolic + pobieranie
 * okolicy auta i korytarza trasy dom ↔ praca (do zoomu 14 – pełne dane z budynkami).
 */
object OfflineMaps {

    /** Grupa obszarów do pokazania w ustawieniach (np. „okolica”, „dojazd”). */
    data class Area(val group: String, val done: Long, val required: Long, val bytes: Long, val complete: Boolean, val error: String?)

    val areas = MutableStateFlow<Map<String, Area>>(emptyMap())
    val busy = MutableStateFlow<String?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val regions = HashMap<String, OfflineRegion>()          // klucz regionu → region
    private val status = HashMap<String, OfflineRegionStatus>()      // klucz regionu → stan
    private val errors = HashMap<String, String>()
    private var appCtx: Context? = null

    private fun om(): OfflineManager? = appCtx?.let { OfflineManager.getInstance(it) }

    fun init(ctx: Context) {
        if (appCtx != null) return
        appCtx = ctx.applicationContext
        try {
            MapLibre.getInstance(ctx.applicationContext)
            val m = OfflineManager.getInstance(ctx.applicationContext)
            m.setMaximumAmbientCacheSize(800L * 1024 * 1024, null) // 800 MB oglądanych okolic
            m.setOfflineMapboxTileCountLimit(400_000L)
            refresh()
        } catch (_: Throwable) {
        }
    }

    fun refresh() {
        val m = om() ?: return
        m.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                offlineRegions?.forEach { r ->
                    val key = keyOf(r) ?: return@forEach
                    regions[key] = r
                    watch(key, r)
                    r.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                        override fun onStatus(status: OfflineRegionStatus?) {
                            if (status != null) update(key, status)
                        }
                        override fun onError(error: String?) {}
                    })
                }
                publish()
            }
            override fun onError(error: String) {}
        })
    }

    private fun keyOf(r: OfflineRegion): String? = try { JSONObject(String(r.metadata)).optString("k").ifBlank { null } } catch (_: Exception) { null }

    private fun watch(key: String, r: OfflineRegion) {
        r.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) {
                update(key, status)
                if (status.isComplete) r.setDownloadState(0)
            }
            override fun onError(error: OfflineRegionError) {
                errors[key] = "Błąd pobierania – sprawdź internet"
                publish()
            }
            override fun mapboxTileCountLimitExceeded(limit: Long) {
                errors[key] = "Za duży obszar"
                publish()
            }
        })
    }

    private fun update(key: String, s: OfflineRegionStatus) {
        status[key] = s
        errors.remove(key)
        publish()
    }

    private fun publish() {
        val out = HashMap<String, Area>()
        for (key in regions.keys) {
            val g = key.substringBefore('#')
            val s = status[key]
            val prev = out[g]
            out[g] = Area(
                g,
                (prev?.done ?: 0) + (s?.completedResourceCount ?: 0),
                (prev?.required ?: 0) + (s?.requiredResourceCount ?: 0),
                (prev?.bytes ?: 0) + (s?.completedResourceSize ?: 0),
                (prev?.complete ?: true) && (s?.isComplete ?: false),
                errors[key] ?: prev?.error,
            )
        }
        areas.value = out
    }

    private fun create(key: String, bounds: LatLngBounds) {
        val m = om() ?: return
        val def = OfflineTilePyramidRegionDefinition(CarMapStyle.STYLE_URL, bounds, 0.0, 14.0, 1f)
        val meta = JSONObject().put("k", key).toString().toByteArray()
        m.createOfflineRegion(def, meta, object : OfflineManager.CreateOfflineRegionCallback {
            override fun onCreate(offlineRegion: OfflineRegion) {
                regions[key] = offlineRegion
                watch(key, offlineRegion)
                offlineRegion.setDownloadState(1)
                publish()
            }
            override fun onError(error: String) {
                errors[key] = "Nie udało się zacząć pobierania"
                publish()
            }
        })
    }

    private fun box(lat: Double, lon: Double, km: Double): LatLngBounds {
        val dLat = km / 110.5
        val dLon = km / (111.3 * Math.cos(Math.toRadians(lat)))
        return LatLngBounds.Builder().include(LatLng(lat - dLat, lon - dLon)).include(LatLng(lat + dLat, lon + dLon)).build()
    }

    /** Okolica auta (kwadrat ±[km] od obecnej pozycji). */
    fun downloadAround(km: Double = 40.0): String? {
        val l = LocationRepository.location.value ?: return "Brak pozycji GPS"
        delete("okolica") {
            create("okolica#0", box(l.latitude, l.longitude, km))
        }
        return null
    }

    /** Korytarz ±7 km wzdłuż trasy dom ↔ praca, w odcinkach po ok. 25 km. */
    fun downloadCommute(): String? {
        val p = Prefs.profile.value
        if (p.home.isBlank() || p.work.isBlank()) return "Najpierw ustaw adres domu i pracy"
        busy.value = "dojazd"
        scope.launch {
            try {
                val home = if (p.homeLat != null && p.homeLon != null) RouteRepository.Place("Dom", p.home, p.homeLat, p.homeLon) else RouteRepository.geocode(p.home)
                val work = if (p.workLat != null && p.workLon != null) RouteRepository.Place("Praca", p.work, p.workLat, p.workLon) else RouteRepository.geocode(p.work)
                if (home == null || work == null) {
                    errors["dojazd#0"] = "Nie znaleziono adresu domu lub pracy"
                    return@launch
                }
                val line = RouteRepository.routeLine(home.lat, home.lon, work.lat, work.lon)
                if (line.size < 2) {
                    errors["dojazd#0"] = "Nie udało się wyznaczyć trasy"
                    return@launch
                }
                val chunks = ArrayList<LatLngBounds>()
                var b = LatLngBounds.Builder()
                var n = 0
                var acc = 0.0
                for (i in line.indices) {
                    val pt = line[i]
                    b.include(LatLng(pt.latitude, pt.longitude)); n++
                    if (i > 0) acc += RouteRepository.dist(line[i - 1].latitude, line[i - 1].longitude, pt.latitude, pt.longitude)
                    if (acc > 25_000 && n >= 2) {
                        chunks += b.build()
                        b = LatLngBounds.Builder().include(LatLng(pt.latitude, pt.longitude)); n = 1; acc = 0.0
                    }
                }
                if (n >= 2) chunks += b.build()
                withContext(Dispatchers.Main) {
                    delete("dojazd") {
                        chunks.forEachIndexed { i, c ->
                            val lat = c.center.latitude
                            val pad = 7.0
                            val dLat = pad / 110.5
                            val dLon = pad / (111.3 * Math.cos(Math.toRadians(lat)))
                            val big = LatLngBounds.Builder()
                                .include(LatLng(c.latitudeSouth - dLat, c.longitudeWest - dLon))
                                .include(LatLng(c.latitudeNorth + dLat, c.longitudeEast + dLon))
                                .build()
                            create("dojazd#$i", big)
                        }
                    }
                }
            } catch (_: Exception) {
                errors["dojazd#0"] = "Brak internetu"
            } finally {
                busy.value = null
                publish()
            }
        }
        return null
    }

    /** Usuwa całą grupę (np. „okolica”), potem [then]. */
    fun delete(group: String, then: () -> Unit = {}) {
        val keys = regions.keys.filter { it.substringBefore('#') == group }
        if (keys.isEmpty()) {
            then()
            return
        }
        var left = keys.size
        for (k in keys) {
            val r = regions.remove(k) ?: continue
            status.remove(k)
            errors.remove(k)
            try { r.setDownloadState(0) } catch (_: Exception) {}
            r.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                override fun onDelete() {
                    left--
                    if (left == 0) { publish(); then() }
                }
                override fun onError(error: String) {
                    left--
                    if (left == 0) { publish(); then() }
                }
            })
        }
        publish()
    }
}
