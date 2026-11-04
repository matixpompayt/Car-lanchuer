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

/**
 * Radar opadów RainViewer (darmowe API, bez klucza; kafle do zoomu 7 – mapa je powiększa).
 * Wymagany podpis: „Weather data by RainViewer”.
 */
object RadarRepository {
    data class Frame(val tiles: String, val time: Long)

    /** Najnowsza klatka radaru albo null (wyłączony / brak internetu). */
    val frame = MutableStateFlow<Frame?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** Włącza/wyłącza odświeżanie (co 5 min, dopóki radar jest na mapie). */
    fun setActive(on: Boolean) {
        if (!on) { job?.cancel(); job = null; return }
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                try { fetch() } catch (_: Exception) {}
                delay(5 * 60_000L)
            }
        }
    }

    private fun fetch() {
        val c = URL("https://api.rainviewer.com/public/weather-maps.json").openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", "CarOS")
        try {
            if (c.responseCode !in 200..299) return
            val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val host = o.optString("host").ifBlank { return }
            val past = o.optJSONObject("radar")?.optJSONArray("past") ?: return
            if (past.length() == 0) return
            val last = past.getJSONObject(past.length() - 1)
            val path = last.optString("path").ifBlank { return }
            frame.value = Frame("$host$path/256/{z}/{x}/{y}/2/1_1.png", last.optLong("time") * 1000)
        } finally { c.disconnect() }
    }
}
