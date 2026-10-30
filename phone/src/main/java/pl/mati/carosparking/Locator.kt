package pl.mati.carosparking

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Pozycja telefonu i adresy. */
object Locator {

    /** Najświeższa znana pozycja z dostępnych dostawców. */
    @SuppressLint("MissingPermission")
    fun lastKnown(ctx: Context): Location? {
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        return try {
            lm.getProviders(true).mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (_: Exception) { null } }
                .maxByOrNull { it.time }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Jednorazowy odczyt GPS (blokujący, maks. [timeoutS] s). Wołać poza wątkiem głównym.
     * Gdy się nie uda – najświeższa znana pozycja, jeśli nie starsza niż 3 min.
     */
    @SuppressLint("MissingPermission")
    fun freshFix(ctx: Context, timeoutS: Long = 25): Location? {
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        var result: Location? = null
        if (Build.VERSION.SDK_INT >= 30) {
            val latch = CountDownLatch(1)
            val cancel = CancellationSignal()
            val ex = Executors.newSingleThreadExecutor()
            try {
                val provider = if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
                lm.getCurrentLocation(provider, cancel, ex) { l -> result = l; latch.countDown() }
                latch.await(timeoutS, TimeUnit.SECONDS)
            } catch (_: Exception) {
            } finally {
                cancel.cancel()
                ex.shutdown()
            }
        }
        if (result != null) return result
        val last = lastKnown(ctx) ?: return null
        return if (System.currentTimeMillis() - last.time < 3 * 60_000) last else null
    }

    /** Adres: Geocoder telefonu, a gdy brak – OpenStreetMap (Nominatim). */
    fun address(ctx: Context, lat: Double, lon: Double): String? {
        try {
            if (Geocoder.isPresent()) {
                @Suppress("DEPRECATION")
                val a = Geocoder(ctx, Locale("pl")).getFromLocation(lat, lon, 1)?.firstOrNull()
                if (a != null) {
                    val street = listOfNotNull(a.thoroughfare, a.subThoroughfare).joinToString(" ")
                    val text = listOf(street, a.locality ?: a.subAdminArea ?: "").filter { it.isNotBlank() }.joinToString(", ")
                    if (text.isNotBlank()) return text
                }
            }
        } catch (_: Exception) {
        }
        return try {
            val url = String.format(Locale.US, "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=18&accept-language=pl&lat=%.6f&lon=%.6f", lat, lon)
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("User-Agent", "CarOS-Parking/1.0 (pl.mati.carosparking)")
            try {
                if (c.responseCode !in 200..299) return null
                val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                val a = o.optJSONObject("address") ?: return o.optString("display_name").ifBlank { null }
                val street = a.optString("road").ifBlank { a.optString("pedestrian") }.ifBlank { a.optString("amenity") }
                val num = a.optString("house_number")
                val town = a.optString("city").ifBlank { a.optString("town") }.ifBlank { a.optString("village") }
                listOf(listOf(street, num).filter { it.isNotBlank() }.joinToString(" "), town)
                    .filter { it.isNotBlank() }.joinToString(", ").ifBlank { null }
            } finally {
                c.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }
}
