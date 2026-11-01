package pl.mati.carlauncher.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Radia FYT (UIS7862 itp.): serwis com.syu.ms udostępnia dane MCU i dekodera CAN przez
 * interfejs com.syu.ipc.IRemoteToolkit. Rozmawiamy z nim „ręcznie” (Parcel), bez plików AIDL.
 * Na radiach innych producentów (np. TopWay) serwisu po prostu nie ma.
 */
object FytToolkit {
    private const val PKG = "com.syu.ms"
    private const val T_TOOLKIT = "com.syu.ipc.IRemoteToolkit"
    private const val T_MODULE = "com.syu.ipc.IRemoteModule"
    private const val T_CALLBACK = "com.syu.ipc.IModuleCallback"

    /** Moduły: 0 = główny (MCU), 4 = dźwięk, 7 = CAN-bus, 13/14 = dodatkowe CAN w części firmware. */
    private val MODULES = mapOf(0 to 120, 4 to 40, 7 to 400, 13 to 120, 14 to 120)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var bound = false

    private fun status(s: String) {
        CanRepository.sources.value = CanRepository.sources.value.copy(fyt = s)
    }

    fun start(ctx: Context) {
        if (bound) return
        val installed = try { ctx.packageManager.getPackageInfo(PKG, 0); true } catch (_: Exception) { false }
        if (!installed) {
            status("brak (to nie radio FYT)")
            return
        }
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service == null) return
                status("połączono – rejestruję")
                scope.launch { register(service) }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                status("rozłączono")
            }
        }
        bound = try {
            ctx.bindService(Intent("com.syu.ms.toolkit").setPackage(PKG), conn, Context.BIND_AUTO_CREATE)
        } catch (_: Exception) {
            false
        }
        if (!bound) status("jest, ale nie pozwala się połączyć")
    }

    private fun register(toolkit: IBinder) {
        var ok = 0
        for ((module, codes) in MODULES) {
            val m = module(toolkit, module) ?: continue
            val cb = callback(module)
            for (code in 0 until codes) if (registerCode(m, cb, code)) ok++
        }
        status(if (ok > 0) "połączono ($ok kanałów)" else "połączono, ale bez danych")
    }

    private fun module(toolkit: IBinder, code: Int): IBinder? {
        val d = Parcel.obtain()
        val r = Parcel.obtain()
        return try {
            d.writeInterfaceToken(T_TOOLKIT)
            d.writeInt(code)
            toolkit.transact(IBinder.FIRST_CALL_TRANSACTION, d, r, 0)
            r.readException()
            r.readStrongBinder()
        } catch (_: Exception) {
            null
        } finally {
            d.recycle(); r.recycle()
        }
    }

    private fun registerCode(module: IBinder, cb: Binder, code: Int): Boolean {
        val d = Parcel.obtain()
        val r = Parcel.obtain()
        return try {
            d.writeInterfaceToken(T_MODULE)
            d.writeStrongBinder(cb)
            d.writeInt(code)
            d.writeInt(1)
            module.transact(IBinder.FIRST_CALL_TRANSACTION + 2, d, r, 0)
            r.readException()
            true
        } catch (_: Exception) {
            false
        } finally {
            d.recycle(); r.recycle()
        }
    }

    private fun callback(module: Int) = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == IBinder.FIRST_CALL_TRANSACTION) {
                try {
                    data.enforceInterface(T_CALLBACK)
                    val update = data.readInt()
                    val ints = data.createIntArray()
                    val flts = data.createFloatArray()
                    val strs = data.createStringArray()
                    val k = "fyt:m$module/c$update"
                    ints?.let { if (it.size == 1) CanRepository.observe(k, it[0].toString()) else if (it.isNotEmpty()) CanRepository.observe(k, it.joinToString("|")) }
                    flts?.forEachIndexed { i, f -> CanRepository.observe("$k/f$i", f.toString()) }
                    strs?.forEachIndexed { i, s -> if (s != null) CanRepository.observe("$k/s$i", s) }
                    reply?.writeNoException()
                } catch (_: Exception) {
                }
                return true
            }
            return super.onTransact(code, data, reply, flags)
        }
    }
}
