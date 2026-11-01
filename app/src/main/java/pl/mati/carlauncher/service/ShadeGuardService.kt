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
        camSam(e)
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

    // ---------- okienko ostrzeżeń CamSam ----------
    private var camVisible = false
    private var lastRead = 0L

    private fun camSam(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString()
        val fromCam = pkg != null && pkg.startsWith("com.camsam")
        if (e.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED || fromCam) {
            val now = android.os.SystemClock.uptimeMillis()
            if (!fromCam && now - lastRead < 300) return
            lastRead = now
            val win = try {
                windows.firstOrNull { w -> w.root?.packageName?.toString()?.startsWith("com.camsam") == true }
            } catch (_: Exception) {
                null
            }
            val root = win?.root ?: (if (fromCam) e.source else null)
            if (root == null) {
                if (camVisible) {
                    camVisible = false
                    pl.mati.carlauncher.data.AlertRepository.onOverlayGone()
                }
                return
            }
            camVisible = true
            val out = ArrayList<String>()
            collect(root, out, 0)
            pl.mati.carlauncher.data.AlertRepository.onOverlay(root.packageName?.toString() ?: pkg ?: "com.camsam", out)
        }
    }

    private fun collect(n: android.view.accessibility.AccessibilityNodeInfo?, out: MutableList<String>, depth: Int) {
        if (n == null || depth > 12 || out.size > 30) return
        n.text?.toString()?.let { out += it }
        n.contentDescription?.toString()?.let { out += it }
        for (i in 0 until n.childCount) collect(n.getChild(i), out, depth + 1)
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
