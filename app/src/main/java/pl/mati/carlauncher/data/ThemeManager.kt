package pl.mati.carlauncher.data

import android.content.Context
import android.content.res.Configuration
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Tryb dzień / noc:
 *  - "lights": jak w fabrycznym launcherze – światła wyłączone = jasny, włączone = ciemny
 *    (sygnał świateł wykrywany kalibracją; dopóki nie wykryty – wg słońca),
 *  - "sun": wschód / zachód słońca z GPS (bez internetu),
 *  - "light" / "dark": na stałe.
 */
object ThemeManager {

    val light = MutableStateFlow(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false

    fun start(ctx: Context) {
        if (started) return
        started = true
        HeadlightDetector.start(ctx)
        update()
        scope.launch {
            combine(Prefs.themeMode, HeadlightDetector.on, HeadlightDetector.learned) { _, _, _ -> }.collect { update() }
        }
        scope.launch {
            while (true) {
                delay(30_000)
                update()
            }
        }
    }

    fun update() {
        light.value = when (Prefs.themeMode.value) {
            "light" -> true
            "dark" -> false
            "sun" -> Sun.isDay()
            else -> HeadlightDetector.on.value?.let { !it } ?: Sun.isDay()
        }
    }

    /** Opis bieżącego stanu do ustawień. */
    fun describe(): String = when (Prefs.themeMode.value) {
        "light" -> "Zawsze jasny"
        "dark" -> "Zawsze ciemny"
        "sun" -> if (Sun.isDay()) "Dzień – jasny (wschód ${Sun.hm(true)}, zachód ${Sun.hm(false)})" else "Noc – ciemny (wschód ${Sun.hm(true)})"
        else -> when (HeadlightDetector.on.value) {
            true -> "Światła włączone – ciemny"
            false -> "Światła wyłączone – jasny"
            null -> "Sygnał świateł nierozpoznany – wg słońca (${if (Sun.isDay()) "dzień" else "noc"})"
        }
    }
}

/** Położenie słońca (uproszczony algorytm NOAA) – dzień, gdy słońce jest nad horyzontem. */
object Sun {
    private const val DEF_LAT = 52.74
    private const val DEF_LON = 14.43

    private fun pos(): Pair<Double, Double> {
        val l = LocationRepository.location.value
        return if (l != null) l.latitude to l.longitude else DEF_LAT to DEF_LON
    }

    fun elevation(timeMs: Long = System.currentTimeMillis()): Double {
        val (lat, lon) = pos()
        val d = timeMs / 86_400_000.0 + 2440587.5 - 2451545.0
        val g = Math.toRadians((357.529 + 0.98560028 * d) % 360)
        val q = (280.459 + 0.98564736 * d) % 360
        val l = Math.toRadians(q + 1.915 * sin(g) + 0.020 * sin(2 * g))
        val e = Math.toRadians(23.439 - 0.00000036 * d)
        val ra = atan2(cos(e) * sin(l), cos(l))
        val dec = asin(sin(e) * sin(l))
        val gmst = (18.697374558 + 24.06570982441908 * d) % 24
        val ha = Math.toRadians(((gmst * 15 + lon) % 360)) - ra
        val la = Math.toRadians(lat)
        return Math.toDegrees(asin(sin(la) * sin(dec) + cos(la) * cos(dec) * cos(ha)))
    }

    /** Dzień: słońce wyżej niż 2° pod horyzontem (jeszcze jasno tuż po zachodzie). */
    fun isDay(): Boolean = elevation() > -2.0

    /** Godzina wschodu / zachodu dziś (przybliżona, co 5 min). */
    fun hm(sunrise: Boolean): String {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0); cal.set(java.util.Calendar.SECOND, 0)
        val start = cal.timeInMillis
        var prev = elevation(start) > -2.0
        for (m in 5..1440 step 5) {
            val t = start + m * 60_000L
            val day = elevation(t) > -2.0
            if (day != prev && day == sunrise) {
                val c = java.util.Calendar.getInstance().apply { timeInMillis = t }
                return String.format("%d:%02d", c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
            }
            prev = day
        }
        return "—"
    }
}

/**
 * Sygnał świateł auta. Radio zna go (przewód ILL), ale każdy model podaje go inaczej,
 * więc CAR OS go „uczy się”: kierowca wyłącza i włącza światła, a my porównujemy, co się zmieniło
 * (tryb nocny systemu, ustawienia systemowe, komunikaty fabrycznej aplikacji).
 */
object HeadlightDetector {

