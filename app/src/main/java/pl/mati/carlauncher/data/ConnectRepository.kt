package pl.mati.carlauncher.data

import android.bluetooth.BluetoothAdapter
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import pl.mati.caros.core.Proto
import pl.mati.caros.core.RelayCrypto
import pl.mati.carlauncher.BuildConfig
import pl.mati.carlauncher.ui.Screen
import pl.mati.carlauncher.ui.Toaster
import pl.mati.carlauncher.ui.UiState
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.roundToInt

/**
 * CAR OS Connect – aplikacja na telefon.
 *
 * Kod QR: link do pobrania aplikacji + (po #) klucz parowania, nazwa Bluetooth radia i dane auta.
 * Wymiana danych przez przekaźnik ntfy.sh, szyfrowana AES-GCM (przekaźnik widzi tylko szyfrogram):
 *  - kanał „auto → telefon”: miejsce parkowania, stan auta, trasy, kopia ustawień,
 *  - kanał „telefon → auto”: trasa do nawigacji, ulubione, przywracanie kopii, prośba o odświeżenie.
 */
object ConnectRepository {

    const val APP_PAGE = "https://github.com/matixpompayt/Caros-connect/releases/latest"
    private const val RELAY = "https://ntfy.sh/"
    private val KEEP_KEYS = setOf("connect_key", "connect_sent", "connect_since", "connect_paired_at", "connect_backup_at")

    /** Kopia ustawień przysłana z telefonu – czeka na potwierdzenie na ekranie radia. */
    data class PendingRestore(val text: String, val created: Long, val car: String)

    val lastSent = MutableStateFlow(0L)
    val paired = MutableStateFlow(false)
    val pendingRestore = MutableStateFlow<PendingRestore?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Wysyłka po kolei (kolejność wiadomości + limity przekaźnika). */
    private val sender = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private var lastParkSig: String? = null
    private var listenJob: Job? = null
    private var statusJob: Job? = null
    private val restoreParts = HashMap<String, Array<String?>>()

    private var appCtx: android.content.Context? = null

    fun init(ctx: android.content.Context? = null) {
        if (ctx != null) appCtx = ctx.applicationContext
        val sp = Prefs.store ?: return
        paired.value = sp.getString("connect_key", null) != null
        lastSent.value = sp.getLong("connect_sent", 0L)
        if (sp.contains("phone_pv")) phone.value = Proto.Peer(sp.getInt("phone_pv", 1), sp.getString("phone_app", null)?.ifBlank { null }, emptySet())
        startListening()
        if (paired.value) {
            scope.launch {
                delay(20_000) // po starcie radia: internet, GPS
                ensureCommute()
                publishStatus()
                publishBackup(force = false)
            }
        }
        // auto na żywo w telefonie: pozycja co minutę w czasie jazdy
        scope.launch {
            LocationRepository.location.collect { l -> if (l != null) maybeLive(l) }
        }
    }

    // ---------- auto na żywo ----------
    private var lastLive = 0L
    private var liveMoving = false

    private fun maybeLive(l: android.location.Location) {
        if (!Prefs.shareLive.value) return
        val k = key(false) ?: return
        val now = System.currentTimeMillis()
        val moving = l.hasSpeed() && l.speed > 2f
        // pierwsza pozycja jazdy – od razu (telefon może czuwać: alarm „auto jedzie bez Ciebie”)
        val starting = moving && !liveMoving
        if (moving) liveMoving = true
        // Firebase: co wybrany czas (postój w trakcie jazdy – co 30 s, żeby telefon wiedział, że auto stoi w korku)
        if (liveMoving) firebaseLive(k, l, moving)
        if (!moving) return
        // przekaźnik (limit ok. 250 wiadomości dziennie): co minutę, a gdy działa Firebase – co 5 min
        // (z przekaźnika telefon w tle wie, że „auto ruszyło”)
        // przy udostępnianiu jazdy linkiem (osobne wiadomości) – co 3 min, żeby zmieścić się w limicie
        val gap = if (now - fbOkAt < 60_000) 5 * 60_000L else if (TripShare.session.value != null) 180_000L else 60_000L
        if (!starting && now - lastLive < gap) return
        lastLive = now
        send(
            JSONObject().put("type", "live").put("lat", l.latitude).put("lon", l.longitude)
                .put("spd", (l.speed * 3.6).toInt()).put("brg", l.bearing.toInt()).put("t", now).put("m", true),
        )
    }

