package pl.mati.carlauncher.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import pl.mati.carlauncher.data.MediaRepository
import pl.mati.carlauncher.data.NavRepository
import pl.mati.carlauncher.data.NotificationRepository

/** Daje dostęp do sesji multimediów, powiadomień nawigacji i listy powiadomień. */
class CarNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        NotificationRepository.attach(this)
        MediaRepository.attach(this)
        try {
            activeNotifications?.forEach {
                NavRepository.onPosted(this, it)
                NotificationRepository.onPosted(this, it)
            }
        } catch (_: Exception) {
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        NavRepository.onPosted(this, sbn)
        NotificationRepository.onPosted(this, sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        NavRepository.onRemoved(sbn)
        NotificationRepository.onRemoved(sbn)
    }
}
