package pl.mati.carlauncher.data

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.service.notification.StatusBarNotification
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Informacje o fabrycznym radiu / muzyce BT, które nie mają sesji multimediów.
 * Klucze: "radio", "bt" (z nasłuchu komunikatów) albo nazwa pakietu (z powiadomień).
 */
object SourceInfoRepository {

    data class Info(val key: String, val title: String, val text: String, val sub: String, val icon: Bitmap?, val time: Long)

    val byKey = MutableStateFlow<Map<String, Info>>(emptyMap())

    /** Pakiety, których powiadomienia obserwujemy. */
    @Volatile
    var watched: Set<String> = emptySet()

    fun onPosted(ctx: Context, sbn: StatusBarNotification) {
        if (sbn.packageName !in watched) return
        val n = sbn.notification ?: return
        val ex = n.extras ?: return
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty().trim()
        val sub = ex.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty().trim()
        if (title.isBlank() && text.isBlank()) return
        val icon = try { n.getLargeIcon()?.loadDrawable(ctx)?.toBitmap() } catch (_: Exception) { null }
        put(Info(sbn.packageName, title, text, sub, icon, System.currentTimeMillis()))
    }

    fun onRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName in byKey.value) byKey.value = byKey.value - sbn.packageName
    }

    /** Aktualizacja z nasłuchu komunikatów (puste pola nie nadpisują znanych). */
    fun update(key: String, title: String?, text: String?, sub: String?) {
        val old = byKey.value[key]
        put(
            Info(
                key,
                title?.takeIf { it.isNotBlank() } ?: old?.title.orEmpty(),
                text?.takeIf { it.isNotBlank() } ?: old?.text.orEmpty(),
                sub?.takeIf { it.isNotBlank() } ?: old?.sub.orEmpty(),
                old?.icon,
                System.currentTimeMillis(),
            ),
        )
    }

    private fun put(i: Info) {
        byKey.value = byKey.value + (i.key to i)
    }
}
