package pl.mati.carosparking

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import org.osmdroid.config.Configuration
import java.io.File

data class Perms(
    val bt: Boolean = true,
    val loc: Boolean = true,
    val bgLoc: Boolean = true,
    val notif: Boolean = true,
    val battery: Boolean = true,
) {
    val allOk get() = bt && loc && bgLoc && notif && battery
}

interface Actions {
    fun requestBt()
    fun requestLoc()
    fun requestBgLoc()
    fun requestNotif()
    fun requestBattery()
    fun openAutostart()
    fun pairedDevices(): List<Pair<String, String>>
    fun refresh()
}

class MainActivity : ComponentActivity(), Actions {

    private val perms = mutableStateOf(Perms())

    private val single = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    private val multi = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        Notifs.channels(this)
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(osmdroidBasePath, "tiles")
        }
        setContent { ParkApp(perms.value, this) }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        startIfCarConnected()
        Updater.check(silent = true)
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    override fun refresh() {
        val pm = getSystemService(PowerManager::class.java)
        perms.value = Perms(
            bt = Build.VERSION.SDK_INT < 31 || granted(Manifest.permission.BLUETOOTH_CONNECT),
            loc = granted(Manifest.permission.ACCESS_FINE_LOCATION),
            bgLoc = Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            notif = Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS),
            battery = pm?.isIgnoringBatteryOptimizations(packageName) ?: true,
        )
    }

    /** Aplikacja otwarta w aucie: jeśli telefon już jest połączony z radiem, od razu śledź pozycję. */
    @SuppressLint("MissingPermission")
    private fun startIfCarConnected() {
        val mac = Store.carMac.value ?: return
        if (DriveService.running || !perms.value.bt || !perms.value.loc) return
        try {
            val d = getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices?.firstOrNull { it.address.equals(mac, true) } ?: return
            val connected = BluetoothDevice::class.java.getMethod("isConnected").invoke(d) as? Boolean ?: false
            if (connected) DriveService.start(this)
        } catch (_: Exception) {
        }
    }

    override fun requestBt() {
        if (Build.VERSION.SDK_INT >= 31) single.launch(Manifest.permission.BLUETOOTH_CONNECT)
    }

    override fun requestLoc() {
        multi.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    override fun requestBgLoc() {
        if (!perms.value.loc) { requestLoc(); return }
        if (Build.VERSION.SDK_INT >= 29) single.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    override fun requestNotif() {
        if (Build.VERSION.SDK_INT >= 33) single.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    @SuppressLint("BatteryLife")
    override fun requestBattery() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {}
        }
    }

    /** Xiaomi / HyperOS: bez „Autostartu” system nie budzi aplikacji po połączeniu Bluetooth. */
    override fun openAutostart() {
        val tries = listOf(
            Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
            Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
        )
        for (i in tries) {
            try { startActivity(i); return } catch (_: Exception) {}
        }
    }

    @SuppressLint("MissingPermission")
    override fun pairedDevices(): List<Pair<String, String>> = try {
        val list = getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices?.toList().orEmpty()
        list.sortedByDescending {
            val c = it.bluetoothClass?.deviceClass
            c == android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO || c == android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE
        }.map { (it.name ?: it.address) to it.address }
    } catch (_: Exception) {
        emptyList()
    }
}
