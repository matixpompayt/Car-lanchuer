package pl.mati.carlauncher.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import pl.mati.carlauncher.data.MediaRepository
import pl.mati.carlauncher.data.NavRepository

/** Daje dostęp do sesji multimediów i powiadomień nawigacji. */
class CarNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        MediaRepository.attach(this)
        try {
            activeNotifications?.forEach { NavRepository.onPosted(this, it) }
        } catch (_: Exception) {
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        NavRepository.onPosted(this, sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        NavRepository.onRemoved(sbn)
    }
}
