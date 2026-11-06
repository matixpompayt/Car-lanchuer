package pl.mati.carlauncher.data

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Dbanie o auto w tle:
 *  - timer schłodzenia turbo (po mocnej jeździe: odczekaj na wolnych obrotach przed zgaszeniem),
 *  - kontrola akumulatora (napięcie spoczynkowe, przy rozruchu, ładowanie) – wynik leci też do CAR OS Connect.
 */
object CarCare {

    /** Turbo: sekundy do odczekania; done = schłodzone (komunikat na chwilę). */
    data class Turbo(val secondsLeft: Int, val done: Boolean = false)
    val turbo = MutableStateFlow<Turbo?>(null)

    /** level: 0 = dobrze, 1 = uwaga, 2 = słaby. */
    data class Battery(val time: Long, val rest: Float?, val crank: Float?, val charge: Float?, val level: Int, val msg: String) {
        fun toJson(): JSONObject = JSONObject().put("t", time)
            .put("r", rest?.toDouble() ?: JSONObject.NULL).put("k", crank?.toDouble() ?: JSONObject.NULL)
            .put("c", charge?.toDouble() ?: JSONObject.NULL).put("l", level).put("m", msg)
        companion object {
            fun from(o: JSONObject) = Battery(
                o.getLong("t"),
                if (o.isNull("r")) null else o.getDouble("r").toFloat(),
                if (o.isNull("k")) null else o.getDouble("k").toFloat(),
                if (o.isNull("c")) null else o.getDouble("c").toFloat(),
                o.optInt("l"), o.optString("m"),
            )
        }
    }
    val battery = MutableStateFlow<Battery?>(null)
    val batteryHistory = MutableStateFlow<List<Battery>>(emptyList())
    /** Aktualne napięcie (do podglądu), null = brak odczytu z OBD/CAN. */
    val voltage = MutableStateFlow<Float?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false
    private var appCtx: Context? = null

    fun start(ctx: Context) {
        if (started) return
        started = true
        appCtx = ctx.applicationContext
        loadBattery()
        scope.launch {
            while (isActive) {
                try { turboTick() } catch (_: Exception) {}
                try { batteryTick() } catch (_: Exception) {}
                delay(1000)
            }
        }
    }

    // ---------------- turbo ----------------

    private const val HEAT_MAX = 90       // maks. 90 s schładzania
    private var heat = 0.0                // potrzebne sekundy schładzania
    private var stoppedFor = 0
    private var shown = false
    private var doneUntil = 0L

    private fun turboTick() {
        if (!Prefs.turboTimer.value) { turbo.value = null; heat = 0.0; shown = false; return }
        val d = Vehicle.data.value
        val speed = d.speed ?: LocationRepository.gpsSpeed.value ?: 0
        val rpm = d.rpm
        val boost = d.boostBar
        val hard = speed >= 120 || (boost != null && boost >= 1.0f) || (rpm != null && rpm >= 3300)
        val now = System.currentTimeMillis()
        if (hard) heat = (heat + 2.0).coerceAtMost(HEAT_MAX.toDouble())
        else heat = (heat - 1.0).coerceAtLeast(0.0)   // spokojna jazda i wolne obroty schładzają
        stoppedFor = if (speed <= 2) stoppedFor + 1 else 0
        val engineOff = rpm != null && rpm < 300
        when {
            engineOff -> { shown = false; turbo.value = null }
            stoppedFor >= 4 && heat >= 15 -> { shown = true; turbo.value = Turbo(heat.toInt()) }
            shown && stoppedFor >= 4 && heat < 15 -> {
                // dojechało do zera na postoju – „możesz zgasić”
                if (heat <= 0.0) { shown = false; doneUntil = now + 6000; turbo.value = Turbo(0, done = true); beep() }
                else turbo.value = Turbo(heat.toInt())
            }
            speed > 2 -> { shown = false; if (now > doneUntil) turbo.value = null }
            now > doneUntil && !shown -> turbo.value = null
        }
    }

    private fun beep() {
        try { ToneGenerator(AudioManager.STREAM_MUSIC, 70).startTone(ToneGenerator.TONE_PROP_ACK, 300) } catch (_: Exception) {}
    }

    // ---------------- akumulator ----------------

    private var sessionStart = 0L
    private var restSamples = ArrayList<Float>()
    private var minBeforeRun: Float? = null
    private var runningSince = 0L
    private var chargeSamples = ArrayList<Float>()
    private var evaluated = false
    private var lastSeen = 0L
    private var lastAcc: Boolean? = null

