package pl.mati.carlauncher.data

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Telefon Bluetooth z MainUI (TopWay, com.ts.MainUI). MainUI pobiera książkę z telefonu (PBAP)
 * i udostępnia ją asystentom głosowym przez „TsSpeechLib”: serwis com.ts.tsspeechlib.bt.TsBtService,
 * interfejs com.ts.tsspeechlib.bt.ITsSpeechBt. Rozmawiamy z nim „ręcznie” (Parcel), bez AIDL.
 * Tą samą drogą dzwonimy – MainUI wybiera numer od razu przez profil HFP.
 */
object TsBtBridge {
    private const val ACTION = "com.ts.tsspeechlib.bt.TsBtService"
    private const val DESC = "com.ts.tsspeechlib.bt.ITsSpeechBt"
    private const val T_CALL = 1
    private const val T_ANSWER = 2
    private const val T_HANGUP = 3
    private const val T_CONNECTED = 4
    private const val T_PHONE_STATE = 5
    private const val T_DEV = 6
    private const val T_PB_LIST = 17

    /** Wpis książki MainUI (tabela phonebook): addr = adres Bluetooth telefonu, collect = ulubiony. */
    data class Entry(val addr: String?, val name: String, val num: String, val collect: Int)

    @Volatile private var binder: IBinder? = null
    val bound = MutableStateFlow(false)
    private var started = false

    fun start(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        bind(app)
        // MainUI ogłasza zmiany książki i połączenia – odśwież listy
        try {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) { PhoneBook.reload(800) }
            }
            val f = IntentFilter().apply {
                addAction("com.ts.bt.PHONEBOOK_UPDATE")
                addAction("com.ts.bt.SAVE_PHONEBOOK_COMPLETE")
                addAction("com.ts.bt.CONNECT_STATE_CHANGE")
                addAction("android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED")
            }
            app.registerReceiver(r, f)
        } catch (_: Exception) {
        }
    }

    private fun bind(ctx: Context) {
        try {
            val si = ctx.packageManager.queryIntentServices(Intent(ACTION), 0).firstOrNull()?.serviceInfo ?: return
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    binder = service
                    bound.value = service != null
                    PhoneBook.reload(300)
                }
                override fun onServiceDisconnected(name: ComponentName?) {
                    binder = null
                    bound.value = false
                }
            }
            ctx.bindService(Intent(ACTION).setComponent(ComponentName(si.packageName, si.name)), conn, Context.BIND_AUTO_CREATE)
        } catch (_: Exception) {
        }
    }

    private inline fun <T> call(code: Int, write: (Parcel) -> Unit = {}, read: (Parcel) -> T): T? {
        val b = binder ?: return null
        val d = Parcel.obtain()
        val r = Parcel.obtain()
        return try {
            d.writeInterfaceToken(DESC)
            write(d)
            if (!b.transact(code, d, r, 0)) return null
            r.readException()
            read(r)
        } catch (_: Exception) {
            null
        } finally {
            d.recycle()
            r.recycle()
        }
    }

    /** Czy MainUI ma połączony telefon. */
    fun connected(): Boolean? = call(T_CONNECTED) { it.readInt() == 1 }

    /** Nazwa połączonego telefonu (np. „Galaxy S23”). */
    fun deviceName(): String? = call(T_DEV) { it.readString() }?.takeIf { it.isNotBlank() }

    /** Stan rozmowy wg MainUI (0 = brak). */
    fun phoneState(): Int? = call(T_PHONE_STATE) { it.readInt() }

    /** Dzwoni przez MainUI (HFP). false – MainUI niedostępne. */
    fun dial(number: String): Boolean = call(T_CALL, { it.writeString(number) }) { true } ?: false

    fun answer(): Boolean = call(T_ANSWER) { true } ?: false
    fun hangup(): Boolean = call(T_HANGUP) { true } ?: false

    /** Książka telefoniczna z MainUI: writeTypedList(PhonebookData) – addr, name, num, pinyin, collect, first, middle, given. */
    fun phonebook(): List<Entry>? = call(T_PB_LIST) { r ->
        val n = r.readInt()
        if (n < 0) return@call emptyList()
        val out = ArrayList<Entry>(n)
        repeat(n) {
            if (r.readInt() == 0) return@repeat
            val addr = r.readString()
            val name = r.readString()
            val num = r.readString()
            r.readString() // pinyin
            val collect = r.readInt()
            r.readString(); r.readString(); r.readString() // first, middle, given
            if (!name.isNullOrBlank() && !num.isNullOrBlank()) out += Entry(addr, name.trim(), num.trim(), collect)
        }
        out
    }

    /** Adres Bluetooth telefonu połączonego przez HFP (profil HEADSET_CLIENT = 16). */
    @Volatile var connectedAddr: String? = null
        private set
    private var proxyAsked = false

    fun refreshConnectedAddr(ctx: Context) {
        if (proxyAsked) {
            return
        }
        proxyAsked = true
        try {
            val ad = BluetoothAdapter.getDefaultAdapter() ?: return
            ad.getProfileProxy(ctx.applicationContext, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                    holder = proxy
                    connectedAddr = try { proxy?.connectedDevices?.firstOrNull()?.address } catch (_: Exception) { null }
                }
                override fun onServiceDisconnected(profile: Int) { holder = null }
            }, 16)
        } catch (_: Exception) {
        }
    }

    @Volatile private var holder: BluetoothProfile? = null

    /** Aktualny adres (po zmianie telefonu) – z utrzymywanego proxy HFP. */
    fun currentAddr(): String? {
        val p = holder ?: return connectedAddr
        connectedAddr = try { p.connectedDevices?.firstOrNull()?.address } catch (_: Exception) { connectedAddr }
        return connectedAddr
    }
}
