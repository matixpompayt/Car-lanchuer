package pl.mati.carlauncher.data

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.mati.carlauncher.ui.Screen
import pl.mati.carlauncher.ui.UiState
import java.text.Normalizer

/**
 * Komendy głosowe po polsku (rozpoznawanie mowy Androida / Google):
 * nawigacja (dom, praca, dowolne miejsce), muzyka, głośność, telefon, ekrany, tryb nocny, parking.
 */
object VoiceCommands {

    enum class Phase { IDLE, LISTENING, THINKING, DONE, ERROR }

    data class State(val phase: Phase, val heard: String = "", val reply: String = "", val level: Float = 0f)

    val state = MutableStateFlow(State(Phase.IDLE))

    private var recognizer: SpeechRecognizer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val permissions = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS)

    fun hasMic(ctx: Context) = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Start słuchania (wołać na wątku głównym). */
    fun listen(ctx: Context) {
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            finish(Phase.ERROR, "", "Brak rozpoznawania mowy – zainstaluj aplikację Google ze Sklepu Play")
            return
        }
        cancel()
        val r = try { SpeechRecognizer.createSpeechRecognizer(ctx) } catch (_: Exception) { null }
        if (r == null) {
            finish(Phase.ERROR, "", "Nie mogę włączyć mikrofonu")
            return
        }
        recognizer = r
        state.value = State(Phase.LISTENING)
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {
                state.value = state.value.copy(level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                state.value = state.value.copy(phase = Phase.THINKING, level = 0f)
            }
            override fun onError(error: Int) {
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Nie usłyszałem – spróbuj jeszcze raz"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Brak internetu do rozpoznawania mowy"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Zezwól CAR OS na mikrofon"
                    else -> "Nie udało się rozpoznać mowy"
                }
                finish(Phase.ERROR, state.value.heard, msg)
            }
            override fun onResults(results: Bundle?) {
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                val text = list.firstOrNull().orEmpty()
                if (text.isBlank()) finish(Phase.ERROR, "", "Nie usłyszałem – spróbuj jeszcze raz")
                else handle(ctx.applicationContext, text, list)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let {
                    state.value = state.value.copy(heard = it)
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pl-PL")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        try {
            r.startListening(i)
        } catch (_: Exception) {
            finish(Phase.ERROR, "", "Nie mogę włączyć mikrofonu")
        }
    }

    fun cancel() {
        try { recognizer?.cancel(); recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
        if (state.value.phase == Phase.LISTENING || state.value.phase == Phase.THINKING) state.value = State(Phase.IDLE)
    }

    fun close() {
        cancel()
        state.value = State(Phase.IDLE)
    }

    private fun finish(p: Phase, heard: String, reply: String, speak: Boolean = false) {
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
        state.value = State(p, heard, reply)
        if (speak && reply.isNotBlank()) NavVoice.say(reply, force = true)
        scope.launch {
            val mine = state.value
            delay(if (p == Phase.ERROR) 3500 else 2500)
            if (state.value == mine) state.value = State(Phase.IDLE)
        }
    }

    private fun plain(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").replace('ł', 'l').trim()

    // ---------- rozumienie komend ----------

    private fun handle(ctx: Context, text: String, alts: List<String>) {
        val t = plain(text)
        fun has(vararg w: String) = w.any { t.contains(it) }
        NavVoice.init(ctx)

        // nawigacja
        val nav = Regex("^(jedz|jedziemy|nawiguj|prowadz|zawiez|zabierz mnie|trasa|droga|pokaz droge|wyznacz trase)\\s*(do|na|pod|w)?\\s+(.+)$").find(t)
        if (nav != null) {
            val target = nav.groupValues[3].trim()
            val orig = text.trim().split(Regex("\\s+")).let { words ->
                val skip = nav.groupValues[1].split(" ").size + (if (nav.groupValues[2].isNotBlank()) 1 else 0)
                words.drop(skip).joinToString(" ")
            }
            navigate(ctx, target, orig.ifBlank { target }, text)
            return
        }
        if (t == "do domu" || t == "dom" || has("wracamy do domu")) return navigate(ctx, "domu", "dom", text)
        if (has("do pracy")) return navigate(ctx, "pracy", "praca", text)
        if (has("anuluj trase", "zakoncz nawigacje", "zakoncz trase", "stop nawigacj", "wylacz nawigacj", "anuluj nawigacj")) {
            RouteRepository.cancel(ctx)
            return finish(Phase.DONE, text, "Nawigacja zakończona", speak = true)
        }

        // telefon
        val callM = Regex("^(zadzwon|dzwon|polacz)\\s*(do|z|pod)?\\s+(.+)$").find(t)
        if (callM != null) return dial(ctx, callM.groupValues[3].trim(), text)

        // muzyka
        when {
            has("nastepn", "dalej", "kolejn", "przewin", "pomin") -> { MediaRepository.next(); return finish(Phase.DONE, text, "Następny utwór") }
            has("poprzedni", "wstecz", "cofnij") -> { MediaRepository.previous(); return finish(Phase.DONE, text, "Poprzedni utwór") }
            has("pauza", "zatrzymaj", "stop muzyk", "wylacz muzyk", "zastopuj") -> { MediaRepository.playPause(); return finish(Phase.DONE, text, "Pauza") }
            has("graj", "wznow", "odtwarzaj", "puszczaj", "wlacz muzyk", "play") -> { MediaRepository.play(); return finish(Phase.DONE, text, "Gram") }
        }

        // głośność
        val vol = SystemControls.volume.value
        val max = SystemControls.volumeMax.value
        when {
            has("glosniej", "podglosnij", "zglosnij") -> { SystemControls.setVolume(ctx, vol + maxOf(1, max / 8)); return finish(Phase.DONE, text, "Głośniej") }
            has("ciszej", "przycisz", "sciszyc", "scisz") -> { SystemControls.setVolume(ctx, vol - maxOf(1, max / 8)); return finish(Phase.DONE, text, "Ciszej") }
            has("wycisz") -> { SystemControls.setVolume(ctx, 0); return finish(Phase.DONE, text, "Wyciszono") }
        }

        // wygląd
        if (has("tryb nocny", "ciemny", "noc")) {
            if (ThemeManager.light.value) ThemeManager.toggleNight()
            return finish(Phase.DONE, text, "Tryb nocny")
        }
        if (has("tryb dzienny", "jasny", "dzien")) {
            if (!ThemeManager.light.value) ThemeManager.toggleNight()
            return finish(Phase.DONE, text, "Tryb dzienny")
        }

        // parking
        if (has("zapisz parking", "zapisz miejsce", "zaparkowalem", "tu stoje", "zapamietaj miejsce")) {
            val ok = ParkingRepository.saveManual()
            return finish(if (ok) Phase.DONE else Phase.ERROR, text, if (ok) "Zapisałem miejsce parkowania" else "Brak pozycji GPS", speak = ok)
        }

        if (has("ceny paliw", "cena paliw", "ceny paliwa", "cena ropy", "cena diesla", "ile kosztuje paliwo", "ile kosztuje ropa", "cena benzyny", "ceny benzyny", "cena gazu", "cena lpg")) {
            UiState.go(Screen.PRICES)
            val snap = FuelPrices.data.value
            val f = when {
                has("benzyn", "95") -> FuelPrices.Fuel.PB95
                has("98") -> FuelPrices.Fuel.PB98
                has("gaz", "lpg") -> FuelPrices.Fuel.LPG
                has("rop", "diesl", "napedow") -> FuelPrices.Fuel.ON
                else -> FuelPrices.fav.value
            }
            val p = snap?.today?.get(f)
            val name = if (f == FuelPrices.Fuel.LPG) "Gaz LPG" else f.label
            val reply = if (p == null) "Ceny paliw" else "$name: dziś maksymalnie ${String.format(java.util.Locale("pl", "PL"), "%.2f", p)} zł za litr" +
                (snap?.tomorrow?.get(f)?.let { t -> ", jutro ${String.format(java.util.Locale("pl", "PL"), "%.2f", t)}" } ?: "")
            return finish(Phase.DONE, text, if (f == FuelPrices.Fuel.LPG && p != null) reply.replace("maksymalnie", "średnio") else reply, speak = p != null)
        }

        // ekrany
        val screen = when {
            has("pogod") -> Screen.WEATHER to "Pogoda"
            has("muzyk", "radio", "odtwarzacz") -> Screen.MUSIC to "Muzyka"
            has("telefon") -> Screen.PHONE to "Telefon"
            has("ustawieni") -> Screen.SETTINGS to "Ustawienia"
            has("serwis") -> Screen.SERVICE to "Serwis"
            has("tras", "dziennik", "statystyk") -> Screen.TRIPS to "Dziennik tras"
            has("mapa", "nawigacj") -> Screen.NAV to "Nawigacja"
            has("pojazd", "zegary", "licznik") -> Screen.VEHICLE to "Pojazd"
            has("ekran glowny", "start", "home") -> Screen.HOME to "Ekran główny"
            else -> null
        }
        if (screen != null) {
            UiState.go(screen.first)
            return finish(Phase.DONE, text, screen.second)
        }
        finish(Phase.ERROR, text, "Nie rozumiem – powiedz np. „jedź do domu”, „następna piosenka”, „zadzwoń do Mamy”")
    }

    private fun navigate(ctx: Context, target: String, original: String, heard: String) {
        val p = Prefs.profile.value
        val home = target.startsWith("dom") || target == "domu"
        val work = target.startsWith("prac")
        when {
            home && p.home.isNotBlank() -> {
                RouteRepository.quickStart(ctx, p.home, "Dom")
                finish(Phase.DONE, heard, "Jedziemy do domu", speak = Prefs.navEngine.value != "caros")
            }
            work && p.work.isNotBlank() -> {
                RouteRepository.quickStart(ctx, p.work, "Praca")
                finish(Phase.DONE, heard, "Jedziemy do pracy", speak = Prefs.navEngine.value != "caros")
            }
            home || work -> finish(Phase.ERROR, heard, "Najpierw ustaw adres ${if (home) "domu" else "pracy"} w ustawieniach")
            else -> {
                state.value = State(Phase.THINKING, heard, "Szukam: $original…")
                // ulubione po nazwie, potem wyszukiwarka
                val fav = PlacesRepository.favorites.value.firstOrNull { plain(it.name).contains(target) || target.contains(plain(it.name)) }
                scope.launch {
                    val place = fav ?: withContext(Dispatchers.IO) { RouteRepository.geocode(original) }
                    if (place == null) {
                        finish(Phase.ERROR, heard, "Nie znalazłem: $original")
                    } else {
                        RouteRepository.quickStartPlace(ctx, place)
                        UiState.go(Screen.HOME)
                        finish(Phase.DONE, heard, "Jedziemy: ${place.name}", speak = Prefs.navEngine.value != "caros")
                    }
                }
            }
        }
    }

    private fun dial(ctx: Context, who: String, heard: String) {
        val digits = who.filter { it.isDigit() || it == '+' }
        val number = if (digits.count { it.isDigit() } >= 6) digits else findContact(ctx, who)
        if (number == null) {
            finish(Phase.ERROR, heard, "Nie znalazłem kontaktu: $who")
            return
        }
        val ok = try {
            ctx.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Exception) {
            false
        }
        finish(if (ok) Phase.DONE else Phase.ERROR, heard, if (ok) "Dzwonię: $who" else "Nie mogę zadzwonić z tego radia")
    }

    private fun findContact(ctx: Context, who: String): String? {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val want = plain(who)
        return try {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, null,
            )?.use { c ->
                var best: String? = null
                var bestScore = 0
                while (c.moveToNext()) {
                    val name = plain(c.getString(0) ?: continue)
                    val num = c.getString(1) ?: continue
                    val score = when {
                        name == want -> 3
                        name.startsWith(want) || want.startsWith(name) -> 2
                        name.split(" ").any { it.isNotBlank() && (want.contains(it) || it.startsWith(want.take(4))) } -> 1
                        else -> 0
                    }
                    if (score > bestScore) { bestScore = score; best = num }
                }
                best
            }
        } catch (_: Exception) {
            null
        }
    }
}
