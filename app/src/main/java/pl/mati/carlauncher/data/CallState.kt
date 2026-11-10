package pl.mati.carlauncher.data

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Parcel
import android.os.Parcelable
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Rozmowa telefoniczna przez Bluetooth (radio jako zestaw głośnomówiący, profil HFP).
 * Stos Bluetooth ogłasza każdą zmianę komunikatem AG_CALL_CHANGED z obiektem BluetoothHeadsetClientCall
 * (klasa ukryta – czytamy ją refleksją, a gdy się nie da, z Parcela). Sterowanie (odbierz / rozłącz)
 * idzie przez MainUI, tak samo jak wybieranie numeru; wyciszenie mikrofonu – przez AudioManager,
 * tak samo robi to MainUI.
 */
object CallState {
    // stany BluetoothHeadsetClientCall
    private const val ACTIVE = 0
    private const val HELD = 1
    private const val DIALING = 2
    private const val ALERTING = 3
    private const val INCOMING = 4
    private const val WAITING = 5
    private const val HELD_RH = 6
    private const val TERMINATED = 7

    enum class Phase { IDLE, INCOMING, DIALING, ACTIVE, HELD }

    data class State(
        val phase: Phase = Phase.IDLE,
        val number: String = "",
        val outgoing: Boolean = false,
        /** SystemClock.elapsedRealtime() początku rozmowy (0 – jeszcze nie odebrano). */
        val activeSince: Long = 0,
        /** Druga rozmowa czeka (połączenie oczekujące w trakcie rozmowy). */
        val waiting: Boolean = false,
        val muted: Boolean = false,
        /** Zmienia się przy każdym nowym połączeniu – ekran wie, kiedy znów się rozwinąć. */
        val seq: Int = 0,
    ) {
        val inCall get() = phase != Phase.IDLE
    }

    private data class Call(val id: Int, val state: Int, val number: String, val outgoing: Boolean)

    val state = MutableStateFlow(State())

    private val calls = LinkedHashMap<Int, Call>()
    private var activeAt = 0L
    private var seq = 0
    private var started = false
    private var app: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watch: Job? = null

