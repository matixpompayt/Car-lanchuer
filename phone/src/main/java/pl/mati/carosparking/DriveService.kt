package pl.mati.carosparking

import android.annotation.SuppressLint
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Działa, gdy telefon jest połączony z radiem w aucie: trzyma świeżą pozycję GPS.
 * Po rozłączeniu Bluetooth (zgaszenie auta) zapisuje miejsce parkowania i się wyłącza.
 */
class DriveService : Service(), LocationListener {

    companion object {
        private const val TAG = "CarOsParking"
        const val ACTION_GONE = "pl.mati.carosparking.CAR_GONE"

        @Volatile
        var running = false
            private set

        /** Zwraca false, gdy system nie pozwolił uruchomić usługi w tle. */
        fun start(ctx: Context): Boolean = try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, DriveService::class.java))
            true
        } catch (e: Exception) {
            Log.w(TAG, "Nie mogę uruchomić usługi: $e")
            false
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var last: Location? = null
    private var parking = false
    private var lm: LocationManager? = null

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    @Suppress("DEPRECATION")
                    val d: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    if (Store.isCar(d?.address)) carGone()
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val st = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                    if (st == BluetoothAdapter.STATE_TURNING_OFF || st == BluetoothAdapter.STATE_OFF) carGone()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(Notifs.ID_DRIVE, Notifs.driving(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(Notifs.ID_DRIVE, Notifs.driving(this))
            }
        } catch (e: Exception) {
            Log.w(TAG, "startForeground: $e")
            stopSelf()
            return
        }
        running = true
        Store.driving.value = true
        ContextCompat.registerReceiver(
            this, btReceiver,
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            },
            ContextCompat.RECEIVER_EXPORTED,
        )
        startUpdates()
    }

    @SuppressLint("MissingPermission")
    private fun startUpdates() {
        val m = getSystemService(LocationManager::class.java) ?: return
        lm = m
        last = Locator.lastKnown(this)
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (m.isProviderEnabled(p)) m.requestLocationUpdates(p, 3000L, 0f, this, Looper.getMainLooper())
            } catch (e: Exception) {
                Log.w(TAG, "requestLocationUpdates($p): $e")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_GONE) carGone()
        return START_NOT_STICKY
    }

    override fun onLocationChanged(l: Location) {
        val prev = last
        // GPS ma pierwszeństwo przed siecią; sieć tylko gdy brak świeżego GPS
        if (prev == null || l.provider == LocationManager.GPS_PROVIDER || l.time - prev.time > 20_000 || l.accuracy < prev.accuracy) {
            last = l
        }
        if (parking && l.provider == LocationManager.GPS_PROVIDER && l.accuracy <= 25f) finish(l)
    }

    /** Auto zgaszone (Bluetooth rozłączony): zapisz miejsce. */
    private fun carGone() {
        if (parking) return
        parking = true
        val l = last
        val fresh = l != null && System.currentTimeMillis() - l.time < 30_000 && l.accuracy <= 35f
        if (fresh) {
            finish(l!!)
        } else {
            // czekaj chwilę na dobry odczyt GPS, potem weź najlepsze, co jest
            handler.postDelayed({ finish(last) }, 40_000)
        }
    }

    private var done = false

    private fun finish(l: Location?) {
        if (done) return
        done = true
        handler.removeCallbacksAndMessages(null)
        if (l != null) {
            Store.saveSpot(this, Store.Spot(l.latitude, l.longitude, System.currentTimeMillis(), l.accuracy))
        }
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        Store.driving.value = false
        handler.removeCallbacksAndMessages(null)
        try { lm?.removeUpdates(this) } catch (_: Exception) {}
        try { unregisterReceiver(btReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}
