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

    fun init() {
        val sp = Prefs.store ?: return
        paired.value = sp.getString("connect_key", null) != null
        lastSent.value = sp.getLong("connect_sent", 0L)
        startListening()
        if (paired.value) {
            scope.launch {
                delay(20_000) // po starcie radia: internet, GPS
                publishStatus()
                publishBackup(force = false)
            }
        }
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
        val sig = "${s.lat},${s.lon},${s.time},${s.address}"
        if (!force && sig == lastParkSig) return
        lastParkSig = sig
        send(JSONObject().put("type", "park").put("lat", s.lat).put("lon", s.lon).put("t", s.time).put("a", s.address ?: ""))
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
                .put("svc", svc).put("items", items).put("trips", trips).put("favs", favs)
                .put("ver", BuildConfig.VERSION_NAME)
            send(o)
        } catch (_: Exception) {
        }
    }

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

    private fun handle(o: JSONObject) {
        when (o.optString("type")) {
            "route" -> {
                val p = place(o)
                RouteRepository.planTo(p)
                UiState.go(Screen.NAV)
                Toaster.show("Trasa z telefonu: ${p.name}")
            }
            "fav_add" -> {
                val p = place(o)
                PlacesRepository.load()
                if (!PlacesRepository.isFavorite(p)) PlacesRepository.toggleFavorite(p)
                Toaster.show("Dodano do ulubionych z telefonu: ${p.name}")
                publishStatusSoon()
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
                Toaster.show("Serwis zmieniony z telefonu: ${cur.name}")
            }
            "svc_done" -> {
                ServiceRepository.load()
                val id = o.getString("id")
                val cur = ServiceRepository.items.value.firstOrNull { x -> x.id == id } ?: return
                ServiceRepository.markDone(id)
                ServiceRepository.checkReminders()
                Toaster.show("Z telefonu: ${cur.name} – wykonano")
            }
            "svc_odo" -> {
                ServiceRepository.load()
                val km = o.getInt("km")
                if (km > 0) {
                    ServiceRepository.setOdometer(km)
                    ServiceRepository.checkReminders()
                    Toaster.show("Przebieg z telefonu: $km km")
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
        }
    }

    /** Przywraca kopię z telefonu (zachowuje połączenie z telefonem). */
    fun applyRestore(): Boolean {
        val r = pendingRestore.value ?: return false
        pendingRestore.value = null
        return BackupManager.restoreText(r.text)
    }

    // ---------- szyfrowanie ----------

    private fun topicOf(key: ByteArray, channel: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(key)
        if (channel.isNotEmpty()) md.update(channel.toByteArray())
        return "caros_" + md.digest().joinToString("") { "%02x".format(it) }.take(28)
    }

    /** Format v2: "2:" + base64(iv | AES-GCM(gzip(json))). */
    private fun encrypt(key: ByteArray, plain: String): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return "2:" + Base64.encodeToString(iv + c.doFinal(gzip(plain.toByteArray())), Base64.NO_WRAP)
    }

    private fun decrypt(key: ByteArray, msg: String): String? = try {
        val v2 = msg.startsWith("2:")
        val all = Base64.decode(if (v2) msg.substring(2) else msg, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, all, 0, 12))
        val out = c.doFinal(all, 12, all.size - 12)
        String(if (v2) gunzip(out) else out)
    } catch (_: Exception) {
        null
    }

    private fun gzip(b: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(b) }
        return bos.toByteArray()
    }

    private fun gunzip(b: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(b)).use { it.readBytes() }
}
