package pl.mati.carlauncher.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.roundToInt

/** Temperatura zewnętrzna z Open-Meteo (bez klucza API), odświeżana co 15 min. */
object WeatherRepository {

    val temperature = MutableStateFlow<Int?>(null)

    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            while (isActive) {
                val l = LocationRepository.location.value
                val ok = l != null && runCatching { fetch(l.latitude, l.longitude) }.isSuccess
                delay(if (ok) 15 * 60_000L else 30_000L)
            }
        }
    }

    private fun fetch(lat: Double, lon: Double) {
        val url = URL(
            String.format(
                Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f&current=temperature_2m",
                lat, lon,
            ),
        )
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        try {
            val body = c.inputStream.bufferedReader().use { it.readText() }
            val t = JSONObject(body).getJSONObject("current").getDouble("temperature_2m")
            temperature.value = t.roundToInt()
        } finally {
            c.disconnect()
        }
    }
}
