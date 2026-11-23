package pl.mati.carlauncher.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import pl.mati.caros.core.LiveShare
import pl.mati.caros.core.RelayCrypto

/**
 * Jazda na żywo przez link (strona w przeglądarce, bez aplikacji). Radio wysyła zaszyfrowane wiadomości
 * na osobny kanał przekaźnika: pozycja co 2 min w ruchu (i przy ruszeniu / zatrzymaniu), trasa przy zmianie,
 * koniec po dojechaniu albo po czasie. Strona między wiadomościami przesuwa auto wzdłuż trasy.
 * Przekaźnik ma limit ok. 250 wiadomości dziennie – stąd rzadko; zwykła pozycja do telefonu idzie wtedy co 3 min.
 */
object TripShare {
    /** [toDest] – kończy się po dojechaniu do celu; [until] – najpóźniej (ms); [by] – "car" albo "phone". */
    class Session(val key: ByteArray, val since: Long, val until: Long, val toDest: Boolean, val by: String) {
        val link: String get() = LiveShare.link(key)
    }

    val session = MutableStateFlow<Session?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false
    private var lastPos = 0L
    private var lastMoving: Boolean? = null
    private var lastRouteId = 0L
    private var lastRouteAt = 0L
    private var lastEtaMin = -1L

    fun init() {
        if (started) return
        started = true
        val sp = Prefs.store
        val k = LiveShare.keyFrom(sp?.getString("share_k", null))
        val until = sp?.getLong("share_until", 0L) ?: 0L
        if (k != null && until > System.currentTimeMillis()) {
            session.value = Session(k, sp!!.getLong("share_since", 0L), until, sp.getBoolean("share_dest", false), sp.getString("share_by", "car") ?: "car")
        } else if (k != null) {
            clear()
        }
        // pozycja
        scope.launch {
            LocationRepository.location.filterNotNull().collect { l ->
                try { onLocation(l) } catch (_: Exception) {}
            }
        }
        // trasa (nowa / przeliczona)
        scope.launch {
            RouteRepository.route.map { it?.id ?: 0L }.distinctUntilChanged().collect {
                val s = session.value ?: return@collect
                if (it != 0L) try { publishRoute(force = false) } catch (_: Exception) {}
                else {
                    // trasa zakończona / anulowana – strona od razu chowa linię trasy i cel
                    if (lastRouteId != 0L) try { clearRoute(s) } catch (_: Exception) {}
                    // „do celu”, a trasa zakończona bez dojechania – po 10 min bez nowej trasy koniec
                    if (s.toDest) scope.launch {
                        delay(10 * 60_000L)
                        if (RouteRepository.route.value == null && session.value === s) stop("stopped")
                    }
                }
            }
        }
        // dojechanie do celu
        scope.launch {
            RouteRepository.progress.map { it?.arrived == true }.distinctUntilChanged().collect { arrived ->
                val s = session.value ?: return@collect
                if (arrived) {
                    publishPos(force = true)
                    if (s.toDest) { delay(3000); stop("arrived") }
                }
            }
        }
    }

    /**
     * Zacznij udostępniać: [minutes] – na ile (null = do celu; bez trasy – 2 h). [key] – z telefonu,
     * żeby link znał telefon; bez klucza – nowy (z radia).
     */
    fun start(key: ByteArray? = null, minutes: Int? = null, by: String = "car"): Session {
        val now = System.currentTimeMillis()
        session.value?.let { if (key == null || !it.key.contentEquals(key)) end(it, "stopped") }
        val toDest = minutes == null && RouteRepository.route.value != null
        val until = now + when {
            minutes != null && minutes > 0 -> minutes * 60_000L
            toDest -> 12 * 3600_000L
            else -> 2 * 3600_000L
        }
        val s = Session(key ?: LiveShare.newKey(), now, until, toDest, by)
        session.value = s
        Prefs.store?.edit()
            ?.putString("share_k", LiveShare.keyText(s.key))?.putLong("share_since", now)?.putLong("share_until", until)
            ?.putBoolean("share_dest", toDest)?.putString("share_by", by)?.apply()
        lastPos = 0L
        lastMoving = null
        lastRouteId = 0L
        lastEtaMin = -1L
        scope.launch {
            try { publishRoute(force = true) } catch (_: Exception) {}
            delay(1500)
            try { publishPos(force = true) } catch (_: Exception) {}
        }
        ConnectRepository.publishStatusSoon()
        return s
    }

    fun stop(why: String = "stopped") {
        val s = session.value ?: return
        end(s, why)
        session.value = null
        clear()
        ConnectRepository.publishStatusSoon()
    }

    private fun clear() {
        Prefs.store?.edit()?.remove("share_k")?.remove("share_until")?.remove("share_since")?.remove("share_dest")?.remove("share_by")?.apply()
    }

