package pl.mati.carlauncher.data

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import pl.mati.carlauncher.service.ShadeGuardService

/** Blokada systemowej zasłony powiadomień nad CAR OS. */
object ShadeGuard {
    @Volatile var service: AccessibilityService? = null
    val connected = MutableStateFlow(false)

    @Volatile var launcherResumed = false
    @Volatile var launcherFocused = true

    /** Android 12+ nie pozwala zwykłej aplikacji zamknąć zasłony – potrzebna usługa ułatwień dostępu. */
    val needsService: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    fun shouldBlock() = Prefs.blockStatusBar.value && launcherResumed && !launcherFocused

    fun dismiss(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        } else {
            try {
                @Suppress("DEPRECATION")
                ctx.sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
            } catch (_: Exception) {
            }
        }
    }

    /** Czy usługa jest zaznaczona w ustawieniach (nawet jeśli jeszcze się nie podłączyła). */
    fun enabledInSettings(ctx: Context): Boolean {
        val me = ComponentName(ctx, ShadeGuardService::class.java).flattenToString()
        val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return list.split(':').any { it.equals(me, ignoreCase = true) }
    }

    fun openAccessibilitySettings(ctx: Context) {
        val i = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(i) } catch (_: Exception) {}
    }

    fun openAppInfo(ctx: Context) {
        val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + ctx.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(i) } catch (_: Exception) {}
    }
}
