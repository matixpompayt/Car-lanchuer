package pl.mati.carosparking

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Zapisane miejsce parkowania, historia i wybrane auto (urządzenie Bluetooth radia). */
object Store {

    data class Spot(
        val lat: Double,
        val lon: Double,
        val time: Long,
        val accuracy: Float,
        val address: String? = null,
        val manual: Boolean = false,
    ) {
        val mapsUrl get() = String.format(Locale.US, "https://maps.google.com/?q=%.6f,%.6f", lat, lon)
        val coords get() = String.format(Locale.US, "%.5f, %.5f", lat, lon)
    }

    val spot = MutableStateFlow<Spot?>(null)
    val history = MutableStateFlow<List<Spot>>(emptyList())
    val carMac = MutableStateFlow<String?>(null)
    val carName = MutableStateFlow<String?>(null)
    /** true = telefon jest połączony z autem i śledzi pozycję (usługa działa). */
    val driving = MutableStateFlow(false)

    private var sp: SharedPreferences? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(ctx: Context) {
        if (sp != null) return
        val p = ctx.applicationContext.getSharedPreferences("parking", Context.MODE_PRIVATE)
        sp = p
        carMac.value = p.getString("car_mac", null)
        carName.value = p.getString("car_name", null)
        spot.value = p.getString("spot", null)?.let { decode(it) }
        history.value = try {
            val a = JSONArray(p.getString("history", "[]"))
            (0 until a.length()).mapNotNull { decode(a.getString(it)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun setCar(mac: String, name: String) {
        carMac.value = mac
        carName.value = name
        sp?.edit()?.putString("car_mac", mac)?.putString("car_name", name)?.apply()
    }

    fun isCar(mac: String?): Boolean = mac != null && mac.equals(carMac.value, ignoreCase = true)

    /** Zapisuje nowe miejsce, dopisuje do historii i w tle ustala adres. */
    fun saveSpot(ctx: Context, s: Spot) {
        init(ctx)
        spot.value = s
        history.value = (listOf(s) + history.value.filterNot { it.time == s.time }).take(20)
        persist()
        val app = ctx.applicationContext
        if (!s.manual) Notifs.parked(app, s)
        scope.launch {
            val addr = Locator.address(app, s.lat, s.lon) ?: return@launch
            val cur = spot.value
            val updated = s.copy(address = addr)
            if (cur != null && cur.time == s.time) spot.value = updated
            history.value = history.value.map { if (it.time == s.time) updated else it }
            persist()
            if (cur != null && cur.time == s.time && !s.manual) Notifs.parked(app, updated)
        }
    }

    fun clearHistory() {
        history.value = listOfNotNull(spot.value)
        persist()
    }

    private fun persist() {
        val a = JSONArray()
        history.value.forEach { a.put(encode(it)) }
        sp?.edit()
            ?.putString("spot", spot.value?.let { encode(it) })
            ?.putString("history", a.toString())
            ?.apply()
    }

    private fun encode(s: Spot): String = JSONObject()
        .put("lat", s.lat).put("lon", s.lon).put("t", s.time).put("acc", s.accuracy.toDouble())
        .put("a", s.address ?: "").put("m", s.manual)
        .toString()

    private fun decode(raw: String): Spot? = try {
        val o = JSONObject(raw)
        Spot(
            o.getDouble("lat"), o.getDouble("lon"), o.getLong("t"), o.optDouble("acc", 0.0).toFloat(),
            o.optString("a").ifBlank { null }, o.optBoolean("m"),
        )
    } catch (_: Exception) {
        null
    }
}
