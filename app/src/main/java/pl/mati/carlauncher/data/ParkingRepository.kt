package pl.mati.carlauncher.data

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * „Gdzie zaparkowałem”: miejsce ostatniego postoju (≥ 20 s po jeździe) albo wyłączenia ekranu.
 * Po wyłączeniu radia zostaje zapisane miejsce parkowania – kod QR otwiera je w telefonie.
 */
object ParkingRepository {

    data class Spot(val lat: Double, val lon: Double, val time: Long, val address: String?) {
        val mapsUrl get() = String.format(Locale.US, "https://maps.google.com/?q=%.6f,%.6f", lat, lon)
    }

    val spot = MutableStateFlow<Spot?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false
    private var drove = false
    private var stoppedSince = 0L
    private var savedThisStop = false

    fun start() {
        if (started) return
        started = true
        load()
        scope.launch {
            while (isActive) {
                try { tick() } catch (_: Exception) {}
                delay(2000)
            }
        }
    }

    private fun tick() {
        val v = LocationRepository.gpsSpeed.value ?: return
        val now = System.currentTimeMillis()
        if (v >= 12) {
            drove = true
            stoppedSince = 0L
            savedThisStop = false
        } else if (v <= 3 && drove) {
            if (stoppedSince == 0L) stoppedSince = now
            if (!savedThisStop && now - stoppedSince >= 20_000) {
                savedThisStop = true
                saveHere(stoppedSince)
            }
        }
    }

    /** Wywoływane przy wyłączeniu ekranu / zatrzymaniu aplikacji. */
    fun saveNow() {
        if (!drove && spot.value != null) return
        saveHere(System.currentTimeMillis())
    }

    private fun saveHere(time: Long) {
        val l = LocationRepository.location.value ?: return
        val prev = spot.value
        val same = prev != null && RouteRepository.dist(prev.lat, prev.lon, l.latitude, l.longitude) < 40
        val s = Spot(l.latitude, l.longitude, if (same) prev!!.time else time, if (same) prev!!.address else null)
        spot.value = s
        persist(s)
        if (s.address == null) {
            scope.launch(Dispatchers.IO) {
                val a = try { reverse(s.lat, s.lon) } catch (_: Exception) { null } ?: return@launch
                val cur = spot.value ?: return@launch
                if (cur.lat == s.lat && cur.lon == s.lon) {
                    val n = cur.copy(address = a)
                    spot.value = n
                    persist(n)
                }
            }
        }
    }

    fun clear() {
        spot.value = null
        Prefs.store?.edit()?.remove("parking")?.apply()
    }

    private fun persist(s: Spot) {
        val o = JSONObject().put("lat", s.lat).put("lon", s.lon).put("t", s.time).put("a", s.address ?: "")
        Prefs.store?.edit()?.putString("parking", o.toString())?.apply()
    }

    private fun load() {
        val raw = Prefs.store?.getString("parking", null) ?: return
        try {
            val o = JSONObject(raw)
            spot.value = Spot(o.getDouble("lat"), o.getDouble("lon"), o.getLong("t"), o.optString("a").ifBlank { null })
        } catch (_: Exception) {}
    }

    private fun reverse(lat: Double, lon: Double): String? {
        val url = String.format(Locale.US, "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=18&accept-language=pl&lat=%.6f&lon=%.6f", lat, lon)
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", "CarOS/1.1 (Android head unit launcher; pl.mati.carlauncher)")
        try {
            if (c.responseCode !in 200..299) return null
            val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val a = o.optJSONObject("address") ?: return o.optString("display_name").ifBlank { null }
            val street = a.optString("road").ifBlank { a.optString("pedestrian") }.ifBlank { a.optString("amenity") }
            val num = a.optString("house_number")
            val town = a.optString("city").ifBlank { a.optString("town") }.ifBlank { a.optString("village") }
            return listOf(listOf(street, num).filter { it.isNotBlank() }.joinToString(" "), town)
                .filter { it.isNotBlank() }.joinToString(", ").ifBlank { null }
        } finally {
            c.disconnect()
        }
    }

    fun qr(text: String, size: Int = 360): Bitmap? = try {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
        val px = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) px[y * size + x] = if (m.get(x, y)) Color.BLACK else Color.WHITE
        Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    } catch (_: Exception) {
        null
    }
}