    private fun batteryTick() {
        val v = Vehicle.data.value.voltage?.takeIf { it in 6f..17f }
        voltage.value = v
        val now = System.currentTimeMillis()
        if (v == null) return
        // nowa „sesja” po starcie radia, po włączeniu zapłonu (MainApp) albo po dłuższej przerwie w odczytach
        val accNow = TsCarBridge.acc.value
        val accOn = accNow == true && lastAcc == false
        lastAcc = accNow
        if (sessionStart == 0L || accOn || now - lastSeen > 10 * 60_000L) {
            sessionStart = now; restSamples.clear(); minBeforeRun = null; runningSince = 0L; chargeSamples.clear(); evaluated = false
        }
        lastSeen = now
        val rpm = Vehicle.data.value.rpm
        val running = (rpm != null && rpm > 400) || v >= 13.3f
        if (!running && runningSince == 0L) {
            if (now - sessionStart < 60_000L) restSamples += v
            minBeforeRun = minOf(minBeforeRun ?: v, v)
        }
        if (running && runningSince == 0L) runningSince = now
        if (running && runningSince > 0 && now - runningSince > 120_000L) chargeSamples += v
        if (!evaluated && runningSince > 0 && chargeSamples.size >= 30) {
            evaluated = true
            evaluate(now)
        }
    }

    private fun evaluate(now: Long) {
        // spoczynkowe: najwyższy odczyt sprzed rozruchu (po włączeniu odbiorników napięcie lekko spada)
        val rest = restSamples.maxOrNull()?.takeIf { it < 13.0f }
        val crank = minBeforeRun?.takeIf { rest != null && it < rest - 0.3f }
        val charge = chargeSamples.sorted()[chargeSamples.size / 2]
        var level = 0
        val notes = ArrayList<String>()
        if (rest != null) when {
            rest < 12.0f -> { level = 2; notes += "napięcie spoczynkowe tylko ${fmt(rest)} V" }
            rest < 12.3f -> { level = maxOf(level, 1); notes += "napięcie spoczynkowe ${fmt(rest)} V (niedoładowany)" }
        }
        if (crank != null) when {
            crank < 9.6f -> { level = 2; notes += "przy rozruchu spada do ${fmt(crank)} V" }
            crank < 10.2f -> { level = maxOf(level, 1); notes += "przy rozruchu ${fmt(crank)} V" }
        }
        when {
            charge < 13.2f -> { level = maxOf(level, 1); notes += "słabe ładowanie ${fmt(charge)} V – sprawdź alternator" }
            charge > 14.9f -> { level = maxOf(level, 1); notes += "za wysokie ładowanie ${fmt(charge)} V – regulator?" }
        }
        // trend: trzy kolejne spadki napięcia spoczynkowego
        val prev = batteryHistory.value.mapNotNull { it.rest }.take(3)
        if (rest != null && prev.size == 3 && rest < prev[0] && prev[0] < prev[1] && prev[1] < prev[2] && rest < 12.45f) {
            level = maxOf(level, 1); notes += "napięcie spada z dnia na dzień"
        }
        val msg = if (notes.isEmpty()) "Akumulator i ładowanie w porządku" else notes.joinToString(", ").replaceFirstChar { it.uppercase() }
        val b = Battery(now, rest, crank, charge, level, msg)
        battery.value = b
        batteryHistory.value = (listOf(b) + batteryHistory.value).take(30)
        saveBattery()
        ConnectRepository.publishStatusSoon()
    }

    private fun fmt(v: Float) = String.format(java.util.Locale("pl", "PL"), "%.1f", v)

    fun batteryJson(): Any = battery.value?.toJson() ?: JSONObject.NULL

    private fun loadBattery() {
        val sp = appCtx?.getSharedPreferences("car_care", Context.MODE_PRIVATE) ?: return
        try {
            val arr = JSONArray(sp.getString("battery", "[]"))
            val list = (0 until arr.length()).map { Battery.from(arr.getJSONObject(it)) }
            batteryHistory.value = list
            battery.value = list.firstOrNull()
        } catch (_: Exception) {}
    }

    private fun saveBattery() {
        val sp = appCtx?.getSharedPreferences("car_care", Context.MODE_PRIVATE) ?: return
        val arr = JSONArray()
        batteryHistory.value.forEach { arr.put(it.toJson()) }
        sp.edit().putString("battery", arr.toString()).apply()
    }
}
