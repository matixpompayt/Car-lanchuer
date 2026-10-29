package pl.mati.carlauncher.data

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import pl.mati.carlauncher.BuildConfig
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Aktualizacje online z GitHub Releases (repo z BuildConfig.UPDATE_REPO).
 * Tag wydania: v1.0.<numer builda>; numer builda = versionCode.
 */
object UpdateManager {

    data class Release(
        val code: Int,
        val name: String,
        val notes: String,
        val apkUrl: String,
        val sizeBytes: Long,
    )

    sealed class State {
        object Idle : State()
        object Checking : State()
        object UpToDate : State()
        data class Available(val r: Release) : State()
        data class Downloading(val r: Release, val progress: Float) : State()
        data class Ready(val r: Release, val file: File) : State()
        data class Error(val message: String) : State()
    }

    val state = MutableStateFlow<State>(State.Idle)
    val lastChecked = MutableStateFlow(0L)

    val currentVersion: String get() = BuildConfig.VERSION_NAME
    private val currentCode: Int get() = BuildConfig.VERSION_CODE

    private var lastRelease: Release? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun check(ctx: Context, silent: Boolean = false) {
        if (state.value is State.Downloading) return
        job?.cancel()
        job = scope.launch {
            if (!silent) state.value = State.Checking
            try {
                val r = fetchLatest()
                lastChecked.value = System.currentTimeMillis()
                if (r != null && r.code > currentCode) {
                    lastRelease = r
                    state.value = State.Available(r)
                    NotificationRepository.postSystem(
                        "update", "Dostępna aktualizacja", "CAR OS ${r.name} – dotknij, aby zainstalować", "update",
                    )
                } else {
                    state.value = State.UpToDate
                    NotificationRepository.removeSystem("update")
                }
            } catch (e: Exception) {
                state.value = if (silent) State.Idle else State.Error(e.message ?: "Brak połączenia")
            }
        }
    }

    fun download(ctx: Context) {
        val r = lastRelease ?: return
        if (Prefs.updateWifiOnly.value && !onWifi(ctx)) {
            state.value = State.Error("Pobieranie tylko przez Wi-Fi – połącz się z Wi-Fi albo wyłącz tę opcję")
            return
        }
        job?.cancel()
        job = scope.launch {
            val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
            val out = File(dir, "update.apk")
            try {
                state.value = State.Downloading(r, 0f)
                val c = (URL(r.apkUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    setRequestProperty("Accept", "application/octet-stream")
                }
                try {
                    if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
                    val total = if (c.contentLengthLong > 0) c.contentLengthLong else r.sizeBytes
                    c.inputStream.use { input ->
                        out.outputStream().use { output ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            var lastEmit = 0L
                            while (isActive) {
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                done += n
                                val now = System.currentTimeMillis()
                                if (now - lastEmit > 150 && total > 0) {
                                    lastEmit = now
                                    state.value = State.Downloading(r, (done.toFloat() / total).coerceIn(0f, 1f))
                                }
                            }
                        }
                    }
                } finally {
                    c.disconnect()
                }
                if (!isActive) return@launch
                state.value = State.Ready(r, out)
                NotificationRepository.postSystem("update", "Aktualizacja pobrana", "CAR OS ${r.name} gotowa do instalacji", "update")
            } catch (e: Exception) {
                out.delete()
                state.value = State.Error("Pobieranie nie powiodło się: ${e.message}")
            }
        }
    }

    fun cancel() {
        job?.cancel()
        val s = state.value
        if (s is State.Downloading) state.value = State.Available(s.r)
    }

    /** Uruchamia systemowy instalator. Na Androidzie 10 trzeba raz zezwolić na instalację z tej aplikacji. */
    fun install(ctx: Context): Boolean {
        val s = state.value as? State.Ready ?: return false
        BackupManager.autoBackup(ctx, force = true)
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { ctx.startActivity(i) } catch (_: Exception) {}
            return false
        }
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", s.file)
        @Suppress("DEPRECATION")
        val i = Intent(Intent.ACTION_INSTALL_PACKAGE)
            .setData(uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
        return try {
            ctx.startActivity(i)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun fetchLatest(): Release? {
        val url = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "CarOS")
        try {
            if (c.responseCode == 404) return null
            if (c.responseCode !in 200..299) throw IOException("Serwer aktualizacji: HTTP ${c.responseCode}")
            val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val tag = json.optString("tag_name")
            val code = Regex("""(\d+)$""").find(tag)?.value?.toIntOrNull() ?: return null
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.optString("name")
                if (name.endsWith(".apk")) {
                    return Release(
                        code = code,
                        name = tag.removePrefix("v"),
                        notes = json.optString("body").trim(),
                        apkUrl = a.optString("browser_download_url"),
                        sizeBytes = a.optLong("size"),
                    )
                }
            }
            return null
        } finally {
            c.disconnect()
        }
    }

    private fun onWifi(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
}
