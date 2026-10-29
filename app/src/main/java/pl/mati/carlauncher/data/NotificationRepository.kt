package pl.mati.carlauncher.data

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import java.lang.ref.WeakReference

/** Powiadomienia innych aplikacji do centrum sterowania + własne komunikaty systemu. */
object NotificationRepository {

    data class Item(
        val key: String,
        val pkg: String,
        val app: String,
        val title: String,
        val text: String,
        val time: Long,
        val intent: PendingIntent? = null,
        val action: String? = null, // własne akcje: "update", "obd"
    )

    val items = MutableStateFlow<List<Item>>(emptyList())

    private var service: WeakReference<NotificationListenerService>? = null
    private val SKIP = setOf("android", "com.android.systemui")

    fun attach(s: NotificationListenerService) {
        service = WeakReference(s)
    }

    fun onPosted(ctx: Context, sbn: StatusBarNotification) {
        if (sbn.packageName == ctx.packageName || sbn.packageName in SKIP) return
        val n = sbn.notification ?: return
        // pomijamy ciągłe (odtwarzacz, nawigacja) – mają własne miejsca w UI
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val ex = n.extras ?: return
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val app = try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (_: Exception) {
            sbn.packageName
        }
        val item = Item(sbn.key, sbn.packageName, app, title, text, sbn.postTime, n.contentIntent)
        items.value = (listOf(item) + items.value.filter { it.key != sbn.key }).take(20)
    }

    fun onRemoved(sbn: StatusBarNotification) {
        items.value = items.value.filter { it.key != sbn.key }
    }

    fun postSystem(key: String, title: String, text: String, action: String?) {
        val item = Item("caros:$key", "caros", "CAR OS", title, text, System.currentTimeMillis(), null, action)
        items.value = (listOf(item) + items.value.filter { it.key != item.key }).take(20)
    }

    fun removeSystem(key: String) {
        items.value = items.value.filter { it.key != "caros:$key" }
    }

    /** Ponowne przejrzenie aktywnych powiadomień (np. po zmianie obserwowanych aplikacji). */
    fun rescan(ctx: Context) {
        try {
            service?.get()?.activeNotifications?.forEach { SourceInfoRepository.onPosted(ctx, it) }
        } catch (_: Exception) {
        }
    }

    fun clearAll() {
        try { service?.get()?.cancelAllNotifications() } catch (_: Exception) {}
        items.value = emptyList()
    }

    fun open(item: Item) {
        try { item.intent?.send() } catch (_: Exception) {}
    }
}
