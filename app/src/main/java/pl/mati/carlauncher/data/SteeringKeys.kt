package pl.mati.carlauncher.data

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import pl.mati.carlauncher.ui.Screen
import pl.mati.carlauncher.ui.Toaster
import pl.mati.carlauncher.ui.UiState

/**
 * Przyciski na kierownicy → CAR OS (przewijanie muzyki itd.).
 * Radio (MCU + dekoder CAN) zamienia przyciski kierownicy na klawisze Androida albo na komunikaty
 * aplikacji radia – zależnie od modelu. Łapiemy wszystkie trzy drogi:
 *  1) klawisz do okna CAR OS (gdy CAR OS jest na ekranie),
 *  2) usługa ułatwień dostępu „CAR OS – przyciski kierownicy” (klawisze zawsze, nad każdą aplikacją),
 *  3) komunikaty (broadcast) aplikacji radia / CAN z nazwą przycisku.
 * Standardowe klawisze multimediów działają od razu, inne można „nauczyć” (naciśnij przycisk → wybierz akcję).
 * Przewijanie idzie do źródła wybranego w odtwarzaczu CAR OS (Bluetooth, Spotify, YT Music…).
 */
object SteeringKeys {

    enum class Action(val label: String) {
        NEXT("Następny utwór"),
        PREV("Poprzedni utwór"),
        PLAY_PAUSE("Pauza / odtwarzaj"),
        MUTE("Wycisz / przywróć dźwięk"),
        VOICE("Komenda głosowa"),
        MUSIC("Ekran muzyki"),
        NAV("Nawigacja"),
        HOME("Ekran główny"),
        NONE("Nic (zostaw radiu)"),
    }

    data class Press(val id: String, val label: String, val via: String, val time: Long, val action: Action?)

    /** Włączone sterowanie z kierownicy. */
    val enabled = MutableStateFlow(true)
    /** Przypisania nauczone: id przycisku → akcja. */
    val bindings = MutableStateFlow<Map<String, Action>>(emptyMap())
    /** Ostatnie naciśnięcia (podgląd w Ustawieniach). */
    val log = MutableStateFlow<List<Press>>(emptyList())
    /** Nauka: czekamy na przycisk (do tego czasu, elapsedRealtime), potem pytamy o akcję. */
    val learnUntil = MutableStateFlow(0L)
    val learned = MutableStateFlow<Press?>(null)
    /** Usługa ułatwień dostępu działa (klawisze z każdej aplikacji). */
    val serviceOn = MutableStateFlow(false)

    private var ctx: Context? = null
    private val actionSeen = HashMap<String, Long>()
    private var learnStart = 0L
    private var lastId = ""
    private var lastAt = 0L

    /** Standardowe klawisze – bez nauki. */
    private val DEFAULTS = mapOf(
        KeyEvent.KEYCODE_MEDIA_NEXT to Action.NEXT,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS to Action.PREV,
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD to Action.NEXT,
        KeyEvent.KEYCODE_MEDIA_REWIND to Action.PREV,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to Action.PLAY_PAUSE,
        KeyEvent.KEYCODE_HEADSETHOOK to Action.PLAY_PAUSE,
    )

    /** Klawisze, których nigdy nie ruszamy (głośność, systemowe). */
    private val SYSTEM = setOf(
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
        KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH,
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_TAB,
    )

    private val KEYISH = Regex("(key|swc|wheel|steer|button|btn|press)", RegexOption.IGNORE_CASE)

    fun init(c: Context) {
        if (ctx != null) return
        ctx = c.applicationContext
        val sp = Prefs.store ?: return
        enabled.value = sp.getBoolean("swc_on", true)
        try {
            val o = JSONObject(sp.getString("swc_map", "{}") ?: "{}")
            bindings.value = o.keys().asSequence().mapNotNull { k ->
                runCatching { k to Action.valueOf(o.getString(k)) }.getOrNull()
            }.toMap()
        } catch (_: Exception) {}
    }

    fun setEnabled(v: Boolean) {
        enabled.value = v
        Prefs.store?.edit()?.putBoolean("swc_on", v)?.apply()
    }

    fun bind(id: String, a: Action) {
        bindings.value = bindings.value + (id to a)
        save()
    }

    fun unbind(id: String) {
        bindings.value = bindings.value - id
        save()
    }

    fun resetAll() {
        bindings.value = emptyMap()
        save()
    }

    private fun save() {
        val o = JSONObject()
        bindings.value.forEach { (k, v) -> o.put(k, v.name) }
        Prefs.store?.edit()?.putString("swc_map", o.toString())?.apply()
    }

    fun startLearning() {
        learned.value = null
        learnStart = SystemClock.elapsedRealtime()
        learnUntil.value = learnStart + 20_000
    }

    fun stopLearning() {
        learnUntil.value = 0L
        learned.value = null
    }

    private fun learning() = SystemClock.elapsedRealtime() < learnUntil.value

    fun keyId(e: KeyEvent): String = if (e.keyCode != KeyEvent.KEYCODE_UNKNOWN) "key:${e.keyCode}" else "scan:${e.scanCode}"

