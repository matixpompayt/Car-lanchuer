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

    const val K_SPEED = "ts:car/speed"
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

        val carOk = bind(app, A_CAR) { car = it; ensureLoop() }
        val fnOk = bind(app, A_FN) { fn = it; ensureLoop() }
        status.value = when {
            carOk && fnOk -> "łączę z MainApp…"
            carOk || fnOk -> "łączę z MainApp (częściowo)…"
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
                // prędkość co 0,5 s, reszta co sekundę
                if (c != null) callInt(c, D_CAR, C_SPEED)?.let {
                    got++
                    CanRepository.observe(K_SPEED, it.toString())
                    checkSpeed(it)
                }
                if (c != null && n % 2 == 0) {
                    callInt(c, D_CAR, C_KM)?.let { got++; CanRepository.observe(K_KM, it.toString()); autoMap(CanRepository.Field.ODO, K_KM, it, 1000..3_000_000) }
                    if (n % 10 == 0) callInt(c, D_CAR, C_OIL)?.let { got++; CanRepository.observe(K_OIL, it.toString()); autoMap(CanRepository.Field.FUEL_L, K_OIL, it, 1..150) }
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
                    c == null && f == null -> "rozłączono"
                    got > 0 -> "połączono z MainApp" + (if (c == null) " (bez danych auta)" else "")
                    else -> "połączono, ale MainApp nie odpowiada"
                }
                n++
                delay(500)
            }
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
