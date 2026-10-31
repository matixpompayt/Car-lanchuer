package pl.mati.carlauncher.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import pl.mati.carlauncher.data.Prefs
import pl.mati.carlauncher.data.ShadeGuard

/**
 * Usługa ułatwień dostępu – jedyny sposób na Androidzie 12+, żeby aplikacja mogła
 * zamknąć systemową zasłonę powiadomień (GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE).
 * Reaguje tylko gdy CAR OS jest na wierzchu, a jego okno straciło fokus (= coś systemowego wyjechało nad nim).
 */
class ShadeGuardService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        Prefs.init(applicationContext)
        ShadeGuard.service = this
        ShadeGuard.connected.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = e.packageName?.toString() ?: return
                if (!pkg.contains("systemui")) return
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> Unit
            else -> return
        }
        if (ShadeGuard.shouldBlock()) ShadeGuard.dismiss(this)
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        clear()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        clear()
        super.onDestroy()
    }

    private fun clear() {
        if (ShadeGuard.service === this) ShadeGuard.service = null
        ShadeGuard.connected.value = false
    }
}