    fun label(id: String): String = when {
        id.startsWith("key:") -> id.removePrefix("key:").toIntOrNull()?.let { KeyEvent.keyCodeToString(it).removePrefix("KEYCODE_") } ?: id
        id.startsWith("scan:") -> "Kod przycisku " + id.removePrefix("scan:")
        id.startsWith("bc:") -> "Radio: " + id.removePrefix("bc:").substringAfterLast('.').take(40)
        else -> id
    }

    /** Akcja dla klawisza (nauczona albo standardowa); null – nie nasz. */
    private fun actionFor(id: String, keyCode: Int?): Action? {
        bindings.value[id]?.let { return it }
        val d = keyCode?.let { DEFAULTS[it] } ?: return null
        // radio FM wybrane w CAR OS – przewijanie stacji zostawiamy aplikacji radia
        if (MediaRepository.preferred.value == "radio") return null
        return d
    }

    /**
     * Klawisz z okna CAR OS (via = "okno") albo z usługi (via = "system").
     * true = obsłużone (klawisz nie idzie dalej).
     */
    fun onKey(e: KeyEvent, via: String): Boolean {
        if (e.keyCode in SYSTEM && !learning()) return false
        val id = keyId(e)
        if (learning()) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
                val p = Press(id, label(id), via, System.currentTimeMillis(), bindings.value[id])
                note(p)
                learned.value = p
                learnUntil.value = 0L
            }
            return true
        }
        if (!enabled.value) return false
        val a = actionFor(id, e.keyCode) ?: run {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) note(Press(id, label(id), via, System.currentTimeMillis(), null))
            return false
        }
        if (a == Action.NONE) return false
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
            note(Press(id, label(id), via, System.currentTimeMillis(), a))
            perform(a)
        }
        return true
    }

    /** Komunikat aplikacji radia / CAN – czy to przycisk (nazwa akcji albo pól). */
    fun onBroadcast(action: String, extras: Map<String, String>) {
        val keyField = extras.entries.firstOrNull { KEYISH.containsMatchIn(it.key) }
        if (!KEYISH.containsMatchIn(action) && keyField == null) return
        // przyciski kierownicy, nie klawiatura ekranowa
        if (action.contains("keyboard", true)) return
        val now0 = SystemClock.elapsedRealtime()
        val prevSeen = actionSeen[action]
        actionSeen[action] = now0
        val v = keyField?.let { "#${it.key}=${it.value}" }
            ?: extras.entries.firstOrNull { it.value.toIntOrNull() != null }?.let { "#${it.key}=${it.value}" } ?: ""
        val id = "bc:$action$v"
        val now = SystemClock.elapsedRealtime()
        // naciśnięcie i puszczenie to często dwa komunikaty – jeden ruch
        if (id == lastId && now - lastAt < 350) return
        lastId = id; lastAt = now
        val lbl = action.substringAfterLast('.') + v.replace("#", " ")
        if (learning()) {
            // komunikat, który leciał też tuż przed nauką, to stan radia (wysyłany co chwilę), nie przycisk
            if (prevSeen != null && prevSeen in (learnStart - 8000)..learnStart) return
            val p = Press(id, lbl, "radio", System.currentTimeMillis(), bindings.value[id])
            note(p)
            learned.value = p
            learnUntil.value = 0L
            return
        }
        val a = bindings.value[id]
        // do podglądu tylko przypisane albo z nazwą przycisku w akcji (bez zalewu komunikatami stanu)
        if (a != null || KEYISH.containsMatchIn(action)) note(Press(id, lbl, "radio", System.currentTimeMillis(), a))
        if (enabled.value && a != null && a != Action.NONE) perform(a)
    }

    private fun note(p: Press) {
        log.value = (listOf(p) + log.value).take(12)
    }

    fun perform(a: Action) {
        val c = ctx ?: return
        when (a) {
            Action.NEXT -> { musicNext(); Toaster.show("Następny utwór") }
            Action.PREV -> { musicPrev(); Toaster.show("Poprzedni utwór") }
            Action.PLAY_PAUSE -> musicPlayPause()
            Action.MUTE -> c.getSystemService(AudioManager::class.java)
                ?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
            Action.VOICE -> { front(c); if (VoiceCommands.hasMic(c)) VoiceCommands.listen(c) }
            Action.MUSIC -> { front(c); UiState.go(Screen.MUSIC) }
            Action.NAV -> { front(c); UiState.go(Screen.NAV) }
            Action.HOME -> { front(c); UiState.go(Screen.HOME) }
            Action.NONE -> Unit
        }
    }

    // muzyka: to samo źródło co w odtwarzaczu CAR OS
    private fun bt() = MediaRepository.preferred.value == "bt" && BtAudioRepository.controllable && BtAudioRepository.state.value.connected

    fun musicNext() = if (bt()) BtAudioRepository.next() else MediaRepository.next()
    fun musicPrev() = if (bt()) BtAudioRepository.previous() else MediaRepository.previous()
    fun musicPlayPause() = if (bt()) BtAudioRepository.playPause() else MediaRepository.playPause()

    private fun front(c: Context) {
        try {
            c.startActivity(
                Intent(c, pl.mati.carlauncher.MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
        } catch (_: Exception) {}
    }

    fun serviceEnabled(c: Context): Boolean {
        val me = c.packageName + "/" + pl.mati.carlauncher.service.WheelKeysService::class.java.name
        val list = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return list.split(':').any { it.equals(me, true) || it.equals(c.packageName + "/.service.WheelKeysService", true) }
    }
}
