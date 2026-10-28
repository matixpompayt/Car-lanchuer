package pl.mati.carlauncher.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow

object Prefs {
    const val DEFAULT_ACCENT = 0xFF2FD3F0.toInt()

    private var sp: SharedPreferences? = null

    val accent = MutableStateFlow(DEFAULT_ACCENT)
    val obdMac = MutableStateFlow<String?>(null)

    fun init(context: Context) {
        if (sp != null) return
        val p = context.applicationContext.getSharedPreferences("car_launcher", Context.MODE_PRIVATE)
        sp = p
        accent.value = p.getInt("accent", DEFAULT_ACCENT)
        obdMac.value = p.getString("obd_mac", null)
    }

    fun setAccent(color: Int) {
        accent.value = color
        sp?.edit()?.putInt("accent", color)?.apply()
    }

    fun setObdMac(mac: String?) {
        obdMac.value = mac
        sp?.edit()?.putString("obd_mac", mac)?.apply()
    }
}
