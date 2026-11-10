package pl.mati.carlauncher.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.math.roundToInt

/**
 * Przypomnienie o zmianie opon z prognozy (Open-Meteo, 6 dni):
 *  – letnie (albo nieznane): kilka dni z rzędu średnio poniżej 7 °C albo przymrozek → zimowe,
 *  – zimowe, wiosną (marzec–maj): kilka dni z rzędu średnio powyżej 7 °C bez przymrozków → letnie.
 * Najwyżej raz dziennie, po zapłonie.
 */
object TireReminder {

    data class Reminder(val toWinter: Boolean, val title: String, val text: String, val askType: Boolean)

    val reminder = MutableStateFlow<Reminder?>(null)

    private const val T = 7.0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: kotlinx.coroutines.Job? = null

    fun label(type: String) = when (type) {
        "summer" -> "letnie"
        "winter" -> "zimowe"
        "all" -> "całoroczne"
        else -> "nie podano"
    }

    /** Sama reguła – bez stanu (do podglądu w Serwisie i do banera). */
    fun evaluate(f: ForecastRepository.Forecast?, now: Long = System.currentTimeMillis()): Reminder? {
        val type = Prefs.tireType.value
        if (type == "all") return null
        val days = f?.days?.filter { it.time + 24 * 3600_000L > now }?.take(6) ?: return null
        if (days.size < 3) return null
        val changed = Prefs.tireChanged.value
        val recent = changed > 0 && now - changed < 21L * 24 * 3600_000
        val mean = days.map { (it.max + it.min) / 2 }
        if (type != "winter") {
            if (recent && type == "summer") return null
            val cold = mean.takeWhile { it < T }.size
            val frost = days.take(3).minOf { it.min }
            if (cold >= 3 || frost <= -1.0) {
                val low = days.take(maxOf(cold, 3)).minOf { it.min }.roundToInt()
                val text = if (cold >= 3) "Przez najbliższe $cold dni średnio poniżej 7 °C (w nocy do $low °C). Letnie opony tracą wtedy przyczepność."
                    else "Zapowiadany przymrozek – w nocy do ${frost.roundToInt()} °C. Na letnich oponach droga hamowania mocno się wydłuża."
                return Reminder(true, if (cold >= 3) "Czas na opony zimowe" else "Przymrozek – pora na zimówki", text, type.isEmpty())
            }
            return null
        }
        // zimowe → letnie tylko wiosną
        val month = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.MONTH) + 1
        if (month !in 3..5 || recent) return null
        val warm = mean.takeWhile { it >= T }.size
        val frost = days.take(warm.coerceAtLeast(1)).minOf { it.min }
        if (warm >= 5 && frost > 0.0) {
            return Reminder(false, "Czas na opony letnie", "Przez najbliższe $warm dni średnio powyżej 7 °C, bez przymrozków. Zimowe szybciej się zużywają w cieple.", false)
        }
        return null
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("tires", Context.MODE_PRIVATE)

    private fun today(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
    }

    /** Po zapłonie: poczekaj na prognozę, potem (po podpowiedzi celu) pokaż przypomnienie. */
    fun onIgnition(ctx: Context) {
        val app = ctx.applicationContext
        job?.cancel()
        job = scope.launch {
            var f = ForecastRepository.forecast.value
            var waited = 0
            while ((f == null || System.currentTimeMillis() - f.fetched > 12 * 3600_000L) && waited < 180) {
                delay(3000); waited += 3
                f = ForecastRepository.forecast.value
            }
            val r = evaluate(f) ?: return@launch
            if (System.currentTimeMillis() < Prefs.tireSnooze.value) return@launch
            if (prefs(app).getInt("shown", 0) == today()) return@launch
            // nie w tym samym momencie co „Jedziesz do…”
            delay(50_000)
            while (DestinationHints.hint.value != null || AutostartManager.suggestion.value != null) delay(3000)
            prefs(app).edit().putInt("shown", today()).apply()
            reminder.value = r
            delay(45_000)
            if (reminder.value == r) reminder.value = null
        }
    }

    fun changed(type: String) {
        Prefs.setTires(type)
        reminder.value = null
    }

    fun later() {
        Prefs.setTireSnooze(System.currentTimeMillis() + 20 * 3600_000L)
        reminder.value = null
    }
}
