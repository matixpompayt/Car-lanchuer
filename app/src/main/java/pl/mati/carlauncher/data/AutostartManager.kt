package pl.mati.carlauncher.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * „Zapłon”: start launchera albo włączenie ekranu po ≥3 min przerwy.
 * Wznawia muzykę, łączy OBD, proponuje trasę do domu, przypomina o serwisie.
 */
object AutostartManager {

    val homeSuggestion = MutableStateFlow(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lastOff = 0L
    private var ranAt = 0L

    val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> lastOff = System.currentTimeMillis()
                Intent.ACTION_SCREEN_ON -> if (lastOff > 0 && System.currentTimeMillis() - lastOff > 3 * 60_000) run(ctx)
            }
        }
    }

    fun run(ctx: Context) {
        val now = System.currentTimeMillis()
        if (now - ranAt < 60_000) return
        ranAt = now
        if (!Prefs.profile.value.setupDone) return

        Prefs.obdMac.value?.let { mac ->
            val s = ObdManager.status.value
            if (s == ObdManager.Status.OFF || s == ObdManager.Status.ERROR) ObdManager.start(mac)
        }

        if (Prefs.autoResumeMusic.value) {
            scope.launch {
                delay(6000) // czas na połączenie BT z telefonem / start aplikacji muzycznej
                if (!MediaRepository.state.value.playing) MediaRepository.play()
            }
        }

        if (Prefs.suggestHome.value && Prefs.profile.value.home.isNotBlank() && inHomeWindow() && RouteRepository.route.value == null) {
            scope.launch {
                delay(3500)
                homeSuggestion.value = true
                delay(25_000)
                homeSuggestion.value = false
            }
        }

        ServiceRepository.checkReminders()
    }

    fun inHomeWindow(): Boolean {
        val c = Calendar.getInstance()
        val h = c.get(Calendar.HOUR_OF_DAY)
        val dow = c.get(Calendar.DAY_OF_WEEK)
        if (Prefs.homeWeekdaysOnly.value && (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY)) return false
        val from = Prefs.homeFrom.value
        val to = Prefs.homeTo.value
        return if (from <= to) h in from until to else h >= from || h < to
    }
}
