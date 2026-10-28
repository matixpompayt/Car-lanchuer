package pl.mati.carlauncher.data

import android.app.Notification
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Ostrzeżenia drogowe z aplikacji typu CamSam / Yanosik – czytane z ich powiadomień
 * (te aplikacje nie mają publicznego API).
 */
object AlertRepository {

    data class Alert(val pkg: String, val app: String, val title: String, val text: String, val time: Long)
    data class Raw(val app: String, val title: String, val text: String, val ongoing: Boolean, val time: Long)

    val current = MutableStateFlow<Alert?>(null)
    /** Ostatnie surowe powiadomienia – podgląd w Ustawieniach do dopasowania formatu. */
    val raw = MutableStateFlow<List<Raw>>(emptyList())

    private fun appName(pkg: String): String? = when {
        pkg.startsWith("com.camsam") -> "CamSam"
        pkg.startsWith("pl.neptis.yanosik") -> "Yanosik"
        else -> null
    }

    // Słowa, które oznaczają realne ostrzeżenie (a nie „aplikacja działa w tle”).
    private val ALERT = Regex(
        "(fotoradar|radar|kamer|kontrol|policj|patrol|odcink|pomiar|wypadek|korek|zator|robot|przeszkod|zagroż|uwaga|" +
            "speed|camera|blitzer|police|accident|hazard|\\d+\\s?m\\b|\\d+[.,]?\\d*\\s?km\\b)",
        RegexOption.IGNORE_CASE,
    )
    private val IDLE = Regex("(działa w tle|aktywn|uruchomion|running|is active|tap to|dotknij, aby)", RegexOption.IGNORE_CASE)

    fun onPosted(sbn: StatusBarNotification) {
        val app = appName(sbn.packageName) ?: return
        if (!Prefs.roadAlerts.value) return
        val n = sbn.notification ?: return
        val ex = n.extras ?: return
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty().trim()
        val ongoing = n.flags and Notification.FLAG_ONGOING_EVENT != 0
        if (title.isBlank() && text.isBlank()) return

        raw.value = (listOf(Raw(app, title, text, ongoing, sbn.postTime)) + raw.value).distinctBy { it.title + it.text }.take(8)

        val all = "$title $text"
        val isAlert = ALERT.containsMatchIn(all) && !IDLE.containsMatchIn(all)
        if (isAlert) {
            current.value = Alert(sbn.packageName, app, title.ifBlank { app }, text, System.currentTimeMillis())
        } else if (current.value?.pkg == sbn.packageName) {
            // to samo powiadomienie wróciło do stanu „spokojnie” → chowamy ostrzeżenie
            current.value = null
        }
    }

    fun onRemoved(sbn: StatusBarNotification) {
        if (current.value?.pkg == sbn.packageName) current.value = null
    }

    fun dismiss() {
        current.value = null
    }
}
