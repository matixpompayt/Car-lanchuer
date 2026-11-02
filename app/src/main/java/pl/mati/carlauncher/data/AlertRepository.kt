package pl.mati.carlauncher.data

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Ostrzeżenia drogowe z CamSam / CamSam PLUS (com.camsam, com.camsam.plus) i Yanosika.
 * Te aplikacje nie mają publicznego API, więc czytamy:
 *  1) ich powiadomienia – wszystkie pola tekstowe, także własny układ (RemoteViews),
 *  2) okienko ostrzeżeń CamSam (tryb widżetu) przez usługę ułatwień dostępu CAR OS.
 */
object AlertRepository {

    data class Alert(val pkg: String, val app: String, val title: String, val text: String, val time: Long, val source: String)
    data class Raw(val app: String, val source: String, val title: String, val text: String, val ongoing: Boolean, val time: Long)

    val current = MutableStateFlow<Alert?>(null)
    /** Ostatnie surowe teksty – podgląd w Ustawieniach (diagnostyka). */
    val raw = MutableStateFlow<List<Raw>>(emptyList())
    /** Kiedy ostatnio coś przyszło z CamSam (dowolnym kanałem). */
    val lastSeen = MutableStateFlow(0L)

    fun appName(pkg: String): String? = when {
        pkg.startsWith("com.camsam") -> if (pkg.contains("plus")) "CamSam PLUS" else "CamSam"
        pkg.startsWith("pl.neptis.yanosik") -> "Yanosik"
        else -> null
    }

    // Tylko słowa oznaczające realne ostrzeżenie. Sama odległość / prędkość („0 km/h”) to NIE ostrzeżenie.
    private val ALERT = Regex(
        "(fotoradar|radar|kamer|kontrol|policj|patrol|odcinkow|pomiar|suszark|wypadek|korek|zator|roboty drogow|przeszkod|zagroż|" +
            "czerwon|niebezpiecz|speed ?cam|speedcam|camera|blitzer|messung|kontrolle|polizei|ampel|police|accident|hazard|" +
            "section control|red light|radarfalle|stau|unfall|odstęp|odstep|koniec korku|gołoled|gololed|widoczno|kombinowan|światło|swiatlo|glatteis|baustelle|sicht|abstand)",
        RegexOption.IGNORE_CASE,
    )
    private val IDLE = Regex(
        "(działa w tle|aktywn|uruchomion|running|is active|ist aktiv|läuft|tap to|dotknij, aby|szukam gps|searching|kein gps|no gps|brak gps)",
        RegexOption.IGNORE_CASE,
    )

    private fun keep(app: String, source: String, title: String, text: String, ongoing: Boolean) {
        lastSeen.value = System.currentTimeMillis()
        raw.value = (listOf(Raw(app, source, title, text, ongoing, System.currentTimeMillis())) + raw.value)
            .distinctBy { it.source + it.title + it.text }.take(10)
    }

    // ---------- 1) powiadomienia ----------

    fun onPosted(ctx: Context, sbn: StatusBarNotification) {
        val app = appName(sbn.packageName) ?: return
        val n = sbn.notification ?: return
        val ex = n.extras
        val parts = LinkedHashSet<String>()
        fun add(cs: CharSequence?) { cs?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { parts += it } }
        val title = ex?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        if (ex != null) {
            add(ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))
            add(ex.getCharSequence(Notification.EXTRA_SUB_TEXT))
            add(ex.getCharSequence(Notification.EXTRA_INFO_TEXT))
            add(ex.getCharSequence(Notification.EXTRA_SUMMARY_TEXT))
            ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { add(it) }
        }
        add(n.tickerText)
        // własny układ powiadomienia (często tam jest odległość do fotoradaru)
        @Suppress("DEPRECATION")
        for (rv in listOf(n.contentView, n.bigContentView, n.headsUpContentView)) {
            if (rv == null) continue
            try {
                val v = rv.apply(ctx, FrameLayout(ctx))
                texts(v, parts)
            } catch (_: Throwable) {
            }
        }
        parts.remove(title)
        val text = parts.joinToString(" · ")
        val ongoing = n.flags and Notification.FLAG_ONGOING_EVENT != 0
        if (title.isBlank() && text.isBlank()) return
        keep(app, "powiadomienie", title, text, ongoing)
        if (!Prefs.roadAlerts.value) return
        evaluate(sbn.packageName, app, title, text, "n")
    }

    private fun texts(v: View, out: MutableSet<String>) {
        if (out.size > 20) return
        if (v is TextView) v.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { out += it }
        if (v is ViewGroup) for (i in 0 until v.childCount) texts(v.getChildAt(i), out)
    }

    fun onRemoved(sbn: StatusBarNotification) {
        val c = current.value ?: return
        if (c.pkg == sbn.packageName && c.source == "n") current.value = null
    }

    // ---------- 2) okienko CamSam (ułatwienia dostępu) ----------

    private var lastA11y = 0L

    /** Teksty z okienka CamSam na ekranie – NIE używane (ostrzeżenia tylko z powiadomień). */
    @Suppress("unused")
    fun onOverlay(pkg: String, texts: List<String>) {
        return
    }

    @Suppress("unused")
    private fun onOverlayOld(pkg: String, texts: List<String>) {
        val app = appName(pkg) ?: return
        val now = System.currentTimeMillis()
        if (now - lastA11y < 400) return
        lastA11y = now
        val clean = texts.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(12)
        if (clean.isEmpty()) return
        val title = clean.firstOrNull { ALERT.containsMatchIn(it) && !it.matches(Regex("\\d+.*")) } ?: clean.first()
        val text = clean.filter { it != title }.joinToString(" · ")
        keep(app, "okienko", title, text, false)
        if (!Prefs.roadAlerts.value) return
        evaluate(pkg, app, title, text, "a")
    }

    /** Okienko CamSam zniknęło z ekranu. */
    fun onOverlayGone() {
        val c = current.value ?: return
        if (c.source == "a") current.value = null
    }

    // ---------- wspólne ----------

    private fun evaluate(pkg: String, app: String, title: String, text: String, source: String) {
        val all = "$title $text"
        val isAlert = ALERT.containsMatchIn(all) && !IDLE.containsMatchIn(all)
        if (isAlert) {
            current.value = Alert(pkg, app, title.ifBlank { app }, text, System.currentTimeMillis(), source)
        } else if (current.value?.pkg == pkg && current.value?.source == source) {
            current.value = null // to samo źródło wróciło do „spokojnie”
        }
    }

    /** Próbne ostrzeżenie – sprawdzenie, jak wygląda baner. */
    fun test() {
        current.value = Alert("com.camsam.plus", "CamSam PLUS", "Fotoradar", "350 m · ograniczenie 50", System.currentTimeMillis(), "t")
    }

    fun dismiss() {
        current.value = null
    }

    fun installed(ctx: Context): String? = listOf("com.camsam.plus", "com.camsam").firstOrNull {
        try { ctx.packageManager.getPackageInfo(it, 0); true } catch (_: Exception) { false }
    }
}
