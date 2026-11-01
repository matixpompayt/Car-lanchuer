package pl.mati.carlauncher.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import pl.mati.carlauncher.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Gdy CAR OS się wysypie: opis błędu zapisany w pamięci aplikacji, a przy następnym starcie
 * skopiowany do Pobrane/CarOS/CarOS-blad.txt (do wysłania i naprawy).
 */
object CrashLog {
    private const val FILE = "last_crash.txt"

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        if (prev is Handler) return
        Thread.setDefaultUncaughtExceptionHandler(Handler(app, prev))
    }

    private class Handler(val app: Context, val prev: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
                val text = "CAR OS ${BuildConfig.VERSION_NAME} – błąd $time\n" +
                    "Wątek: ${t.name}\nUrządzenie: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.HARDWARE}), Android ${Build.VERSION.RELEASE}\n\n$sw"
                File(app.filesDir, FILE).writeText(text)
            } catch (_: Throwable) {
            }
            prev?.uncaughtException(t, e)
        }
    }

    /** Przy starcie: był błąd → kopia do Pobranych i komunikat. */
    fun reportPrevious(ctx: Context) {
        val f = File(ctx.filesDir, FILE)
        if (!f.exists()) return
        val text = try { f.readText() } catch (_: Exception) { return }
        f.delete()
        val path = saveText(ctx, "CarOS-blad.txt", text)
        pl.mati.carlauncher.ui.Toaster.show(
            "CAR OS wcześniej się wyłączył – opis błędu zapisany" + (path?.let { ": $it" } ?: ""),
        )
    }

    /** Zapis tekstu do Pobrane/CarOS (nadpisuje plik o tej nazwie). Zwraca ścieżkę albo null. */
    fun saveText(ctx: Context, name: String, text: String): String? = try {
        val dir = "Download/CarOS/"
        val resolver = ctx.contentResolver
        val coll = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        var uri: Uri? = null
        resolver.query(
            coll, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(name, dir), null,
        )?.use { c -> if (c.moveToFirst()) uri = Uri.withAppendedPath(coll, c.getLong(0).toString()) }
        if (uri == null) {
            uri = resolver.insert(coll, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
            })
        }
        val target = uri ?: throw IllegalStateException()
        resolver.openOutputStream(target, "wt")?.use { it.write(text.toByteArray()) }
        "Pobrane/CarOS/$name"
    } catch (_: Exception) {
        null
    }
}