    private fun end(s: Session, why: String) {
        post(s, JSONObject().put("type", LiveShare.END).put("t", System.currentTimeMillis()).put("why", why))
    }

    /** Do statusu: telefon pokaże „udostępniasz jazdę” i link. */
    fun statusJson(): JSONObject? {
        val s = session.value ?: return null
        return JSONObject().put("link", s.link).put("until", s.until).put("dest", s.toDest).put("by", s.by).put("since", s.since)
    }

    private fun onLocation(l: android.location.Location) {
        val s = session.value ?: return
        val now = System.currentTimeMillis()
        if (now > s.until) { stop("expired"); return }
        val moving = l.hasSpeed() && l.speed > 2f
        val etaMin = RouteRepository.progress.value?.let { (it.remainingS / 60).toLong() } ?: -1L
        val gap = now - lastPos
        val due = when {
            lastMoving != moving && gap > 15_000 -> true            // ruszył / zatrzymał się
            moving -> gap >= 120_000                                // w ruchu: co 2 min
            else -> gap >= 10 * 60_000                              // postój: co 10 min
        } || (lastEtaMin >= 0 && etaMin >= 0 && kotlin.math.abs(etaMin - lastEtaMin) >= 8 && gap > 30_000) // duża zmiana przyjazdu
        if (due) publishPos(force = false)
    }

    private fun publishPos(force: Boolean) {
        val s = session.value ?: return
        val l = LocationRepository.location.value ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastPos < 15_000) return
        val moving = l.hasSpeed() && l.speed > 2f
        lastPos = now
        lastMoving = moving
        val o = JSONObject().put("type", LiveShare.POS)
            .put("lat", Math.round(l.latitude * 1e6) / 1e6).put("lon", Math.round(l.longitude * 1e6) / 1e6)
            .put("spd", if (l.hasSpeed()) (l.speed * 3.6).toInt() else 0).put("brg", l.bearing.toInt())
            .put("t", now).put("m", moving).put("until", s.until).put("toDest", s.toDest)
            .put("who", Prefs.profile.value.name.ifBlank { "Auto" })
        RouteRepository.route.value?.let { r ->
            o.put("dest", JSONObject().put("n", r.dest.name).put("lat", r.dest.lat).put("lon", r.dest.lon))
            o.put("rid", r.id.toString())
            RouteRepository.progress.value?.let { p ->
                o.put("eta", now + (p.remainingS * 1000).toLong()).put("rem", p.remainingM.toLong())
                lastEtaMin = (p.remainingS / 60).toLong()
                if (p.arrived) o.put("arr", true)
            }
        }
        post(s, o)
    }

    private fun publishRoute(force: Boolean) {
        val s = session.value ?: return
        val r = RouteRepository.route.value ?: return
        val now = System.currentTimeMillis()
        if (r.id == lastRouteId) return
        // przeliczanie w czasie jazdy: najwyżej co 2 min
        if (!force && now - lastRouteAt < 120_000) {
            scope.launch { delay(120_000 - (now - lastRouteAt)); if (RouteRepository.route.value?.id == r.id) publishRoute(force = true) }
            return
        }
        lastRouteId = r.id
        lastRouteAt = now
        val n = r.points.size
        val lats = DoubleArray(n) { r.points[it].latitude }
        val lons = DoubleArray(n) { r.points[it].longitude }
        val idx = LiveShare.simplify(lats, lons, 220)
        val stops = JSONArray()
        r.stops.forEach { p -> stops.put(JSONObject().put("n", p.name).put("lat", p.lat).put("lon", p.lon)) }
        post(
            s,
            JSONObject().put("type", LiveShare.ROUTE).put("id", r.id.toString()).put("t", now)
                .put("pl", LiveShare.encodePolyline(lats, lons, idx))
                .put("dest", JSONObject().put("n", r.dest.name).put("d", r.dest.detail).put("lat", r.dest.lat).put("lon", r.dest.lon))
                .put("stops", stops).put("km", (r.distanceM / 1000).toInt()),
        )
    }

    /** Koniec trasy: pusta trasa (strona chowa linię i cel) + pozycja już bez celu. */
    private fun clearRoute(s: Session) {
        lastRouteId = 0L
        post(s, JSONObject().put("type", LiveShare.ROUTE).put("id", "0").put("t", System.currentTimeMillis()).put("pl", "").put("clear", true))
        scope.launch { delay(1500); try { publishPos(force = true) } catch (_: Exception) {} }
    }

    private fun post(s: Session, o: JSONObject) {
        ConnectRepository.relayPost(LiveShare.topic(s.key), RelayCrypto.encrypt(s.key, o.toString()))
    }
}
