package pl.mati.carosparking

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Aktualizacje z GitHub Releases (plik CarOS-Parking-*.apk). */
object Updater {

    sealed class State {
        object Idle : State()
        object Checking : State()
        object UpToDate : State()
        data class Available(val name: String, val url: String) : State()
        data class Downloading(val progress: Float) : State()
        data class Error(val msg: String) : State()
    }

    val state = MutableStateFlow<State>(State.Idle)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun check(silent: Boolean) {
        val s = state.value
        if (s is State.Checking || s is State.Downloading) return
        scope.launch {
            if (!silent) state.value = State.Checking
            try {
                val c = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest").openConnection() as HttpURLConnection
                c.connectTimeout = 10_000
                c.readTimeout = 15_000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.setRequestProperty("User-Agent", "CarOS-Parking")
                val json = try {
                    if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
                    JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                } finally {
                    c.disconnect()
                }
                val tag = json.optString("tag_name")
                val code = Regex("""(\d+)$""").find(tag)?.value?.toIntOrNull() ?: 0
                val assets = json.optJSONArray("assets")
                var url: String? = null
                if (assets != null) for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").startsWith("CarOS-Parking") && a.optString("name").endsWith(".apk")) url = a.optString("browser_download_url")
                }
                state.value = if (url != null && code > BuildConfig.VERSION_CODE) State.Available(tag.removePrefix("v"), url) else State.UpToDate
            } catch (e: Exception) {
                state.value = if (silent) State.Idle else State.Error(e.message ?: "Brak połączenia")
            }
        }
    }

    fun downloadAndInstall(ctx: Context) {
        val s = state.value as? State.Available ?: return
        val app = ctx.applicationContext
        if (!app.packageManager.canRequestPackageInstalls()) {
            try {
                app.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + app.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (_: Exception) {}
            return
        }
        scope.launch {
            val dir = File(app.cacheDir, "updates").apply { mkdirs() }
            val out = File(dir, "update.apk")
            try {
                state.value = State.Downloading(0f)
                val c = URL(s.url).openConnection() as HttpURLConnection
                c.instanceFollowRedirects = true
                c.connectTimeout = 15_000
                c.readTimeout = 30_000
                try {
                    if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
                    val total = c.contentLengthLong
                    c.inputStream.use { input ->
                        out.outputStream().use { output ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                done += n
                                if (total > 0) state.value = State.Downloading((done.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                } finally {
                    c.disconnect()
                }
                state.value = s
                val uri = FileProvider.getUriForFile(app, app.packageName + ".files", out)
                @Suppress("DEPRECATION")
                app.startActivity(
                    Intent(Intent.ACTION_INSTALL_PACKAGE).setData(uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (e: Exception) {
                out.delete()
                state.value = State.Error("Pobieranie nie powiodło się: ${e.message}")
            }
        }
    }
}