    // ---------- pozycja na żywo przez Firebase (Realtime Database użytkownika) ----------
    private var fbLast = 0L
    @Volatile private var fbBusy = false
    /** Ostatni udany zapis do Firebase (ms) i ostatni błąd – do ustawień. */
    @Volatile var fbOkAt = 0L
        private set
    val fbError = MutableStateFlow<String?>(null)

    private fun firebaseLive(k: ByteArray, l: android.location.Location?, moving: Boolean, force: Boolean = false) {
        val base = Prefs.fbUrl.value
        if (base.isBlank()) return
        val now = System.currentTimeMillis()
        val iv = Prefs.liveInterval.value
        val gap = if (!moving && !force) 30_000L else if (iv <= 0) 1000L else iv * 1000L
        if (!force && now - fbLast < gap - 250) return
        if (fbBusy && !force) return
        fbLast = now
        fbBusy = true
        val plain = JSONObject().put("type", "live").put("lat", l?.latitude ?: 0.0).put("lon", l?.longitude ?: 0.0)
            .put("spd", if (l != null && l.hasSpeed()) (l.speed * 3.6).toInt() else 0).put("brg", l?.bearing?.toInt() ?: 0)
            .put("t", now).put("m", moving || !force)
        val body = JSONObject().put("d", encrypt(k, plain.toString())).put("ts", JSONObject().put(".sv", "timestamp")).toString()
        scope.launch(Dispatchers.IO) {
            try {
                val c = URL(base + "/caros/" + topicOf(k, "live") + ".json").openConnection() as HttpURLConnection
                c.connectTimeout = 8_000
                c.readTimeout = 10_000
                c.requestMethod = "PUT"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                try {
                    c.outputStream.use { it.write(body.toByteArray()) }
                    val code = c.responseCode
                    if (code in 200..299) { fbOkAt = System.currentTimeMillis(); fbError.value = null }
                    else fbError.value = if (code == 401 || code == 403) "Baza odrzuca zapis – sprawdź reguły w Firebase" else "Firebase: błąd $code"
                    try { (if (code in 200..299) c.inputStream else c.errorStream)?.close() } catch (_: Exception) {}
                } finally {
                    c.disconnect()
                }
            } catch (_: Exception) {
                fbError.value = "Brak połączenia z Firebase"
            } finally {
                fbBusy = false
            }
        }
    }

    /** Koniec jazdy: telefon przestaje pokazywać „auto w drodze”. */
    fun publishLiveStop() {
        if (!liveMoving || key(false) == null) return
        liveMoving = false
        val l = LocationRepository.location.value
        // Firebase od razu: auto stoi (telefon pokaże miejsce parkowania)
        key(false)?.let { firebaseLive(it, l, moving = false, force = true) }
        send(
            JSONObject().put("type", "live").put("lat", l?.latitude ?: 0.0).put("lon", l?.longitude ?: 0.0)
                .put("spd", 0).put("t", System.currentTimeMillis()).put("m", false),
        )
    }

    // ---------- czas dojazdu do pracy (dla przypomnień w telefonie) ----------
    private fun ensureCommute() {
        val sp = Prefs.store ?: return
        val p = Prefs.profile.value
        if (p.home.isBlank() || p.work.isBlank()) return
        val sig = p.home + "|" + p.work
        if (sp.getString("commute_key", null) == sig && System.currentTimeMillis() - sp.getLong("commute_at", 0) < 7 * 86_400_000L) return
        scope.launch {
            val h = if (p.homeLat != null && p.homeLon != null) RouteRepository.Place("Dom", p.home, p.homeLat, p.homeLon) else RouteRepository.geocode(p.home)
            val w = if (p.workLat != null && p.workLon != null) RouteRepository.Place("Praca", p.work, p.workLat, p.workLon) else RouteRepository.geocode(p.work)
            if (h == null || w == null) return@launch
            val d = RouteRepository.routeDuration(h.lat, h.lon, w.lat, w.lon) ?: return@launch
            sp.edit().putString("commute_key", sig).putLong("commute_at", System.currentTimeMillis()).putInt("commute_s", d.toInt()).apply()
            publishStatusSoon()
        }
    }

