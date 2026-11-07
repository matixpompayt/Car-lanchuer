package pl.mati.carlauncher.data

import android.content.ContentValues
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Dane auta z radia (dekoder CAN-bus podłączony do radia), bez adaptera OBD2.
 *
 * Nie znamy z góry, jak dane wystawia konkretne radio, więc zbieramy WSZYSTKIE liczby,
 * które da się podsłuchać: komunikaty (broadcast) aplikacji radia/CAN, ustawienia systemowe,
 * właściwości systemu i serwis FYT (com.syu.ms). W skanerze widać, co się zmienia
 * (np. przy dodaniu gazu), i taką wartość przypisuje się jako obroty, temperaturę itd.
 */
object CanRepository {

    enum class Field(val label: String, val unit: String) {
        RPM("Obroty", "obr/min"),
        SPEED("Prędkość", "km/h"),
        COOLANT("Płyn chłodzący", "°C"),
        OUTSIDE("Temperatura zewnętrzna", "°C"),
        VOLTAGE("Napięcie", "V"),
        FUEL("Paliwo", "%"),
        FUEL_L("Paliwo w baku", "l"),
        RANGE("Zasięg", "km"),
        ODO("Przebieg", "km"),
        LOAD("Obciążenie", "%"),
        MAP("Ciśnienie w kolektorze (MAP)", "kPa"),
    }

    data class Mapping(val key: String, val scale: Double = 1.0, val offset: Double = 0.0) {
        fun apply(raw: Double) = raw * scale + offset
    }

    class Signal(val key: String) {
        @Volatile var value = 0.0
        @Volatile var text = ""
        @Volatile var min = Double.MAX_VALUE
        @Volatile var max = -Double.MAX_VALUE
        @Volatile var updates = 0
        @Volatile var lastUpdate = 0L
        @Volatile var lastChange = 0L
        val changes = java.util.ArrayDeque<Long>()

        fun recentChanges(now: Long): Int = synchronized(changes) {
            while (changes.isNotEmpty() && now - changes.first() > 60_000) changes.removeFirst()
            changes.size
        }
    }

    /** Niezmienna kopia sygnału (do sortowania i wyświetlania – wartości w tle zmieniają się w trakcie). */
    data class Row(
        val key: String, val value: Double, val text: String, val min: Double, val max: Double,
        val changes: Int, val lastChange: Long, val lastUpdate: Long,
    )

    data class Sources(
        val canPackages: List<String> = emptyList(),
        val actions: Int = 0,
        val fyt: String = "nie sprawdzono",
        val settingsKeys: Int = 0,
        val props: Int = 0,
        val ts: String = "nie sprawdzono",
    )

    val signals = ConcurrentHashMap<String, Signal>()
    val tick = MutableStateFlow(0L)
    val mappings = MutableStateFlow<Map<Field, Mapping>>(emptyMap())
    val values = MutableStateFlow<Map<Field, Double>>(emptyMap())
    val scannerOpen = MutableStateFlow(false)
    val sources = MutableStateFlow(Sources())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var appCtx: Context? = null
    private var started = false
    private var lastTick = 0L
    private var lastOdoWrite = 0L

    fun init(ctx: Context) {
        if (started) return
        started = true
        appCtx = ctx.applicationContext
        load()
        Vehicle.start()
        FytToolkit.start(ctx.applicationContext)
        TsCarBridge.start(ctx.applicationContext)
        scope.launch { pollLoop() }
    }

    // ---------- zbieranie ----------

    /** Wszystkie dodatki (extras) jednego komunikatu. */
    fun observeBundle(action: String, extras: Map<String, String>) {
        if (extras.isEmpty()) return
        // komunikaty z wieloma rodzajami ramek: rozdziel po polu „cmd/id/type…” (jeśli małą liczbą)
        val sel = extras.entries.firstOrNull { e ->
            e.key.lowercase().let { k -> k == "cmd" || k == "id" || k == "type" || k == "code" || k == "what" || k == "msg" || k.endsWith("cmd") || k.endsWith("_id") } &&
                (e.value.toIntOrNull()?.let { it in 0..0xFFFF } ?: false)
        }
        val prefix = "bc:$action" + (sel?.let { "@${it.key}=${it.value}" } ?: "")
        for ((k, v) in extras) observe("$prefix#$k", v)
    }

