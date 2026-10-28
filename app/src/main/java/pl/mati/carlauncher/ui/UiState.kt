package pl.mati.carlauncher.ui

import kotlinx.coroutines.flow.MutableStateFlow
import pl.mati.carlauncher.data.Prefs

enum class Screen { HOME, NAV, MUSIC, PHONE, VEHICLE, APPS, SETTINGS }
enum class Phase { BOOT, SETUP, MAIN }

/** Globalny stan nawigacji po systemie. */
object UiState {
    val phase = MutableStateFlow(Phase.BOOT)
    val screen = MutableStateFlow(Screen.HOME)
    val settingsPage = MutableStateFlow("look")
    val setupStep = MutableStateFlow(0)
    val bootMessage = MutableStateFlow<String?>(null)
    val controlCenter = MutableStateFlow(false)

    fun go(s: Screen) {
        controlCenter.value = false
        screen.value = s
    }

    fun openSettings(page: String) {
        settingsPage.value = page
        go(Screen.SETTINGS)
    }

    fun start() {
        phase.value = if (Prefs.bootAnim.value || !Prefs.profile.value.setupDone) Phase.BOOT else Phase.MAIN
    }

    fun bootFinished() {
        bootMessage.value = null
        phase.value = if (Prefs.profile.value.setupDone) Phase.MAIN else Phase.SETUP
    }

    fun reboot(message: String? = null) {
        controlCenter.value = false
        bootMessage.value = message
        screen.value = Screen.HOME
        phase.value = Phase.BOOT
    }

    fun openSetup(step: Int) {
        controlCenter.value = false
        setupStep.value = step
        phase.value = Phase.SETUP
    }
}

object Toaster {
    val message = MutableStateFlow<Pair<String, Long>?>(null)
    fun show(text: String) {
        message.value = text to System.nanoTime()
    }
}