    /** Zmiany na najbliższe 14 dni (początek, koniec, nazwa) – telefon przypomina przed pracą. */
    private fun shiftsJson(): JSONArray {
        val out = JSONArray()
        val today = java.time.LocalDate.now()
        val zone = java.time.ZoneId.systemDefault()
        for (i in -1..13) {
            val d = today.plusDays(i.toLong())
            val s = WorkSchedule.shiftStartingOn(d) ?: continue
            if (s.off) continue
            val start = d.atStartOfDay(zone).toInstant().toEpochMilli() + s.start * 60_000L
            var dur = (s.end - s.start) * 60_000L
            if (dur <= 0) dur += 24 * 3_600_000L
            out.put(JSONObject().put("s", start).put("e", start + dur).put("n", s.name).put("h", s.hours))
        }
        return out
    }

    // ---------- klucz i kod QR ----------

    private fun key(create: Boolean): ByteArray? {
        val sp = Prefs.store ?: return null
        sp.getString("connect_key", null)?.let { return Base64.decode(it, Base64.NO_WRAP) }
        if (!create) return null
        val k = ByteArray(32).also { SecureRandom().nextBytes(it) }
        sp.edit()
            .putString("connect_key", Base64.encodeToString(k, Base64.NO_WRAP))
            .putLong("connect_paired_at", System.currentTimeMillis() / 1000)
            .remove("connect_since")
            .remove("connect_backup_at")
            .apply()
        paired.value = true
        startListening(restart = true)
        return k
    }

    fun resetKey() {
        Prefs.store?.edit()?.remove("connect_key")?.remove("connect_since")?.remove("connect_backup_at")?.apply()
        paired.value = false
        lastParkSig = null
        listenJob?.cancel()
        listenJob = null
    }

    private fun btName(): String = try {
        @Suppress("DEPRECATION")
        BluetoothAdapter.getDefaultAdapter()?.name.orEmpty()
    } catch (_: Exception) {
        ""
    }

