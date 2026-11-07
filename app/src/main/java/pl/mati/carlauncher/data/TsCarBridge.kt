package pl.mati.carlauncher.data

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Radia TopWay (com.ts.MainUI): MainApp udostępnia dane auta asystentom głosowym przez
 * „TsSpeechLib” – serwisy com.ts.tsspeechlib.car.TsCarService (prędkość, przebieg, paliwo,
 * kierunkowskazy, awaryjne) i com.ts.tsspeechlib.function.TsFunctionService (wsteczny,
 * światła, hamulec ręczny, typ CAN). Rozmawiamy z nimi „ręcznie” (Parcel) – kody wywołań
 * wzięte z biblioteki w aplikacji com.ts.car.client.
 *
 * Wartości trafiają do skanera CAN jako „ts:…”, a przebieg, paliwo i (po sprawdzeniu z GPS)
 * prędkość przypisują się same.
 */
object TsCarBridge {
    private const val A_CAR = "com.ts.tsspeechlib.car.TsCarService"
    private const val D_CAR = "com.ts.tsspeechlib.car.ITsSpeechCar"
    private const val A_INFO = "com.ts.can.carinfo.CarInfoService"
    private const val D_INFO = "com.ts.can.carinfo.ICarInfoService"
    private const val I_BASE = 6
    private const val A_FN = "com.ts.tsspeechlib.function.TsFunctionService"
    private const val D_FN = "com.ts.tsspeechlib.function.ITsSpeechFunction"

    // kody wywołań ITsSpeechCar
    private const val C_KM = 17
    private const val C_OIL = 18
    private const val C_LEFT = 19
    private const val C_RIGHT = 20
    private const val C_EMERGENCY = 21
    private const val C_SPEED = 22

    // kody wywołań ITsSpeechFunction
    private const val F_ILL = 34
    private const val F_BACK = 35
    private const val F_BRAKES = 36
    private const val F_CAN_TYPE = 41

    const val K_SPEED = "ts:base/speed"
    /** Stary klucz (TsCarService.GetSpeed: przy braku CAN zwraca prędkość z GPS radia – skakała). */
    private const val K_SPEED_OLD = "ts:car/speed"

    /** Nazwy pól tablicy requestCarBaseInfo() (Can.mCarMsg w MainApp). */
    private val BASE = mapOf(
        0 to "valid", 1 to "speed_dw", 2 to "speed", 3 to "rpm", 4 to "water_temp", 5 to "tpms_dw",
        6 to "tpms_fl", 7 to "tpms_fr", 8 to "tpms_rl", 9 to "tpms_rr", 10 to "oil_dw", 11 to "avg_fuel",
        12 to "inst_fuel", 13 to "range", 14 to "distance", 15 to "bat_v", 16 to "distance_dw",
        17 to "turn_left", 18 to "turn_right", 19 to "belt_driver", 20 to "high_beam", 21 to "low_beam",
        22 to "foot_brake", 23 to "hand_brake", 24 to "gear", 25 to "ill", 26 to "warn_led",
        27 to "front_fog", 28 to "rear_fog", 29 to "throttle", 30 to "fuel_level", 31 to "coolant2",
        32 to "oil_press", 33 to "baro", 34 to "fuel_temp", 35 to "maint", 36 to "belt_passenger",
        37 to "gearbox_oil", 38 to "trunk", 39 to "avg_speed", 40 to "trip1", 41 to "trip2",
        59 to "width_light", 60 to "wiper", 61 to "door_fl", 62 to "door_fr", 63 to "door_rl", 64 to "door_rr",
        65 to "door_trunk", 66 to "door_hood", 67 to "out_temp", 68 to "oil_temp", 69 to "door_lock",
        70 to "charge", 71 to "warn",
    )
    const val K_KM = "ts:car/km"
    const val K_OIL = "ts:car/fuel_l"

