package pl.mati.carlauncher.data

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import pl.mati.carlauncher.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Dziennik błędów radia: awarie, zacięcia (ekran nie odpowiada > 5 s – z miejscem w kodzie),
 * stan internetu (Wi-Fi / karta SIM), ładowanie mapy, brak pamięci. Po awarii albo zacięciu
 * wysyłany sam do CAR OS Connect (Ustawienia → Raport danych z radia → Udostępnij).
 */
object Diag {
    private const val LOG = "diag.log"
    private const val FREEZES = "freezes.txt"
    const val CRASHES = "crashes.txt"
    private const val TAG = "CarOsDiag"

    private var app: Context? = null
    private val io = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())
    private var started = false

    /** Jest internet (null = jeszcze nie wiadomo). */
    val online = MutableStateFlow<Boolean?>(null)
    @Volatile var netDesc = "nieznana"
        private set
    /** Stan mapy (do raportu). */
    @Volatile var mapState = "jeszcze nie ładowana"

    /** Ile awarii / zacięć w dzienniku (do ustawień). */
    val counts = MutableStateFlow(0 to 0)

    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
    private fun time() = fmt.format(Date())

    fun start(ctx: Context) {
        if (started) return
        started = true
        app = ctx.applicationContext
        log("Start CAR OS ${BuildConfig.VERSION_NAME} · pamięć: ${memInfo()}")
        refreshCounts()
        watchNetwork()
        watchdog()
    }

    fun log(msg: String) {
        Log.i(TAG, msg)
        val line = "${time()} $msg"
        io.execute { append(LOG, line, 64_000) }
    }

    private fun file(name: String): File? = app?.let { File(it.filesDir, name) }

    /** Dopisz i przytnij plik do [max] bajtów (zostają najnowsze). */
    private fun append(name: String, text: String, max: Int) {
        val f = file(name) ?: return
        try {
            f.appendText(text + "\n")
            if (f.length() > max) {
                val t = f.readText()
                f.writeText(t.takeLast(max * 3 / 4).substringAfter('\n'))
            }
        } catch (_: Exception) {
        }
    }

    private fun tail(name: String, max: Int): String = try {
        file(name)?.takeIf { it.exists() }?.readText()?.takeLast(max) ?: ""
    } catch (_: Exception) {
        ""
    }

    private fun refreshCounts() {
        io.execute {
            val c = tail(CRASHES, 200_000).split("\n=== AWARIA").size - 1
            val f = tail(FREEZES, 200_000).split("ZACIĘCIE:").size - 1
            counts.value = c.coerceAtLeast(0) to f.coerceAtLeast(0)
        }
    }

    // ---------- zacięcia ----------

    @Volatile private var tick = SystemClock.uptimeMillis()

    /** Co sekundę „ping” do wątku ekranu – brak odpowiedzi > 5 s = zacięcie, zapis stosu wywołań. */
    private fun watchdog() {
        Thread({
            var stuck = false
            var worst = 0L
            while (true) {
                main.post { tick = SystemClock.uptimeMillis() }
                try { Thread.sleep(1000) } catch (_: InterruptedException) { return@Thread }
                val lag = SystemClock.uptimeMillis() - tick
                if (lag > 5_000 && !stuck) {
                    stuck = true
                    worst = lag
                    val st = Looper.getMainLooper().thread.stackTrace.take(40).joinToString("\n") { "    at $it" }
                    append(
                        FREEZES,
                        "${time()} ZACIĘCIE: ekran nie odpowiada od ${lag / 1000} s · sieć: $netDesc · mapa: $mapState · pamięć: ${memInfo()}\n$st\n",
                        120_000,
                    )
                    log("ZACIĘCIE ${lag / 1000} s – zapisano miejsce w kodzie")
                } else if (stuck) {
                    if (lag > worst) worst = lag
                    if (lag < 1_500) {
                        stuck = false
                        log("Ekran znów odpowiada (zacięcie ok. ${worst / 1000} s)")
                        refreshCounts()
                        autoSend("zacięcie")
                    }
                }
            }
        }, "caros-watchdog").apply { isDaemon = true; priority = Thread.MAX_PRIORITY }.start()
    }

    // ---------- internet ----------

    private fun watchNetwork() {
        val ctx = app ?: return
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return
        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = update(cm)
                override fun onLost(network: Network) = update(cm)
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = update(cm)
            })
        } catch (e: Exception) {
            log("Sieć: nie da się nasłuchiwać (${e.javaClass.simpleName})")
        }
        update(cm)
    }

    private fun update(cm: ConnectivityManager) {
        val c = try { cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } } catch (_: Exception) { null }
        val type = when {
            c == null -> "brak sieci"
            c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "karta SIM"
            c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "inna"
        }
        val validated = c?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val desc = type + when {
            c == null -> ""
            validated -> " (internet OK)"
            else -> " (bez potwierdzonego internetu)"
        }
        if (desc != netDesc) {
            netDesc = desc
            log("Sieć: $desc")
        }
        val on = c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (online.value != on) {
            online.value = on
            // mapa: internet wrócił (np. karta SIM złapała zasięg po starcie) – ponów pobieranie od razu
            main.post {
                try { org.maplibre.android.MapLibre.setConnected(if (on) true else null) } catch (_: Throwable) {}
            }
        }
    }

    // ---------- pamięć ----------

    fun memInfo(): String = try {
        val am = app?.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val rt = Runtime.getRuntime()
        val heap = (rt.totalMemory() - rt.freeMemory()) / 1_048_576
        "wolne ${mi.availMem / 1_048_576}/${mi.totalMem / 1_048_576} MB" + (if (mi.lowMemory) " (MAŁO!)" else "") +
            ", CAR OS $heap/${rt.maxMemory() / 1_048_576} MB"
    } catch (_: Exception) {
        "?"
    }

    fun onTrim(level: Int) {
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) log("System: mało pamięci (poziom $level) · ${memInfo()}")
    }

    // ---------- raport ----------

    fun report(): String = buildString {
        appendLine("CAR OS ${BuildConfig.VERSION_NAME} – dziennik błędów (${time()})")
        appendLine("Urządzenie: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.HARDWARE}), Android ${Build.VERSION.RELEASE}")
        appendLine("Pamięć: ${memInfo()}")
        appendLine("Sieć teraz: $netDesc")
        appendLine("Mapa: $mapState")
        val (c, f) = counts.value
        appendLine("Awarie w dzienniku: $c, zacięcia: $f")
        appendLine()
        appendLine("== AWARIE (najnowsze na dole) ==")
        appendLine(tail(CRASHES, 30_000).ifBlank { "brak" })
        appendLine()
        appendLine("== ZACIĘCIA (najnowsze na dole) ==")
        appendLine(tail(FREEZES, 30_000).ifBlank { "brak" })
        appendLine()
        appendLine("== DZIENNIK ==")
        appendLine(tail(LOG, 40_000))
    }

    /** Wyślij dziennik do CAR OS Connect. false = telefon nie sparowany. */
    fun sendToPhone(): Boolean = ConnectRepository.publishReport(report())

    /** Po awarii / zacięciu: sam do telefonu (gdy jest internet), najwyżej co 15 min. */
    fun autoSend(why: String) {
        scope.launch {
            val sp = Prefs.store ?: return@launch
            val now = System.currentTimeMillis()
            if (now - sp.getLong("diag_sent", 0L) < 15 * 60_000L) return@launch
            delay(20_000)
            withTimeoutOrNull(10 * 60_000L) { online.first { it == true } } ?: return@launch
            delay(5_000)
            if (sendToPhone()) {
                sp.edit().putLong("diag_sent", System.currentTimeMillis()).apply()
                log("Dziennik błędów wysłany do telefonu ($why)")
            }
        }
    }

    /** Awaria z poprzedniego uruchomienia (zapisana przez [CrashLog]) – do dziennika i telefonu. */
    fun onPreviousCrash() {
        refreshCounts()
        log("Poprzednie uruchomienie zakończyło się awarią")
        autoSend("awaria")
    }
}