    fun start(ctx: Context) {
        if (started) return
        started = true
        val a = ctx.applicationContext
        app = a
        try {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    i ?: return
                    try {
                        when (i.action) {
                            "android.bluetooth.headsetclient.profile.action.AG_CALL_CHANGED" -> onCall(i)
                            "android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED" ->
                                if (i.getIntExtra("android.bluetooth.profile.extra.STATE", -1) == 0) clear()
                        }
                    } catch (_: Exception) {
                    }
                }
            }
            val f = IntentFilter().apply {
                addAction("android.bluetooth.headsetclient.profile.action.AG_CALL_CHANGED")
                addAction("android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED")
            }
            a.registerReceiver(r, f)
        } catch (_: Exception) {
        }
        // Launcher uruchomiony w trakcie rozmowy – zapytaj MainUI.
        scope.launch {
            delay(4000)
            if (calls.isEmpty()) poll()
        }
    }

    private fun onCall(i: Intent) {
        @Suppress("DEPRECATION")
        val p = i.getParcelableExtra<Parcelable>("android.bluetooth.headsetclient.extra.CALL") ?: return
        val c = parse(p) ?: return
        calls.remove(-1) // zastępczy wpis z odpytania MainUI – teraz mamy prawdziwe dane
        if (c.state == TERMINATED) calls.remove(c.id)
        else {
            val old = calls[c.id]
            // numer bywa pusty w pierwszym komunikacie – zachowaj znany
            calls[c.id] = if (c.number.isBlank() && old != null) c.copy(number = old.number) else c
        }
        publish()
    }

    /** BluetoothHeadsetClientCall → nasze pola. Najpierw getterami (refleksja), potem z Parcela. */
    private fun parse(p: Parcelable): Call? {
        try {
            val k = p.javaClass
            val id = k.getMethod("getId").invoke(p) as Int
            val st = k.getMethod("getState").invoke(p) as Int
            val num = (k.getMethod("getNumber").invoke(p) as? String).orEmpty()
            val out = k.getMethod("isOutgoing").invoke(p) as Boolean
            return Call(id, st, clean(num), out)
        } catch (_: Throwable) {
        }
        // writeToParcel: device, id, uuid, state, number, multiParty, outgoing, inBandRing
        val parcel = Parcel.obtain()
        return try {
            p.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            parcel.readParcelable<BluetoothDevice>(BluetoothDevice::class.java.classLoader)
            val id = parcel.readInt()
            parcel.readString()
            val st = parcel.readInt()
            val num = parcel.readString().orEmpty()
            parcel.readInt()
            val out = parcel.readInt() == 1
            if (st in ACTIVE..TERMINATED) Call(id, st, clean(num), out) else null
        } catch (_: Throwable) {
            null
        } finally {
            parcel.recycle()
        }
    }

    private fun clean(n: String) = if (n.all { it == '0' }) "" else n.trim()

    private fun publish() {
        val all = calls.values.toList()
        val ring = all.firstOrNull { it.state == INCOMING || it.state == WAITING }
        val dial = all.firstOrNull { it.state == DIALING || it.state == ALERTING }
        val act = all.firstOrNull { it.state == ACTIVE }
        val held = all.firstOrNull { it.state == HELD || it.state == HELD_RH }
        val old = state.value
        val (phase, main) = when {
            ring != null && act == null -> Phase.INCOMING to ring
            dial != null -> Phase.DIALING to dial
            act != null -> Phase.ACTIVE to act
            held != null -> Phase.HELD to held
            else -> Phase.IDLE to null
        }
        if (phase == Phase.IDLE) {
            if (old.inCall) {
                activeAt = 0
                setMute(false)
                PhoneBook.reload(2500) // nowy wpis w historii
            }
            stopWatch()
            state.value = State(seq = seq)
            return
        }
        if (!old.inCall) seq++
        if (phase == Phase.ACTIVE && activeAt == 0L) activeAt = SystemClock.elapsedRealtime()
        state.value = State(
            phase = phase,
            number = main?.number?.takeIf { it.isNotBlank() } ?: old.number,
            outgoing = main?.outgoing ?: false,
            activeSince = activeAt,
            waiting = ring != null && act != null,
            muted = micMuted(),
            seq = seq,
        )
        startWatch()
    }

    private fun clear() {
        if (calls.isEmpty() && !state.value.inCall) return
        calls.clear()
        publish()
    }

    /** Zabezpieczenie: gdy zgubimy komunikat o rozłączeniu, MainUI powie, że rozmowy już nie ma. */
    private fun startWatch() {
        if (watch?.isActive == true) return
        watch = scope.launch {
            var gone = 0
            while (isActive) {
                delay(3000)
                val s = TsBtBridge.phoneState() ?: continue
                gone = if (s == -1 || s == TERMINATED) gone + 1 else 0
                if (gone >= 2) { clear(); break }
            }
        }
    }

    private fun stopWatch() {
        watch?.cancel()
        watch = null
    }

    private fun poll() {
        val s = TsBtBridge.phoneState() ?: return
        if (s in ACTIVE..WAITING) {
            calls[-1] = Call(-1, s, "", s == DIALING || s == ALERTING)
            publish()
        }
    }

    fun answer() {
        if (!TsBtBridge.answer()) pl.mati.carlauncher.ui.Toaster.show("Nie udało się odebrać – użyj okna MainUI")
    }

    /** Odrzuć przychodzące albo zakończ rozmowę (MainUI sam wybiera, co zrobić). */
    fun hangup() {
        if (!TsBtBridge.hangup()) pl.mati.carlauncher.ui.Toaster.show("Nie udało się rozłączyć – użyj okna MainUI")
    }

    fun toggleMute() {
        setMute(!micMuted())
        state.value = state.value.copy(muted = micMuted())
    }

    private fun audio(): AudioManager? = app?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private fun micMuted(): Boolean = try { audio()?.isMicrophoneMute == true } catch (_: Exception) { false }

    private fun setMute(on: Boolean) {
        try { audio()?.isMicrophoneMute = on } catch (_: Exception) {
        }
    }
}