    data class Learned(val kind: String, val table: String, val key: String, val onValue: String, val offValue: String) {
        fun describe(): String = when (kind) {
            "uimode" -> "tryb nocny systemu"
            "setting" -> "ustawienie systemowe „$key” ($offValue → $onValue)"
            "bcast" -> "komunikat ${table.substringAfterLast('.')} ($key: $offValue → $onValue)"
            "bcast2" -> "komunikaty ${table.substringAfterLast('.')} / ${key.substringAfterLast('.')}"
            else -> kind
        }
    }

    /** true = światła włączone, null = nie wiadomo. */
    val on = MutableStateFlow<Boolean?>(null)
    val learned = MutableStateFlow<Learned?>(null)

    private var appCtx: Context? = null
    private var observer: ContentObserver? = null
    private val main = Handler(Looper.getMainLooper())

    // ---------- praca ----------

    fun start(ctx: Context) {
        appCtx = ctx.applicationContext
        learned.value = load()
        apply()
    }

    private fun load(): Learned? = try {
        Prefs.store?.getString("hl_learned", null)?.let {
            val o = JSONObject(it)
            Learned(o.getString("k"), o.getString("t"), o.getString("key"), o.getString("on"), o.getString("off"))
        }
    } catch (_: Exception) {
        null
    }

    private fun save(l: Learned?) {
        val ed = Prefs.store?.edit() ?: return
        if (l == null) ed.remove("hl_learned")
        else ed.putString("hl_learned", JSONObject().put("k", l.kind).put("t", l.table).put("key", l.key).put("on", l.onValue).put("off", l.offValue).toString())
        ed.apply()
    }

    fun forget() {
        learned.value = null
        save(null)
        apply()
    }

    private fun uriFor(table: String, key: String): Uri = when (table) {
        "secure" -> Settings.Secure.getUriFor(key)
        "global" -> Settings.Global.getUriFor(key)
        else -> Settings.System.getUriFor(key)
    }

    private fun readSetting(ctx: Context, table: String, key: String): String? = try {
        when (table) {
            "secure" -> Settings.Secure.getString(ctx.contentResolver, key)
            "global" -> Settings.Global.getString(ctx.contentResolver, key)
            else -> Settings.System.getString(ctx.contentResolver, key)
        }
    } catch (_: Exception) {
        null
    }

    private fun apply() {
        val ctx = appCtx ?: return
        observer?.let { try { ctx.contentResolver.unregisterContentObserver(it) } catch (_: Exception) {} }
        observer = null
        val l = learned.value
        when (l?.kind) {
            "setting" -> {
                val obs = object : ContentObserver(main) {
                    override fun onChange(selfChange: Boolean) { readNow() }
                }
                try { ctx.contentResolver.registerContentObserver(uriFor(l.table, l.key), false, obs); observer = obs } catch (_: Exception) {}
                readNow()
            }
            "uimode" -> onUiMode(ctx.resources.configuration)
            null -> on.value = null
            else -> Unit // komunikaty – stan przyjdzie z kolejną zmianą
        }
    }

    private fun readNow() {
        val ctx = appCtx ?: return
        val l = learned.value ?: return
        val v = readSetting(ctx, l.table, l.key) ?: return
        on.value = when (v) {
            l.onValue -> true
            l.offValue -> false
            else -> {
                val a = v.toDoubleOrNull(); val on1 = l.onValue.toDoubleOrNull(); val off1 = l.offValue.toDoubleOrNull()
                if (a != null && on1 != null && off1 != null) kotlin.math.abs(a - on1) < kotlin.math.abs(a - off1) else v != l.offValue
            }
        }
    }

    /** Zmiana konfiguracji (tryb nocny systemu) – z MainActivity. */
    fun onUiMode(cfg: Configuration) {
        val night = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val l = learned.value ?: return
        if (l.kind == "uimode") on.value = (night.toString() == l.onValue)
    }

    /** Komunikat fabrycznej aplikacji (BroadcastProbe). */
    fun onBroadcast(action: String, extras: Map<String, String>) {
        if (calibrating) synchronized(events) { events += Event(System.currentTimeMillis(), action, extras) }
        val l = learned.value ?: return
        when (l.kind) {
            "bcast" -> if (action == l.table) extras[l.key]?.let { v -> on.value = v == l.onValue || (v != l.offValue && on.value == true) }
            "bcast2" -> when (action) {
                l.table -> on.value = true
                l.key -> on.value = false
            }
        }
    }