    /** Jedna wartość (tekst). Tablice „a|b|c” rozbijane na elementy (+ pary bajtów jako liczby 16-bit). */
    fun observe(key: String, raw: String) {
        val t = raw.trim()
        if (t.contains('|')) {
            val parts = t.split('|').take(32)
            val nums = parts.map { it.trim().toDoubleOrNull() }
            nums.forEachIndexed { i, d -> if (d != null) put("$key[$i]", d, parts[i]) }
            val bytes = nums.size in 2..24 && nums.all { it != null && it >= 0 && it <= 255 && it == Math.floor(it) }
            if (bytes) {
                for (i in 0 until nums.size - 1) {
                    val a = nums[i]!!.toInt()
                    val b = nums[i + 1]!!.toInt()
                    put("$key[$i,${i + 1}]", (a * 256 + b).toDouble(), "")
                    put("$key[${i + 1},$i]", (b * 256 + a).toDouble(), "")
                }
            }
            return
        }
        val d = when (t.lowercase()) {
            "true" -> 1.0
            "false" -> 0.0
            else -> t.toDoubleOrNull() ?: t.removePrefix("0x").takeIf { t.startsWith("0x") }?.toLongOrNull(16)?.toDouble()
        } ?: return
        put(key, d, t)
    }

    private fun put(key: String, d: Double, text: String) {
        if (d.isNaN() || d.isInfinite()) return
        val now = System.currentTimeMillis()
        val s = signals[key] ?: run {
            if (signals.size >= 4000) return
            Signal(key).also { signals[key] = it }
        }
        if (s.updates > 0 && s.value != d) {
            s.lastChange = now
            synchronized(s.changes) {
                s.changes.addLast(now)
                if (s.changes.size > 120) s.changes.removeFirst()
            }
        }
        s.value = d
        s.text = text
        if (d < s.min) s.min = d
        if (d > s.max) s.max = d
        s.updates++
        s.lastUpdate = now
        // przypisane pola
        val mapped = mappings.value.entries.filter { it.value.key == key }
        if (mapped.isNotEmpty()) {
            val nv = values.value.toMutableMap()
            mapped.forEach { (f, m) -> nv[f] = m.apply(d) }
            values.value = nv
            mapped.firstOrNull { it.key == Field.ODO }?.let { odo(it.value.apply(d)) }
        }
        if (now - lastTick > 300) {
            lastTick = now
            tick.value = now
        }
    }

    /** Usuń wartości pól przypisanych do sygnału (np. dane przestały przychodzić – nieaktualne). */
    fun expire(key: String) {
        val fields = mappings.value.entries.filter { it.value.key == key }.map { it.key }
        if (fields.isEmpty()) return
        val nv = values.value.toMutableMap()
        if (fields.none { nv.containsKey(it) }) return
        fields.forEach { nv.remove(it) }
        values.value = nv
    }

    /** Przebieg z radia → licznik serwisowy (najwyżej raz na minutę, tylko sensowne wartości). */
    private fun odo(km: Double) {
        val now = System.currentTimeMillis()
        if (km !in 1000.0..3_000_000.0 || now - lastOdoWrite < 60_000) return
        val cur = ServiceRepository.odometerKm.value
        if (ServiceRepository.odometerSet.value && kotlin.math.abs(cur - km) < 1.0) return
        lastOdoWrite = now
        try { ServiceRepository.setOdometer(km.toInt()) } catch (_: Exception) {}
    }

