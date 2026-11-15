package pl.mati.carlauncher.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import pl.mati.carlauncher.data.Prefs
import pl.mati.carlauncher.data.SteeringKeys

/**
 * Przyciski na kierownicy nad każdą aplikacją: usługa dostaje klawisze, zanim trafią do aplikacji.
 * Obsługuje tylko klawisze multimediów i nauczone przyciski – resztę (głośność, wstecz…) puszcza dalej.
 * Nie czyta treści ekranu ani tego, co wpisujesz.
 */
class WheelKeysService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        Prefs.init(applicationContext)
        SteeringKeys.init(applicationContext)
        SteeringKeys.serviceOn.value = true
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        val e = event ?: return false
        return try { SteeringKeys.onKey(e, "system") } catch (_: Exception) { false }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        SteeringKeys.serviceOn.value = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        SteeringKeys.serviceOn.value = false
        super.onDestroy()
    }
}