    // ---------- kalibracja ----------

    private class Event(val t: Long, val action: String, val extras: Map<String, String>)

    @Volatile private var calibrating = false
    private val events = ArrayList<Event>()
    private val snaps = ArrayList<Pair<Long, Map<String, String>>>()

    /** Migawka: wszystkie ustawienia systemowe + tryb nocny systemu. */
    private fun snapshot(ctx: Context): Map<String, String> {
        val out = HashMap<String, String>()
        for ((table, uri) in listOf("system" to Settings.System.CONTENT_URI, "secure" to Settings.Secure.CONTENT_URI, "global" to Settings.Global.CONTENT_URI)) {
            try {
                ctx.contentResolver.query(uri, arrayOf("name", "value"), null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val k = c.getString(0) ?: continue
                        out["$table/$k"] = c.getString(1) ?: ""
                    }
                }
            } catch (_: Exception) {
            }
        }
        val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        out["uimode"] = night.toString()
        return out
    }

    /** Krok kalibracji: 0 = światła wyłączone, 1 = włączone, 2 = znów wyłączone (wtedy wynik). */
    fun calibrateStep(ctx: Context, step: Int): Learned? {
        if (step == 0) {
            synchronized(events) { events.clear() }
            snaps.clear()
            calibrating = true
        }
        snaps += System.currentTimeMillis() to snapshot(ctx)
        if (step < 2) return null
        calibrating = false
        val result = analyze()
        if (result != null) {
            learned.value = result
            save(result)
            apply()
            on.value = false // ostatni krok: światła wyłączone
        }
        return result
    }

    fun cancelCalibration() {
        calibrating = false
    }

    private val noisy = Regex("(time|count|last|usage|stamp|boot|location|wifi|network|battery|package|install|_id$|token|uuid|screen_off_timeout|volume)", RegexOption.IGNORE_CASE)
    private val hint = Regex("(ill|light|lamp|night|head|dimm|backl|daymode|day_mode|nightmode|car_)", RegexOption.IGNORE_CASE)

    private fun analyze(): Learned? {
        if (snaps.size < 3) return null
        val (t0, a) = snaps[0]
        val (t1, b) = snaps[1]
        val (t2, c) = snaps[2]
        // 1) tryb nocny systemu
        if (a["uimode"] == c["uimode"] && a["uimode"] != b["uimode"]) return Learned("uimode", "", "", b["uimode"] ?: "true", a["uimode"] ?: "false")
        // 2) ustawienia: zmiana przy włączeniu i powrót przy wyłączeniu
        val cands = b.keys.filter { k -> k != "uimode" && a[k] != null && a[k] == c[k] && a[k] != b[k] && !noisy.containsMatchIn(k.substringAfter('/')) }
        val best = cands.sortedWith(compareByDescending<String> { hint.containsMatchIn(it) }.thenBy { if (it.startsWith("system/")) 0 else 1 }).firstOrNull()
        if (best != null && hint.containsMatchIn(best)) return Learned("setting", best.substringBefore('/'), best.substringAfter('/'), b[best]!!, a[best]!!)
        // 3) komunikaty fabrycznej aplikacji
        val ev = synchronized(events) { events.toList() }
        val onEv = ev.filter { it.t in (t0 + 1)..t1 }
        val offEv = ev.filter { it.t in (t1 + 1)..t2 }
        for (e in onEv.asReversed()) {
            val o = offEv.lastOrNull { it.action == e.action } ?: continue
            val key = e.extras.keys.firstOrNull { k -> o.extras.containsKey(k) && o.extras[k] != e.extras[k] && !noisy.containsMatchIn(k) }
            if (key != null) return Learned("bcast", e.action, key, e.extras[key]!!, o.extras[key]!!)
        }
        val onOnly = onEv.map { it.action }.distinct().filter { a1 -> offEv.none { it.action == a1 } }
        val offOnly = offEv.map { it.action }.distinct().filter { a1 -> onEv.none { it.action == a1 } }
        if (onOnly.size == 1 && offOnly.size == 1) return Learned("bcast2", onOnly[0], offOnly[0], "", "")
        // 4) inne ustawienie, które zmienia się razem ze światłami (np. jasność ekranu przy ILL)
        if (best != null) return Learned("setting", best.substringBefore('/'), best.substringAfter('/'), b[best]!!, a[best]!!)
        return null
    }
}