    /** Ustawienia systemowe i właściwości systemu – tylko gdy skaner otwarty albo coś stamtąd przypisano. */
    private suspend fun pollLoop() {
        var n = 0
        while (true) {
            val keys = mappings.value.values.map { it.key }
            val wantSet = scannerOpen.value || keys.any { it.startsWith("set:") }
            val wantProp = scannerOpen.value || keys.any { it.startsWith("prop:") }
            val ctx = appCtx
            if (ctx != null && wantSet) {
                var count = 0
                for ((table, uri) in listOf("system" to Settings.System.CONTENT_URI, "secure" to Settings.Secure.CONTENT_URI, "global" to Settings.Global.CONTENT_URI)) {
                    try {
                        ctx.contentResolver.query(uri, arrayOf("name", "value"), null, null, null)?.use { c ->
                            while (c.moveToNext()) {
                                val k = c.getString(0) ?: continue
                                count++
                                observe("set:$table/$k", c.getString(1) ?: continue)
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
                sources.value = sources.value.copy(settingsKeys = count)
            }
            if (wantProp && n % 2 == 0) {
                var p: Process? = null
                try {
                    p = Runtime.getRuntime().exec("getprop")
                    try { p.outputStream.close() } catch (_: Exception) {}
                    var count = 0
                    p.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            val m = PROP.matchEntire(line) ?: return@forEach
                            count++
                            observe("prop:${m.groupValues[1]}", m.groupValues[2])
                        }
                    }
                    p.waitFor()
                    sources.value = sources.value.copy(props = count)
                } catch (_: Exception) {
                } finally {
                    try { p?.errorStream?.close() } catch (_: Exception) {}
                    try { p?.destroy() } catch (_: Exception) {}
                }
            }
            n++
            delay(if (scannerOpen.value) 1000 else 2000)
        }
    }

    private val PROP = Regex("""\[(.+?)]: \[(.*)]""")

    // ---------- przypisania ----------

    fun assign(f: Field, m: Mapping) {
        mappings.value = mappings.value + (f to m)
        signals[m.key]?.let { s -> values.value = values.value + (f to m.apply(s.value)) }
        save()
    }

    fun unassign(f: Field) {
        mappings.value = mappings.value - f
        values.value = values.value - f
        save()
    }

    private fun save() {
        val o = JSONObject()
        mappings.value.forEach { (f, m) -> o.put(f.name, JSONObject().put("k", m.key).put("s", m.scale).put("o", m.offset)) }
        Prefs.store?.edit()?.putString("can_map", o.toString())?.apply()
    }

    private fun load() {
        val raw = Prefs.store?.getString("can_map", null) ?: return
        try {
            val o = JSONObject(raw)
            val out = HashMap<Field, Mapping>()
            o.keys().forEach { k ->
                val f = try { Field.valueOf(k) } catch (_: Exception) { return@forEach }
                val m = o.getJSONObject(k)
                out[f] = Mapping(m.getString("k"), m.optDouble("s", 1.0), m.optDouble("o", 0.0))
            }
            mappings.value = out
        } catch (_: Exception) {
        }
    }

    fun clearSignals() {
        signals.keys.retainAll(mappings.value.values.map { it.key }.toSet())
        tick.value = System.currentTimeMillis()
    }

    /** Lista do skanera: najpierw to, co się zmienia, potem świeże. Sortujemy kopie (stałe wartości). */
    fun snapshot(onlyChanging: Boolean, query: String): List<Row> = try {
        val now = System.currentTimeMillis()
        signals.values.toList()
            .filter { query.isBlank() || it.key.contains(query, ignoreCase = true) }
            .map { Row(it.key, it.value, it.text, it.min, it.max, it.recentChanges(now), it.lastChange, it.lastUpdate) }
            .filter { !onlyChanging || it.changes > 0 || (it.lastChange > 0 && now - it.lastChange < 120_000) }
            .sortedWith(compareByDescending<Row> { it.changes }.thenByDescending { it.lastChange }.thenByDescending { it.lastUpdate }.thenBy { it.key })
            .take(300)
    } catch (_: Exception) {
        emptyList()
    }

    // ---------- radio: pakiety CAN ----------

    private val CAN_PKG = Regex("(can|mcu|carinfo|car\\.info|carservice|vehicle|syu|\\.ts\\.|\\.tw\\.|hzbhd|microntek|fyt|topway|autochips|nwd|carsetting)", RegexOption.IGNORE_CASE)

    /** Aplikacje systemowe radia, które mogą rozsyłać dane z CAN. */
    fun canPackages(ctx: Context): List<String> = try {
        ctx.packageManager.getInstalledApplications(0)
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0 || (it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0 }
            .map { it.packageName }
            .filter { p -> CAN_PKG.containsMatchIn(p) && !p.contains("scan", true) && !p.contains("camera", true) && !p.startsWith("com.google.") }
            .sorted()
    } catch (_: Exception) {
        emptyList()
    }

    // ---------- raport (do przesłania i analizy) ----------

    fun report(ctx: Context): String {
        val sb = StringBuilder()
        val now = System.currentTimeMillis()
        sb.append("CAR OS – dane z radia (CAN)\n")
        sb.append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(now))).append('\n')
        sb.append("Urządzenie: ${Build.MANUFACTURER} ${Build.MODEL} | device=${Build.DEVICE} board=${Build.BOARD} hw=${Build.HARDWARE} | Android ${Build.VERSION.RELEASE}\n")
        sb.append("Wersja: ${pl.mati.carlauncher.BuildConfig.VERSION_NAME}\n\n")
        val src = sources.value
        sb.append("Źródła: komunikaty=${src.actions}, FYT=${src.fyt}, TopWay MainApp=${TsCarBridge.status.value}, ustawienia=${src.settingsKeys}, właściwości=${src.props}\n")
        sb.append("Przypisane: ").append(mappings.value.entries.joinToString { "${it.key.label}=${it.value.key} ×${it.value.scale} ${it.value.offset}" }.ifBlank { "brak" }).append("\n\n")

        sb.append("== Aplikacje radia / CAN ==\n")
        val pm = ctx.packageManager
        for (p in src.canPackages.ifEmpty { canPackages(ctx) }) {
            sb.append(p)
            try {
                @Suppress("DEPRECATION")
                val pi = pm.getPackageInfo(p, PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS)
                sb.append(" v").append(pi.versionName).append('\n')
                pi.services?.forEach { sb.append("  serwis: ").append(it.name).append(if (it.exported) " (exported)" else "").append('\n') }
                pi.receivers?.forEach { sb.append("  odbiornik: ").append(it.name).append('\n') }
                pi.providers?.forEach { sb.append("  provider: ").append(it.authority).append('\n') }
            } catch (_: Exception) {
                sb.append('\n')
            }
        }
        sb.append("\n== Inne aplikacje systemowe (nie Google/Android) ==\n")
        try {
            pm.getInstalledApplications(0)
                .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0 }
                .map { it.packageName }
                .filter { !it.startsWith("com.google.") && !it.startsWith("com.android.") && !it.startsWith("android") }
                .sorted().forEach { sb.append(it).append('\n') }
        } catch (_: Exception) {
        }