    /** Stan połączenia – do raportu i ustawień. */
    val status = MutableStateFlow("nie sprawdzono")
    /** Zapłon (ACC) wg MainApp: true = włączony, null = nie wiadomo. */
    val acc = MutableStateFlow<Boolean?>(null)
    val reverse = MutableStateFlow(false)
    val lights = MutableStateFlow(false)
    val handbrake = MutableStateFlow(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var car: IBinder? = null
    @Volatile private var fn: IBinder? = null
    @Volatile private var info: IBinder? = null

    /**
     * Pola, które przejściówka CAN wysyła tylko na zapytanie – w MainApp robi je jedynie ekran
     * „informacje o aucie”, gdy jest otwarty. Gdy obroty i napięcie przestają się zmieniać,
     * cała grupa jest nieaktualna i jej nie pokazujemy (zamiast „zamrożonych” liczb).
     */
    private val QUERY_GROUP = setOf(3, 4, 11, 12, 13, 15, 67, 68)
    private var lastRpm = Int.MIN_VALUE
    private var lastBat = Int.MIN_VALUE
    private var groupChanged = 0L
    /** Czy dane z zapytania (obroty, napięcie, temperatura…) są teraz świeże. */
    val queryFresh = MutableStateFlow(false)
    private var loop: Job? = null
    private var started = false
    private var speedMatches = 0

    fun start(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        // zapłon z MainApp
        try {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    when (i?.action) {
                        "com.ts.main.uiaccon" -> acc.value = true
                        "com.ts.main.uiaccoff" -> acc.value = false
                    }
                }
            }
            val f = IntentFilter().apply { addAction("com.ts.main.uiaccon"); addAction("com.ts.main.uiaccoff") }
            app.registerReceiver(r, f)
        } catch (_: Exception) {}

