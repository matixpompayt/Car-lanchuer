package pl.mati.carlauncher.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.roundToInt

/** GPS: pozycja, prędkość (zapas gdy brak OBD) i dystans trasy od uruchomienia. */
@SuppressLint("MissingPermission")
object LocationRepository : LocationListener {

    val location = MutableStateFlow<Location?>(null)
    val gpsSpeed = MutableStateFlow<Int?>(null)
    val tripMeters = MutableStateFlow(0f)

    private var started = false
    private var lastTripPoint: Location? = null
    private var appCtx: Context? = null
    /** Kiedy (SystemClock.elapsedRealtime) przyszedł ostatni odczyt GPS – do sprawdzania, czy prędkość jest aktualna. */
    @Volatile var lastGpsFixAt = 0L
        private set
    private var lastKick = 0L

    /** Prędkość z GPS tylko wtedy, gdy odczyt jest świeży (inaczej null – nie „ostatnia znana”). */
    fun freshGpsSpeed(maxAgeMs: Long = 6000): Int? =
        if (android.os.SystemClock.elapsedRealtime() - lastGpsFixAt <= maxAgeMs) gpsSpeed.value else null

    /**
     * Niektóre radia po uśpieniu „gubią” nasłuch GPS – gdy od dawna nie ma odczytu,
     * zarejestruj go od nowa (najwyżej raz na 2 min).
     */
    fun kickIfStale() {
        val ctx = appCtx ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastGpsFixAt < 120_000 || now - lastKick < 120_000) return
        lastKick = now
        try {
            val lm = ctx.getSystemService(LocationManager::class.java) ?: return
            lm.removeUpdates(this)
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (p in lm.allProviders) lm.requestLocationUpdates(p, 1000L, 0f, this, Looper.getMainLooper())
            }
        } catch (_: Exception) {
        }
    }

    fun start(context: Context) {
        appCtx = context.applicationContext
        if (started) return
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return
        val lm = context.getSystemService(LocationManager::class.java) ?: return
        started = true
        val providers = lm.allProviders
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (p !in providers) continue
            try {
                lm.getLastKnownLocation(p)?.let { if (location.value == null) location.value = it }
                lm.requestLocationUpdates(p, 1000L, 0f, this, Looper.getMainLooper())
            } catch (_: Exception) {
            }
        }
    }

    override fun onLocationChanged(l: Location) {
        location.value = l
        if (l.provider != LocationManager.GPS_PROVIDER) return
        lastGpsFixAt = android.os.SystemClock.elapsedRealtime()
        gpsSpeed.value = if (l.hasSpeed()) (l.speed * 3.6f).roundToInt() else null
        if (l.accuracy <= 30f) {
            lastTripPoint?.let { prev ->
                val dist = prev.distanceTo(l)
                // ignoruj szum na postoju i skoki
                if (dist in 3f..500f) tripMeters.value += dist
            }
            lastTripPoint = l
        }
    }

    // Android 10 nie ma domyślnych implementacji tych metod – muszą być nadpisane.
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}