        sb.append("\n== Sygnały (najpierw zmieniające się) ==\n")
        snapshot(false, "").take(250).forEach { s ->
            sb.append(String.format(java.util.Locale.US, "%s = %s  [min %s, max %s, zmian/min %d, odczytów %d]\n",
                s.key, fmt(s.value), fmt(s.min), fmt(s.max), s.changes, signals[s.key]?.updates ?: 0))
        }
        sb.append("\n== Ostatnie komunikaty ==\n")
        BroadcastProbe.log.value.forEach { sb.append(it).append('\n') }
        sb.append("\n== Nasłuchiwane komunikaty ==\n")
        BroadcastProbe.actions.value.forEach { sb.append(it).append('\n') }
        return sb.toString()
    }

    fun fmt(d: Double): String = when {
        d == Double.MAX_VALUE || d == -Double.MAX_VALUE -> "-"
        d == Math.floor(d) && kotlin.math.abs(d) < 1e12 -> d.toLong().toString()
        else -> String.format(java.util.Locale.US, "%.3f", d).trimEnd('0').trimEnd('.')
    }

    /** Zapis raportu do Pobrane/CarOS. Zwraca ścieżkę albo null. */
    fun saveReport(ctx: Context): String? = try {
        CrashLog.saveText(ctx, "CarOS-dane-radia.txt", report(ctx))
    } catch (_: Exception) {
        null
    }
}
