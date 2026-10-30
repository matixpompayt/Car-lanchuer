package pl.mati.carosparking

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Budzi aplikację przy połączeniu z autem; awaryjnie zapisuje miejsce, gdy usługa nie działała. */
class CarBtReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        Store.init(ctx)
        @Suppress("DEPRECATION")
        val d: BluetoothDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) ?: return
        if (!Store.isCar(d.address)) return
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> if (!DriveService.running) DriveService.start(ctx)
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                if (DriveService.running) return // usługa sama zapisze miejsce
                val pending = goAsync()
                val app = ctx.applicationContext
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val l = Locator.freshFix(app, 25)
                        if (l != null) Store.saveSpot(app, Store.Spot(l.latitude, l.longitude, System.currentTimeMillis(), l.accuracy))
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}