        val infoOk = bind(app, A_INFO) { info = it; ensureLoop() }
        val carOk = bind(app, A_CAR) { car = it; ensureLoop() }
        val fnOk = bind(app, A_FN) { fn = it; ensureLoop() }
        status.value = when {
            infoOk -> "łączę z MainApp…"
            carOk || fnOk -> "łączę z MainApp (bez danych CAN)…"
            else -> "brak (to nie radio TopWay albo MainApp nie pozwala)"
        }
    }

    private fun bind(ctx: Context, action: String, onBinder: (IBinder?) -> Unit): Boolean {
        return try {
            val list = ctx.packageManager.queryIntentServices(Intent(action), 0)
            val si = list.firstOrNull()?.serviceInfo ?: return false
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) { onBinder(service) }
                override fun onServiceDisconnected(name: ComponentName?) { onBinder(null) }
            }
            ctx.bindService(Intent(action).setComponent(ComponentName(si.packageName, si.name)), conn, Context.BIND_AUTO_CREATE)
        } catch (_: Exception) {
            false
        }
    }

    private fun ensureLoop() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            var n = 0
            fn?.let { b -> callInt(b, D_FN, F_CAN_TYPE)?.let { CanRepository.observe("ts:fn/can_type", it.toString()) } }
            while (isActive) {
                val c = car
                val f = fn
                var got = 0
                val i = info
                // dane z CAN (Can.mCarMsg) co 0,5 s
                if (i != null) callIntArray(i, D_INFO, I_BASE)?.let { arr ->
                    got++
                    val now = System.currentTimeMillis()
                    val rpm = arr.getOrNull(3) ?: 0
                    val bat = arr.getOrNull(15) ?: 0
                    if (rpm != lastRpm || bat != lastBat) {
                        if (lastRpm != Int.MIN_VALUE) groupChanged = now
                        lastRpm = rpm; lastBat = bat
                    }
                    val fresh = now - groupChanged < 12_000
                    queryFresh.value = fresh
                    for ((idx, name) in BASE) {
                        if (idx >= arr.size) continue
                        val key = "ts:base/$name"
                        if (idx in QUERY_GROUP && !fresh) CanRepository.expire(key)
                        else CanRepository.observe(key, arr[idx].toString())
                    }
                    if (fresh) autoBase(arr) else checkSpeed(arr.getOrNull(2) ?: 0)
                }
                if (c != null && n % 2 == 0) {
                    callInt(c, D_CAR, C_LEFT)?.let { CanRepository.observe("ts:car/left", it.toString()) }
                    callInt(c, D_CAR, C_RIGHT)?.let { CanRepository.observe("ts:car/right", it.toString()) }
                    callInt(c, D_CAR, C_EMERGENCY)?.let { CanRepository.observe("ts:car/hazard", it.toString()) }
                }
                if (f != null && n % 4 == 0) {
                    callInt(f, D_FN, F_BACK)?.let { got++; reverse.value = it == 1; CanRepository.observe("ts:fn/reverse", it.toString()) }
                    callInt(f, D_FN, F_ILL)?.let { lights.value = it == 1; CanRepository.observe("ts:fn/lights", it.toString()) }
                    callInt(f, D_FN, F_BRAKES)?.let { handbrake.value = it == 1; CanRepository.observe("ts:fn/handbrake", it.toString()) }
                }
                status.value = when {
                    i == null && c == null && f == null -> "rozłączono"
                    got > 0 && i != null -> "połączono z MainApp (dane CAN" + (if (queryFresh.value) ", obroty i napięcie na żywo)" else "; obroty, napięcie i temperatury tylko przy otwartym ekranie auta w MainApp)")
                    got > 0 -> "połączono z MainApp (bez danych CAN)"
                    else -> "połączono, ale MainApp nie odpowiada"
                }
                n++
                delay(500)
            }
        }
    }

    /** Pewne pola z CAN przypisz same (obroty, napięcie, przebieg, zasięg); temperatury zostają do sprawdzenia w skanerze. */
    private fun autoBase(arr: IntArray) {
        fun v(i: Int) = arr.getOrNull(i) ?: 0
        // stara prędkość z TsCarService (mieszała CAN z GPS) → prędkość z CAN
        CanRepository.mappings.value[CanRepository.Field.SPEED]?.let { m ->
            if (m.key == K_SPEED_OLD) CanRepository.unassign(CanRepository.Field.SPEED)
        }
        checkSpeed(v(2))
        autoMap(CanRepository.Field.RPM, "ts:base/rpm", v(3), 300..8000)
        autoMap(CanRepository.Field.ODO, "ts:base/distance", v(14), 1000..3_000_000)
        autoMap(CanRepository.Field.RANGE, "ts:base/range", v(13), 10..2000)
        val bat = v(15)
        if (!CanRepository.mappings.value.containsKey(CanRepository.Field.VOLTAGE)) {
            val scale = when (bat) { in 900..1700 -> 0.01; in 90..170 -> 0.1; in 9..17 -> 1.0; else -> null }
            if (scale != null) CanRepository.assign(CanRepository.Field.VOLTAGE, CanRepository.Mapping("ts:base/bat_v", scale))
        }
    }

    /** Przypisz pole, jeśli użytkownik nic nie przypisał, a wartość wygląda sensownie. */
    private fun autoMap(field: CanRepository.Field, key: String, v: Int, range: IntRange) {
        if (v !in range) return
        if (CanRepository.mappings.value.containsKey(field)) return
        CanRepository.assign(field, CanRepository.Mapping(key))
    }

    /** Prędkość przypisz dopiero, gdy kilka razy zgodzi się z GPS w czasie jazdy (żeby zero na postoju nie zastąpiło GPS). */
    private fun checkSpeed(v: Int) {
        if (CanRepository.mappings.value.containsKey(CanRepository.Field.SPEED)) return
        val gps = LocationRepository.gpsSpeed.value ?: return
        if (gps < 20) return
        speedMatches = if (v > 0 && abs(v - gps) <= maxOf(8.0, gps * 0.15)) speedMatches + 1 else 0
        if (speedMatches >= 5) CanRepository.assign(CanRepository.Field.SPEED, CanRepository.Mapping(K_SPEED))
    }

    private fun callIntArray(b: IBinder, descriptor: String, code: Int): IntArray? {
        val d = Parcel.obtain()
        val r = Parcel.obtain()
        return try {
            d.writeInterfaceToken(descriptor)
            if (!b.transact(code, d, r, 0)) return null
            r.readException()
            r.createIntArray()
        } catch (_: Exception) {
            null
        } finally {
            d.recycle()
            r.recycle()
        }
    }

    private fun callInt(b: IBinder, descriptor: String, code: Int): Int? {
        val d = Parcel.obtain()
        val r = Parcel.obtain()
        return try {
            d.writeInterfaceToken(descriptor)
            if (!b.transact(code, d, r, 0)) return null
            r.readException()
            r.readInt()
        } catch (_: Exception) {
            null
        } finally {
            d.recycle()
            r.recycle()
        }
    }
}
