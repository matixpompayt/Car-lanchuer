package pl.mati.carlauncher.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.zip.ZipFile

/**
 * Nasłuch komunikatów (broadcast) fabrycznej aplikacji radia, która nie ma sesji multimediów
 * (np. TopWay TS10/TS18: com.ts.MainUI). Nazwy komunikatów wyciągamy z kodu aplikacji,
 * odbieramy je i próbujemy odczytać częstotliwość, RDS i tytuł z BT.
 */
object BroadcastProbe {

    val actions = MutableStateFlow<List<String>>(emptyList())
    val log = MutableStateFlow<List<String>>(emptyList())
    val scanning = MutableStateFlow(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var receiver: BroadcastReceiver? = null
    private var appCtx: Context? = null

    /** Standardowe komunikaty odtwarzaczy – wiele fabrycznych aplikacji je wysyła. */
    private val STANDARD = listOf(
        "com.android.music.metachanged", "com.android.music.playstatechanged",
        "com.android.music.musicservicecommand", "android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED",
        "android.bluetooth.avrcp-controller.profile.action.TRACK_EVENT",
        "com.microntek.radio.report", "com.microntek.music.report", "com.microntek.bt.report",
        "com.syu.radio.Info", "com.tw.radio.info",
    )

    private val INTEREST = Regex("(radio|rds|fm|freq|band|bt|blue|a2dp|avrcp|music|media|track|song|id3|play|meta|info|report|station)", RegexOption.IGNORE_CASE)

    fun start(ctx: Context, pkg: String?) {
        appCtx = ctx.applicationContext
        if (pkg == null) {
            register(STANDARD)
            return
        }
        scope.launch {
            val sp = Prefs.store
            val ver = try { ctx.packageManager.getPackageInfo(pkg, 0).longVersionCode } catch (_: Exception) { -1L }
            val cacheKey = "probe_$pkg:$ver"
            val cached = sp?.getString("probe_actions", null)?.takeIf { sp.getString("probe_key", null) == cacheKey }
            val list = if (cached != null) {
                val a = JSONArray(cached)
                (0 until a.length()).map { a.getString(it) }
            } else {
                scanning.value = true
                val found = try { scan(ctx, pkg) } catch (_: Exception) { emptyList() }
                scanning.value = false
                val arr = JSONArray(); found.forEach { arr.put(it) }
                sp?.edit()?.putString("probe_actions", arr.toString())?.putString("probe_key", cacheKey)?.apply()
                found
            }
            withContext(Dispatchers.Main) { register((STANDARD + list).distinct()) }
        }
    }

    /** Szuka w plikach .dex aplikacji stałych wyglądających na nazwy komunikatów. */
    private fun scan(ctx: Context, pkg: String): List<String> {
        val ai = ctx.packageManager.getApplicationInfo(pkg, 0)
        val files = listOfNotNull(ai.sourceDir) + (ai.splitSourceDirs?.toList() ?: emptyList())
        val out = LinkedHashSet<String>()
        val re = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+){2,}""")
        for (f in files) {
            ZipFile(f).use { zip ->
                val entries = zip.entries().toList().filter { it.name.matches(Regex("classes\\d*\\.dex")) }
                for (e in entries) {
                    val bytes = zip.getInputStream(e).use { it.readBytes() }
                    val text = String(bytes, Charsets.ISO_8859_1)
                    for (m in re.findAll(text)) {
                        val s = m.value
                        if (s.length !in 10..100) continue
                        if (s.endsWith(".java") || s.endsWith(".kt") || s.endsWith(".png") || s.endsWith(".xml")) continue
                        val lower = s.lowercase()
                        val looksAction = lower.contains("action") || lower.contains("broadcast") || lower.contains(".report") ||
                            lower.contains(".info") || lower.contains("changed") || lower.contains("update") || lower.contains("notify") ||
                            lower.startsWith("com.ts") || lower.startsWith("com.tw.") || lower.startsWith("com.android.music")
                        if (looksAction && INTEREST.containsMatchIn(s)) out += s
                        if (out.size >= 400) return out.toList()
                    }
                }
            }
        }
        return out.toList()
    }

    private fun register(list: List<String>) {
        val ctx = appCtx ?: return
        receiver?.let { try { ctx.unregisterReceiver(it) } catch (_: Exception) {} }
        actions.value = list
        val filter = IntentFilter()
        list.forEach { filter.addAction(it) }
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) = handle(intent)
        }
        receiver = r
        try {
            ContextCompat.registerReceiver(ctx, r, filter, ContextCompat.RECEIVER_EXPORTED)
        } catch (_: Exception) {
        }
    }

    private fun handle(intent: Intent) {
        val action = intent.action ?: return
        val ex = intent.extras
        val pairs = mutableMapOf<String, String>()
        if (ex != null) for (k in ex.keySet()) pairs[k] = valueOf(ex, k)
        val line = formatTime(System.currentTimeMillis()) + " " + action +
            if (pairs.isEmpty()) "" else " → " + pairs.entries.joinToString(", ") { "${it.key}=${it.value}" }.take(300)
        log.value = (listOf(line) + log.value).take(40)
        interpret(action, pairs)
    }

    private fun valueOf(b: Bundle, k: String): String = try {
        @Suppress("DEPRECATION")
        when (val v = b.get(k)) {
            null -> "null"
            is Array<*> -> v.joinToString("|")
            is IntArray -> v.joinToString("|")
            else -> v.toString()
        }
    } catch (_: Exception) {
        "?"
    }

    /** Próba odczytu znanych pól: częstotliwość, nazwa stacji, RDS, tytuł, wykonawca. */
    private fun interpret(action: String, p: Map<String, String>) {
        if (p.isEmpty()) return
        fun find(vararg names: String): String? = p.entries.firstOrNull { e -> names.any { e.key.equals(it, true) } }?.value?.takeIf { it.isNotBlank() && it != "null" }
        val a = action.lowercase()
        val isRadio = a.contains("radio") || a.contains("fm") || a.contains("rds") || find("freq", "frequency", "mfreq", "curfreq") != null
        val isBt = a.contains("bt") || a.contains("blue") || a.contains("a2dp") || a.contains("avrcp")
        val title = find("title", "track", "song", "songname", "musicname", "name", "id3title")
        val artist = find("artist", "singer", "id3artist", "author")
        if (isRadio) {
            val freqRaw = find("freq", "frequency", "mfreq", "curfreq", "fre")?.toDoubleOrNull()
            val freq = freqRaw?.let {
                when {
                    it > 1_000_000 -> it / 1_000_000.0       // Hz
                    it > 10_000 -> it / 1000.0               // kHz
                    it > 500 -> it / 100.0                   // 10 kHz (np. 9860)
                    else -> it                               // MHz
                }
            }
            val ps = find("ps", "psname", "stationname", "station", "rdsps", "pty_name")
            val rt = find("rt", "radiotext", "rdstext", "rds_rt", "text")
            if (freq != null || ps != null || rt != null) {
                val freqTxt = freq?.let { if (it < 200) String.format(java.util.Locale("pl"), "%.1f MHz", it) else "${it.toInt()} kHz" }
                SourceInfoRepository.update("radio", ps ?: freqTxt, rt, if (ps != null) freqTxt else null)
            }
        } else if (isBt || title != null) {
            if (title != null || artist != null) SourceInfoRepository.update("bt", title, artist, null)
        }
    }

    private fun formatTime(ms: Long): String = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(ms))
}