    /** Treść kodu QR (tworzy klucz przy pierwszym użyciu i od razu wysyła dane do telefonu). */
    fun payload(): String {
        val k = key(create = true) ?: return APP_PAGE
        val p = Prefs.profile.value
        val json = JSONObject()
            .put("k", Base64.encodeToString(k, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
            .put("bt", btName())
            .put("car", p.carName)
            .put("drv", p.name)
            .toString()
        ParkingRepository.spot.value?.let { publishPark(it, force = true) }
        publishStatusSoon()
        scope.launch { delay(4000); publishBackup(force = true) }
        return APP_PAGE + "#caros1." + Base64.encodeToString(json.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    // ---------- auto → telefon ----------

    fun publishPark(s: ParkingRepository.Spot, force: Boolean = false) {
        if (key(false) == null) return
        val fin = ParkingRepository.isFinal
        val sig = "${s.lat},${s.lon},${s.time},${s.address},$fin"
        if (!force && sig == lastParkSig) return
        lastParkSig = sig
        val o = JSONObject().put("type", "park").put("lat", s.lat).put("lon", s.lon).put("t", s.time).put("a", s.address ?: "").put("fin", fin)
            .put("body", Prefs.carBody.value).put("paint", Prefs.carPaint.value)
        // ostatnia mila: cel trasy, gdy auto stoi 150 m – 3 km od niego
        if (fin) LastMile.destFor(s.lat, s.lon)?.let { d ->
            o.put("dest", d)
            if (!force) pl.mati.carlauncher.ui.Toaster.show("Do celu ${d.optInt("m")} m pieszo – CAR OS Connect poprowadzi chodnikami")
        }
        send(o)
    }

    /** Stan auta po krótkiej zwłoce (łączy kilka zmian w jedną wiadomość). */
    fun publishStatusSoon() {
        if (key(false) == null) return
        statusJob?.cancel()
        statusJob = scope.launch {
            delay(5000)
            publishStatus()
        }
    }

    fun publishStatus() {
        if (key(false) == null) return
        try {
            ServiceRepository.load()
            PlacesRepository.load()
            val p = Prefs.profile.value
            val svc = JSONArray()
            ServiceRepository.dueList().forEach { d ->
                svc.put(
                    JSONObject().put("n", d.item.name).put("o", d.overdue).put("s", d.soon).put("k", d.known)
                        .put("km", d.kmLeft ?: JSONObject.NULL).put("d", d.daysLeft ?: JSONObject.NULL)
                        .put("x", ServiceRepository.describe(d)).put("f", d.fraction.toDouble()),
                )
            }
            val items = JSONArray()
            ServiceRepository.items.value.forEach { items.put(it.toJson()) }
            val trips = JSONArray()
            TripRepository.trips.value.take(12).forEach { trips.put(it.toJson()) }
            val favs = JSONArray()
            PlacesRepository.favorites.value.take(25).forEach {
                favs.put(JSONObject().put("n", it.name).put("d", it.detail).put("lat", it.lat).put("lon", it.lon))
            }
            val o = JSONObject()
                .put("type", "status")
                .put("t", System.currentTimeMillis())
                .put("car", p.carName).put("drv", p.name).put("fuel", p.fuel).put("year", p.year)
                .put("odo", if (ServiceRepository.odometerSet.value) ServiceRepository.odometerKm.value.roundToInt() else JSONObject.NULL)
                .put("today", TripRepository.summary(TripRepository.startOfDay()).distanceM)
                .put("week", TripRepository.summary(TripRepository.startOfWeek()).distanceM)
                .put("month", TripRepository.summary(TripRepository.startOfMonth()).distanceM)
                .put("odoSet", ServiceRepository.odometerSet.value)
                .put("body", Prefs.carBody.value).put("paint", Prefs.carPaint.value)
                .put("svc", svc).put("items", items).put("trips", trips).put("favs", favs)
                .put("fuel", FuelRepository.toJson())
                .put("bat", CarCare.batteryJson())
                .put("shifts", try { shiftsJson() } catch (_: Exception) { JSONArray() })
                .put("commute", Prefs.store?.getInt("commute_s", 0)?.takeIf { it > 0 } ?: JSONObject.NULL)
                .put("home", placeJson(p.home, p.homeLat, p.homeLon))
                .put("work", placeJson(p.work, p.workLat, p.workLon))
                .put("ver", BuildConfig.VERSION_NAME)
                .put("look", LookSync.current().toJson())
                .put("share", TripShare.statusJson() ?: JSONObject.NULL)
                // protokół 2: wersja i lista obsługiwanych komend – telefon sprawdza je przed wysłaniem
                .put(Proto.PV, Proto.VERSION).put(Proto.CAPS, Proto.capsJson())
            send(o)
        } catch (_: Exception) {
        }
    }

    private fun placeJson(a: String, lat: Double?, lon: Double?): JSONObject =
        JSONObject().put("a", a).put("lat", lat ?: JSONObject.NULL).put("lon", lon ?: JSONObject.NULL)

    fun publishTrip(t: TripRepository.Trip, path: String?) {
        if (key(false) == null) return
        send(t.toJson().put("type", "trip").put("p", path ?: ""))
    }

    /** Kopia ustawień w częściach (przekaźnik przyjmuje do 4 KB na wiadomość). */
    fun publishBackup(force: Boolean) {
        val sp = Prefs.store ?: return
        if (key(false) == null) return
        val now = System.currentTimeMillis()
        if (!force && now - sp.getLong("connect_backup_at", 0L) < 20 * 3_600_000L) return
        sp.edit().putLong("connect_backup_at", now).apply()
        try {
            val json = BackupManager.buildJson(exclude = KEEP_KEYS) ?: return
            val gz = gzip(json.toByteArray())
            val size = 1900
            val n = (gz.size + size - 1) / size
            val id = now.toString()
            for (i in 0 until n) {
                val part = gz.copyOfRange(i * size, minOf(gz.size, (i + 1) * size))
                send(
                    JSONObject().put("type", "bk").put("id", id).put("i", i).put("n", n).put("c", now)
                        .put("car", Prefs.profile.value.carName)
                        .put("d", Base64.encodeToString(part, Base64.NO_WRAP)),
                )
            }
        } catch (_: Exception) {
        }
    }

    /** Raport danych z radia (CAN) do telefonu, w częściach jak kopia. false = brak parowania. */
    fun publishReport(text: String): Boolean {
        if (key(false) == null) return false
        return try {
            val gz = gzip(text.toByteArray())
            val size = 1900
            val n = (gz.size + size - 1) / size
            val id = System.currentTimeMillis().toString()
            for (i in 0 until n) {
                val part = gz.copyOfRange(i * size, minOf(gz.size, (i + 1) * size))
                send(JSONObject().put("type", "rp").put("id", id).put("i", i).put("n", n).put("d", Base64.encodeToString(part, Base64.NO_WRAP)))
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Wiadomość na dowolny kanał przekaźnika (już zaszyfrowana) – np. jazda na żywo przez link. */
    fun relayPost(topic: String, body: String) {
        scope.launch(sender) {
            for (attempt in 0..2) {
                try {
                    val c = URL(RELAY + topic).openConnection() as HttpURLConnection
                    c.connectTimeout = 10_000
                    c.readTimeout = 15_000
                    c.requestMethod = "POST"
                    c.doOutput = true
                    c.setRequestProperty("User-Agent", "CarOS")
                    try {
                        c.outputStream.use { it.write(body.toByteArray()) }
                        val code = c.responseCode
                        if (code in 200..299) break
                        if (code == 429) delay(6000)
                    } finally {
                        c.disconnect()
                    }
                } catch (_: Exception) {
                    delay(4000)
                }
            }
            delay(600)
        }
    }

    private fun send(o: JSONObject) {
        val k = key(false) ?: return
        val body = encrypt(k, o.toString())
        scope.launch(sender) {
            for (attempt in 0..2) {
                try {
                    val c = URL(RELAY + topicOf(k, "")).openConnection() as HttpURLConnection
                    c.connectTimeout = 10_000
                    c.readTimeout = 15_000
                    c.requestMethod = "POST"
                    c.doOutput = true
                    c.setRequestProperty("User-Agent", "CarOS")
                    try {
                        c.outputStream.use { it.write(body.toByteArray()) }
                        val code = c.responseCode
                        if (code in 200..299) {
                            val now = System.currentTimeMillis()
                            lastSent.value = now
                            Prefs.store?.edit()?.putLong("connect_sent", now)?.apply()
                            break
                        }
                        if (code == 429) delay(6000)
                    } finally {
                        c.disconnect()
                    }
                } catch (_: Exception) {
                    delay(4000)
                }
            }
            delay(600)
        }
    }

    // ---------- telefon → auto ----------

    private fun startListening(restart: Boolean = false) {
        if (listenJob?.isActive == true && !restart) return
        listenJob?.cancel()
        listenJob = scope.launch {
            while (isActive) {
                val k = key(false)
                if (k == null) { delay(5000); continue }
                try { stream(k) } catch (_: Exception) {}
                delay(10_000)
            }
        }
    }

    /** Stały odczyt kanału telefon → auto (przekaźnik wysyła sygnał co ~45 s). */
    private fun stream(k: ByteArray) {
        val sp = Prefs.store ?: return
        val since = sp.getString("connect_since", null) ?: sp.getLong("connect_paired_at", System.currentTimeMillis() / 1000).toString()
        val c = URL(RELAY + topicOf(k, "p2c") + "/json?since=" + since).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 120_000
        c.setRequestProperty("User-Agent", "CarOS")
        try {
            if (c.responseCode !in 200..299) return
            c.inputStream.bufferedReader().use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    val o = try { JSONObject(line) } catch (_: Exception) { continue }
                    if (o.optString("event") != "message") continue
                    val id = o.optString("id")
                    val plain = decrypt(k, o.optString("message"))
                    if (plain != null) {
                        try { handle(JSONObject(plain)) } catch (_: Exception) {}
                    }
                    if (id.isNotBlank()) sp.edit().putString("connect_since", id).apply()
                }
            }
        } finally {
            c.disconnect()
        }
    }

    private fun place(o: JSONObject) = RouteRepository.Place(
        o.optString("n").ifBlank { "Cel z telefonu" }, o.optString("d"), o.getDouble("lat"), o.getDouble("lon"),
    )

    /** Telefon (CAR OS Connect): wersja protokołu i aplikacji z ostatniej komendy. */
    val phone = MutableStateFlow<Proto.Peer?>(null)

    /** Opis wykonania bieżącej komendy (do potwierdzenia „ack”). */
    private var result: String? = null

    /** Komunikat na ekranie radia + opis do potwierdzenia dla telefonu. */
    private fun say(t: String) {
        Toaster.show(t)
        result = t
    }

    /** Ostatnie wykonane komendy (mid) – powtórka tej samej z telefonu nie robi nic drugi raz. */
    private fun seen(mid: String): Boolean {
        val sp = Prefs.store ?: return false
        val list = (sp.getString("connect_mids", "") ?: "").split(',').filter { it.isNotBlank() }
        if (mid in list) return true
        sp.edit().putString("connect_mids", (list.takeLast(59) + mid).joinToString(",")).apply()
        return false
    }

    /** Duże komendy z telefonu przychodzą w częściach (np. trasa z kilkudziesięcioma przystankami). */
    private val joiner = pl.mati.caros.core.Parts.Joiner()

    private fun handle(o: JSONObject) {
        val type = o.optString("type")
        if (type == pl.mati.caros.core.Parts.TYPE) {
            joiner.add(o)?.let { handle(JSONObject(it)) }
            return
        }
        val ph = Proto.phoneFrom(o)
        if (phone.value != ph) {
            phone.value = ph
            Prefs.store?.edit()?.putInt("phone_pv", ph.pv)?.putString("phone_app", ph.app ?: "")?.apply()
        }
        // ostrzeżenie z telefonu, które czekało w przekaźniku (radio było wyłączone) – już nieaktualne
        if (Proto.isStaleLive(o)) return
        val mid = o.optString(Proto.MID)
        val known = type in Proto.CAR_CAPS
        if (mid.isNotBlank() && known && seen(mid)) {
            // telefon wysłał jeszcze raz (nie dostał potwierdzenia) – tylko potwierdź
            send(Proto.ack(type, mid, true, "już wykonane", BuildConfig.VERSION_NAME))
            return
        }
        result = null
        var err: String? = null
        try {
            command(type, o)
        } catch (e: Exception) {
            err = e.message?.take(120) ?: e.javaClass.simpleName
        }
        if (mid.isNotBlank() && known) send(Proto.ack(type, mid, err == null, err ?: result, BuildConfig.VERSION_NAME))
    }

    private fun command(type: String, o: JSONObject) {
        val mid = o.optString(Proto.MID)
        when (type) {
            "route" -> {
                val p = place(o)
                // punkty po drodze ustawione w telefonie (kolejność jak w telefonie)
                val via = o.optJSONArray("via")?.let { a -> (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { v -> try { place(v) } catch (_: Exception) { null } } } }.orEmpty()
                // kształt trasy wybranej w telefonie (żeby radio wybrało ten sam wariant)
                val shape = o.optJSONArray("shape")?.let { a ->
                    (0 until a.length()).mapNotNull { i -> a.optJSONArray(i)?.let { q -> org.osmdroid.util.GeoPoint(q.getDouble(0), q.getDouble(1)) } }
                }.orEmpty()
                RouteRepository.planTo(p, via, shape)
                UiState.go(Screen.NAV)
                say("Trasa z telefonu: ${p.name}" + if (via.isNotEmpty()) " przez ${via.size} ${punktow(via.size)}" else "")
            }
            "fav_add" -> {
                val p = place(o)
                PlacesRepository.load()
                if (!PlacesRepository.isFavorite(p)) PlacesRepository.toggleFavorite(p)
                say("Dodano do ulubionych z telefonu: ${p.name}")
                publishStatusSoon()
            }
            // ustawienia z telefonu (np. klucz TomTom do korków)
            "cfg" -> {
                if (o.has("live_iv")) Prefs.setLiveInterval(o.optInt("live_iv", 10).coerceIn(0, 60))
                if (o.has("fb")) {
                    val u = o.optString("fb", "").trim()
                    if (u.isEmpty() || u.startsWith("https://")) {
                        if (u != Prefs.fbUrl.value) {
                            Prefs.setFbUrl(u)
                            pl.mati.carlauncher.ui.Toaster.show(if (u.isEmpty()) "Firebase wyłączony – pozycja do telefonu co minutę" else "Firebase z telefonu zapisany – pozycja na żywo w czasie jazdy")
                        }
                    }
                }
                val k = o.optString("tomtom", "").trim()
                if (o.has("tomtom")) {
                    Prefs.setTomtomKey(k)
                    if (k.isBlank()) pl.mati.carlauncher.ui.Toaster.show("Klucz TomTom usunięty z telefonu – korki wyłączone")
                    else TrafficRepository.testKey { pl.mati.carlauncher.ui.Toaster.show("Klucz TomTom z telefonu: $it") }
                }
                // klucz Tankerkönig – ceny na stacjach w Niemczech także w planie trasy w radiu
                if (o.has("tk")) {
                    val tk = o.optString("tk", "").trim()
                    appCtx?.let { c -> if (tk != pl.mati.caros.core.StationPrices.tkKey.value) pl.mati.caros.core.StationPrices.setKey(c, tk) }
                }
                result = "Ustawienia zapisane w radiu"
            }
            // ostrzeżenie CamSam / Yanosika z telefonu
            "alert" -> AlertRepository.fromPhone(o)
            "alert_end" -> AlertRepository.phoneEnd(o)
            "camsam" -> AlertRepository.camsamState(o)
            "camsam_hud" -> AlertRepository.fromHud(o)
            // dom / praca ustawione w telefonie
            "place_set" -> {
                val work = o.optString("slot") == "work"
                val a = o.optString("a")
                val lat = if (o.has("lat") && !o.isNull("lat")) o.getDouble("lat") else null
                val lon = if (o.has("lon") && !o.isNull("lon")) o.getDouble("lon") else null
                Prefs.setPlace(work, a, lat, lon)
                say(
                    (if (work) "Adres pracy" else "Adres domu") +
                        (if (a.isBlank()) " usunięty z telefonu" else " z telefonu: $a"),
                )
                publishStatusSoon()
            }
            // tankowanie dodane / usunięte w telefonie
            "fuel_add" -> {
                FuelRepository.add(FuelRepository.Fill.from(o.getJSONObject("fill")))
                say("Tankowanie z telefonu zapisane")
                publishStatusSoon()
            }
            "fuel_del" -> {
                FuelRepository.remove(o.optString("id"))
                publishStatusSoon()
            }
            // pojemność baku zmieniona w telefonie
            "fuel_tank" -> {
                val l = o.optInt("l", 0)
                if (l in 20..150) {
                    FuelRepository.setTank(l)
                    try { FuelRepository.recompute() } catch (_: Exception) {}
                    say("Pojemność baku z telefonu: $l l")
                    publishStatusSoon()
                }
            }
            // korekta zasięgu z telefonu: zasięg z licznika auta albo mnożnik
            "fuel_rk" -> {
                val k = if (o.has("km")) FuelRepository.calibrate(o.optInt("km")) else o.optDouble("k", Double.NaN).takeIf { !it.isNaN() }?.also { FuelRepository.setRangeK(it) }
                if (k != null) {
                    val pct = Math.round((k - 1) * 100)
                    say(if (pct == 0L) "Zasięg bez korekty" else "Korekta zasięgu: ${if (pct > 0) "+" else ""}$pct %")
                    publishStatusSoon()
                }
            }
            "fav_del" -> {
                PlacesRepository.load()
                PlacesRepository.removeFavorite(place(o))
                publishStatusSoon()
            }
            // serwis edytowany w telefonie
            "svc_item" -> {
                ServiceRepository.load()
                val it = ServiceRepository.Item.from(o.getJSONObject("item"))
                val cur = ServiceRepository.items.value.firstOrNull { x -> x.id == it.id } ?: return
                ServiceRepository.update(cur.copy(intervalKm = it.intervalKm, intervalMonths = it.intervalMonths, lastKm = it.lastKm, lastDate = it.lastDate))
                ServiceRepository.checkReminders()
                say("Serwis zmieniony z telefonu: ${cur.name}")
            }
            "svc_done" -> {
                ServiceRepository.load()
                val id = o.getString("id")
                val cur = ServiceRepository.items.value.firstOrNull { x -> x.id == id } ?: return
                ServiceRepository.markDone(id)
                ServiceRepository.checkReminders()
                say("Z telefonu: ${cur.name} – wykonano")
            }
            "svc_odo" -> {
                ServiceRepository.load()
                val km = o.getInt("km")
                if (km > 0) {
                    ServiceRepository.setOdometer(km)
                    ServiceRepository.checkReminders()
                    say("Przebieg z telefonu: $km km")
                }
            }
            "sync" -> {
                publishStatus()
                ParkingRepository.spot.value?.let { publishPark(it, force = true) }
                if (o.optBoolean("backup")) publishBackup(force = true)
            }
            "rs" -> {
                val id = o.getString("id")
                val n = o.getInt("n")
                val parts = restoreParts.getOrPut(id) { arrayOfNulls(n) }
                if (parts.size != n) return
                parts[o.getInt("i")] = o.getString("d")
                if (parts.all { it != null }) {
                    restoreParts.remove(id)
                    val bytes = ByteArrayOutputStream()
                    parts.forEach { bytes.write(Base64.decode(it, Base64.NO_WRAP)) }
                    val text = String(gunzip(bytes.toByteArray()))
                    val root = JSONObject(text)
                    if (root.optString("app") != "CAR OS") return
                    val prefs = root.optJSONObject("prefs")
                    fun s(k: String) = prefs?.optJSONObject(k)?.optString("v").orEmpty()
                    val car = listOf(s("p_brand"), s("p_model"), s("p_engine")).filter { it.isNotBlank() }.joinToString(" ")
                    pendingRestore.value = PendingRestore(text, root.optLong("created"), car)
                }
            }
            // jazda na żywo przez link – klucz z telefonu (telefon zna link)
            Proto.Cmd.SHARE -> {
                val k = pl.mati.caros.core.LiveShare.keyFrom(o.optString("k")) ?: throw IllegalArgumentException("zły klucz udostępniania")
                val min = o.optInt("min", 0)
                val s = TripShare.start(k, if (min > 0) min else null, "phone")
                say("Udostępniasz jazdę na żywo " + if (s.toDest) "do celu" else "do ${java.text.SimpleDateFormat("H:mm", java.util.Locale("pl")).format(java.util.Date(s.until))}")
            }
            Proto.Cmd.SHARE_END -> {
                val had = TripShare.session.value != null
                TripShare.stop("stopped")
                result = if (had) "Udostępnianie jazdy zakończone" else "Udostępnianie było już wyłączone"
                if (had) Toaster.show("Udostępnianie jazdy zakończone")
            }
            // wygląd z telefonu (Glass, jasny / ciemny, akcent)
            Proto.Cmd.LOOK -> {
                val l = pl.mati.caros.core.Look.fromJson(o.optJSONObject("look")) ?: return
                val d = LookSync.apply(l)
                if (d != null) say("Wygląd z telefonu: $d")
                else result = if (Prefs.lookSync.value) "Wygląd bez zmian" else "W radiu wyłączony wspólny wygląd"
                publishStatusSoon()
            }
            // komenda z nowszego telefonu, której ta wersja nie zna – telefon pokaże „zaktualizuj CAR OS”
            else -> if (type.isNotBlank() && type !in Proto.CAR_CAPS) send(Proto.nack(type, BuildConfig.VERSION_NAME, mid))
        }
    }

    /** Przywraca kopię z telefonu (zachowuje połączenie z telefonem). */
    fun applyRestore(): Boolean {
        val r = pendingRestore.value ?: return false
        pendingRestore.value = null
        return BackupManager.restoreText(r.text)
    }

    // ---------- szyfrowanie (wspólne z telefonem: core/RelayCrypto) ----------

    private fun topicOf(key: ByteArray, channel: String) = RelayCrypto.topicOf(key, channel)
    private fun encrypt(key: ByteArray, plain: String) = RelayCrypto.encrypt(key, plain)
    private fun decrypt(key: ByteArray, msg: String) = RelayCrypto.decrypt(key, msg)
    private fun gzip(b: ByteArray) = RelayCrypto.gzip(b)
    private fun gunzip(b: ByteArray) = RelayCrypto.gunzip(b)
}

/** 1 punkt, 2–4 punkty, 5 punktów, 22 punkty… */
internal fun punktow(n: Int): String = when {
    n == 1 -> "punkt"
    n % 10 in 2..4 && n % 100 !in 12..14 -> "punkty"
    else -> "punktów"
}
