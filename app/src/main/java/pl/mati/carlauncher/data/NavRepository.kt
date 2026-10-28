package pl.mati.carlauncher.data

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.service.notification.StatusBarNotification
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Manewry z powiadomienia nawigacji (Mapy Google / Waze).
 * Mapy Google: tytuł = dystans ("350 m"), tekst = ulica/instrukcja, subText = czas przyjazdu,
 * duża ikona = strzałka manewru.
 */
object NavRepository {

    data class NavState(
        val primary: String,
        val secondary: String?,
        val extra: String?,
        val icon: Bitmap?,
        val pkg: String,
        val actions: List<Pair<String, PendingIntent>> = emptyList(),
    )

    val state = MutableStateFlow<NavState?>(null)

    private val NAV_PACKAGES = setOf(
        "com.google.android.apps.maps",
        "com.waze",
        "com.here.app.maps",
    )

    private var currentKey: String? = null

    fun onPosted(context: Context, sbn: StatusBarNotification) {
        if (sbn.packageName !in NAV_PACKAGES) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_ONGOING_EVENT == 0) return
        val ex = n.extras ?: return
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() }
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }
        val sub = ex.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.takeIf { it.isNotBlank() }
        val primary = title ?: text ?: return
        currentKey = sbn.key
        state.value = NavState(
            primary = primary,
            secondary = if (title != null) text else null,
            extra = sub,
            icon = largeIcon(context, n),
            pkg = sbn.packageName,
            actions = n.actions.orEmpty().mapNotNull { a ->
                val t = a.title?.toString() ?: return@mapNotNull null
                val pi = a.actionIntent ?: return@mapNotNull null
                t to pi
            },
        )
    }

    private val EXIT = Regex("(zakończ|zakoncz|zamknij|wyjdź|wyjdz|zatrzymaj|exit|end|stop|close|quit|beenden)", RegexOption.IGNORE_CASE)

    /** Kończy nawigację w Mapach Google / Waze przyciskiem z ich powiadomienia. true = udało się. */
    fun stopNavigation(): Boolean {
        val a = state.value?.actions?.firstOrNull { EXIT.containsMatchIn(it.first) } ?: return false
        return try {
            a.second.send()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun onRemoved(sbn: StatusBarNotification) {
        if (sbn.key == currentKey) {
            currentKey = null
            state.value = null
        }
    }

    private fun largeIcon(context: Context, n: Notification): Bitmap? = try {
        n.getLargeIcon()?.loadDrawable(context)?.toBitmap()
    } catch (_: Exception) {
        null
    }
}
