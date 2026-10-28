package pl.mati.carlauncher.data

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.roundToInt

/** Jasność, głośność, Bluetooth, Wi-Fi – to, co launcher może zrobić bez roota. */
object SystemControls {

    val brightness = MutableStateFlow(100) // %
    val volume = MutableStateFlow(0)
    val volumeMax = MutableStateFlow(15)
    val bluetoothOn = MutableStateFlow(false)
    val wifiOn = MutableStateFlow(false)
    /** Moment ostatniej zmiany głośności z UI – do pokazania wskaźnika. */
    val volumeHudAt = MutableStateFlow(0L)

    @SuppressLint("MissingPermission")
    fun refresh(ctx: Context) {
        val am = ctx.getSystemService(AudioManager::class.java)
        if (am != null) {
            volumeMax.value = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            volume.value = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        }
        try {
            val b = Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            brightness.value = (b / 255f * 100).roundToInt().coerceIn(5, 100)
        } catch (_: Exception) {
        }
        @Suppress("DEPRECATION")
        bluetoothOn.value = try { BluetoothAdapter.getDefaultAdapter()?.isEnabled == true } catch (_: Exception) { false }
        wifiOn.value = try { ctx.applicationContext.getSystemService(WifiManager::class.java)?.isWifiEnabled == true } catch (_: Exception) { false }
    }

    fun setVolume(ctx: Context, v: Int) {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        val value = v.coerceIn(0, volumeMax.value)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
        volume.value = value
        volumeHudAt.value = System.currentTimeMillis()
    }

    fun canWriteSettings(ctx: Context) = Settings.System.canWrite(ctx)

    fun requestWriteSettings(ctx: Context) {
        val i = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + ctx.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(i) } catch (_: Exception) {}
    }

    /** Ustawia jasność: systemową (gdy jest zgoda) i zawsze okna launchera. */
    fun setBrightness(activity: Activity?, ctx: Context, percent: Int) {
        val p = percent.coerceIn(5, 100)
        brightness.value = p
        if (Settings.System.canWrite(ctx)) {
            try {
                Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, (p / 100f * 255).roundToInt())
            } catch (_: Exception) {
            }
        }
        activity?.window?.let { w ->
            val lp = w.attributes
            lp.screenBrightness = p / 100f
            w.attributes = lp
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun toggleBluetooth(ctx: Context): Boolean {
        val a = BluetoothAdapter.getDefaultAdapter() ?: return false
        return try {
            val ok = if (a.isEnabled) a.disable() else a.enable()
            if (ok) bluetoothOn.value = !a.isEnabled
            ok
        } catch (_: SecurityException) {
            false
        }
    }

    /** Android 10 nie pozwala aplikacjom włączać Wi-Fi – otwieramy panel systemowy. */
    fun openWifiPanel(ctx: Context) {
        val i = Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(i) } catch (_: Exception) {
            try { ctx.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        }
    }
}
