package pl.mati.carlauncher.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import pl.mati.carlauncher.BuildConfig

/**
 * Lista zmian (assets/changelog.txt – dołączona do CAR OS, działa bez internetu)
 * i okno „Nowość w wersji” pokazywane raz po aktualizacji.
 */
object Changelog {

    data class Entry(val version: String, val date: String?, val title: String?, val points: List<String>)

    val entries = MutableStateFlow<List<Entry>>(emptyList())

    /** Wpis do pokazania po aktualizacji (null = nic). */
    val whatsNew = MutableStateFlow<Entry?>(null)

    private var loaded = false

    fun init(ctx: Context) {
        if (loaded) return
        loaded = true
        val text = try { ctx.assets.open("changelog.txt").bufferedReader().use { it.readText() } } catch (_: Exception) { "" }
        val list = parse(text).filter { it.version != "next" }
        entries.value = list
        val sp = Prefs.store ?: return
        val seen = sp.getInt("cl_seen_code", 0)
        if (seen == 0 && !Prefs.profile.value.setupDone) {
            // nowa instalacja – bez okna
            sp.edit().putInt("cl_seen_code", BuildConfig.VERSION_CODE).apply()
        } else if (BuildConfig.VERSION_CODE > seen) {
            // aktualizacja (także z wersji sprzed listy zmian)
            whatsNew.value = list.firstOrNull { it.version == BuildConfig.VERSION_NAME } ?: list.firstOrNull()
            if (whatsNew.value == null) markSeen()
        }
    }

    fun markSeen() {
        whatsNew.value = null
        Prefs.store?.edit()?.putInt("cl_seen_code", BuildConfig.VERSION_CODE)?.apply()
    }

    /** „## 1.57 · 2026-11-01”, potem tytuł i punkty „- …”. Linie z # (komentarze) pomijane. */
    fun parse(text: String): List<Entry> {
        val out = ArrayList<Entry>()
        var ver: String? = null
        var date: String? = null
        var title: String? = null
        val points = ArrayList<String>()
        fun flush() {
            val v = ver ?: return
            out += Entry(v, date, title, points.toList())
        }
        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("## ") -> {
                    flush()
                    val h = line.removePrefix("## ").split("·").map { it.trim() }
                    ver = h[0]
                    date = h.getOrNull(1)?.ifBlank { null }
                    title = null
                    points.clear()
                }
                line.isBlank() || line.startsWith("#") -> Unit
                ver == null -> Unit
                line.startsWith("- ") || line.startsWith("• ") -> points += line.drop(2).trim()
                title == null -> title = line
                else -> points += line
            }
        }
        flush()
        return out
    }

    /** Opis wydania z GitHub (tytuł + punkty) bez stopek commitów. */
    fun fromBody(version: String, body: String): Entry? {
        val lines = body.lines().map { it.trim() }.filter {
            it.isNotBlank() && !it.startsWith("Co-Authored-By") && !it.startsWith("Claude-Session") &&
                !it.startsWith("🤖") && !it.startsWith("https://claude.ai")
        }
        if (lines.isEmpty()) return null
        return parse("## $version\n" + lines.joinToString("\n")).firstOrNull()
    }
}
