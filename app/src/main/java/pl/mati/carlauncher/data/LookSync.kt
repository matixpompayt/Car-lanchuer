package pl.mati.carlauncher.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import pl.mati.caros.core.Look

/**
 * Wspólny wygląd z CAR OS Connect: Liquid Glass (gładkie / matowe), jasny / ciemny, kolor akcentu.
 * Animacje NIE – telefon jest mocniejszy od radia, każde ma swoje ustawienie.
 * Zmiana w radiu idzie do telefonu w statusie, zmiana w telefonie przychodzi komendą „look” – nowsza wygrywa.
 */
object LookSync {
    private var started = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun tone(mode: String) = when (mode) { "light" -> "light"; "dark" -> "dark"; else -> "auto" }

    private fun time() = Prefs.store?.getLong("look_t", 0L) ?: 0L

    /** Do porównań „czy coś się zmieniło” – bez animacji (nie są wspólne). */
    private fun key(l: Look) = l.copy(anim = true).content

    /** Wygląd radia teraz (z czasem ostatniej zmiany). */
    fun current(): Look = Look(Prefs.glassUi.value, tone(Prefs.themeMode.value), Prefs.accent.value, Prefs.glassAnim.value, time(), Prefs.glassFrost.value)

    /** Zmiany w ustawieniach radia → nowy czas wyglądu i status do telefonu. */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    fun start() {
        if (started) return
        started = true
        scope.launch {
            delay(3000) // ustawienia wczytane
            combine(Prefs.glassUi, Prefs.themeMode, Prefs.accent, Prefs.glassFrost) { _, _, _, _ -> key(current()) }
                .debounce(1500)
                .collect { c ->
                    val sp = Prefs.store ?: return@collect
                    val prev = sp.getString("look_c", null)
                    if (prev == null) {
                        // pierwszy raz: punkt odniesienia (czas 0 – przy pierwszym połączeniu wygląd z telefonu wygrywa)
                        sp.edit().putString("look_c", c).apply()
                        return@collect
                    }
                    if (c == prev) return@collect
                    sp.edit().putString("look_c", c).putLong("look_t", System.currentTimeMillis()).apply()
                    ConnectRepository.publishStatusSoon()
                }
        }
    }

    /** Wygląd z telefonu – stosowany, gdy nowszy niż radia. Zwraca opis do komunikatu albo null. */
    fun apply(l: Look): String? {
        if (!Prefs.lookSync.value) return null
        if (l.t <= time()) return null
        val mode = when (l.tone) {
            "light", "dark" -> l.tone
            else -> Prefs.themeMode.value.takeIf { it != "light" && it != "dark" } ?: "lights"
        }
        // animacja zostaje radia (telefon jest mocniejszy – tam może być włączona)
        val target = Look(l.glass, tone(mode), l.accent ?: Prefs.accent.value, Prefs.glassAnim.value, frost = l.frost ?: Prefs.glassFrost.value)
        val changed = key(target) != key(current())
        // najpierw „zsynchronizowany” stan – nasłuch zmian nie uzna tego za zmianę w radiu
        Prefs.store?.edit()?.putString("look_c", key(target))?.putLong("look_t", l.t)?.apply()
        if (Prefs.glassUi.value != l.glass) Prefs.setGlassUi(l.glass)
        if (Prefs.themeMode.value != mode) Prefs.setThemeMode(mode)
        l.accent?.let { if (it != Prefs.accent.value) Prefs.setAccent(it) }
        l.frost?.let { if (Prefs.glassFrost.value != it) Prefs.setGlassFrost(it) }
        return if (changed) l.describe() else null
    }
}
