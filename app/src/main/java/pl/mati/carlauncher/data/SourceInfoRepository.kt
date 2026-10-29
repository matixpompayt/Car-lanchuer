package pl.mati.carlauncher.data

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.service.notification.StatusBarNotification
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Informacje z powiadomień fabrycznych aplikacji radia (tuner / muzyka BT),
 * które nie udostępniają sesji multimediów. Zbiera też surowe powiadomienia do diagnostyki.
 */
object SourceInfoRepository {

    data class Info(val pkg: String, val title: String, val text: String, val sub: String, val icon: Bitmap?, val time: Long)

    /** Ostatnie powiadomienie każdej obserwowanej aplikacji. */
    val byPkg = MutableStateFlow<Map<String, Info>>(emptyMap())

    /** Pakiety do obserwowania (ustawiane przez UI po wykryciu aplikacji radia/BT). */
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
        byPkg.value = byPkg.value + (sbn.packageName to Info(sbn.packageName, title, text, sub, icon, System.currentTimeMillis()))
    }

    fun onRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName in byPkg.value) byPkg.value = byPkg.value - sbn.packageName
    }
}
