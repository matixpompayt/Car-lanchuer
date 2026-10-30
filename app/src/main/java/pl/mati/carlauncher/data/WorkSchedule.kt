package pl.mati.carlauncher.data

import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * Grafik pracy → „Jedziesz do pracy?” (2 h przed zmianą) i „Jedziesz do domu?” (od końca zmiany).
 *  - Tesla (Silver): rotacja co tydzień nocna → pierwsza → popołudniowa (nocki 1.11–5.11.2026, nd–czw),
 *  - niestandardowy: kierowca sam ustawia zmianę na ten i następny tydzień.
 */
object WorkSchedule {

    data class Shift(val kind: String, val start: Int, val end: Int) {
        val off get() = kind == OFF
        val overnight get() = !off && end <= start
        val name: String
            get() = when (kind) {
                NIGHT -> "Nocna"
                FIRST -> "Pierwsza"
                AFTERNOON -> "Popołudniowa"
                OFF -> "Wolne"
                else -> "Własna"
            }
        val hours: String get() = if (off) "" else "${hm(start)}–${hm(end)}"
        val label: String get() = if (off) "Wolne" else "$name $hours"
    }

    data class Suggestion(val work: Boolean, val address: String, val shift: Shift) {
        val title get() = if (work) "Jedziesz do pracy?" else "Jedziesz do domu?"
        val target get() = if (work) "Praca" else "Dom"
    }

    const val NIGHT = "N"
    const val FIRST = "R"
    const val AFTERNOON = "P"
    const val OFF = "W"
    const val CUSTOM = "C"

    val PRESETS = listOf(
        Shift(FIRST, 6 * 60, 14 * 60),
        Shift(AFTERNOON, 14 * 60, 22 * 60),
        Shift(NIGHT, 22 * 60, 6 * 60),
        Shift(OFF, 0, 0),
    )
    private fun preset(kind: String) = PRESETS.first { it.kind == kind }

    /** Tesla Silver: tydzień od poniedziałku 2.11.2026 = nocna, potem pierwsza, potem popołudniowa. */
    private val TESLA_ANCHOR: LocalDate = LocalDate.of(2026, 11, 2)
    private val TESLA_CYCLE = listOf(NIGHT, FIRST, AFTERNOON)

    /** Okno propozycji: do pracy 2 h przed początkiem, do domu przez 2 h od końca zmiany. */
    private const val BEFORE_WORK_MIN = 120
    private const val AFTER_WORK_MIN = 120

    val mode = MutableStateFlow("tesla")          // "tesla" | "custom"
    val days = MutableStateFlow(setOf(1, 2, 3, 4, 5)) // 1 = pon … 7 = nd
    val custom = MutableStateFlow<Map<Long, Shift>>(emptyMap())

    private var loaded = false

    fun load() {
        if (loaded) return
        val sp = Prefs.store ?: return
        loaded = true
        mode.value = sp.getString("work_mode", "tesla") ?: "tesla"
        days.value = (sp.getString("work_days", "1,2,3,4,5") ?: "").split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
        custom.value = try {
            val o = JSONObject(sp.getString("work_custom", "{}") ?: "{}")
            o.keys().asSequence().associate { k ->
                val s = o.getJSONObject(k)
                k.toLong() to Shift(s.getString("k"), s.getInt("s"), s.getInt("e"))
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun setMode(m: String) {
        mode.value = m
        Prefs.store?.edit()?.putString("work_mode", m)?.apply()
    }

    fun toggleDay(d: Int) {
        val n = if (d in days.value) days.value - d else days.value + d
        days.value = n
        Prefs.store?.edit()?.putString("work_days", n.sorted().joinToString(","))?.apply()
    }

    fun setCustom(monday: LocalDate, shift: Shift) {
        val key = monday.toEpochDay()
        // stare tygodnie nie są potrzebne
        val minKey = mondayOf(LocalDate.now()).minusWeeks(4).toEpochDay()
        val m = custom.value.filterKeys { it >= minKey } + (key to shift)
        custom.value = m
        val o = JSONObject()
        m.forEach { (k, s) -> o.put(k.toString(), JSONObject().put("k", s.kind).put("s", s.start).put("e", s.end)) }
        Prefs.store?.edit()?.putString("work_custom", o.toString())?.apply()
    }

    fun mondayOf(d: LocalDate): LocalDate = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /** Zmiana w tygodniu zaczynającym się w [monday]; null = nieustawiona. */
    fun shiftFor(monday: LocalDate): Shift? {
        load()
        return if (mode.value == "tesla") {
            val w = ChronoUnit.WEEKS.between(TESLA_ANCHOR, monday)
            preset(TESLA_CYCLE[Math.floorMod(w, 3L).toInt()])
        } else {
            // niestandardowy: ustawiona na ten tydzień, a jak brak – ostatnia wcześniej ustawiona
            val key = monday.toEpochDay()
            custom.value[key] ?: custom.value.filterKeys { it < key }.maxByOrNull { it.key }?.value
        }
    }

    /** Czy [monday] ma zmianę ustawioną wprost (tryb niestandardowy). */
    fun isSet(monday: LocalDate) = custom.value.containsKey(monday.toEpochDay())

    /**
     * Zmiana, która ZACZYNA SIĘ danego dnia (albo null).
     * Tesla: nocki od niedzieli do czwartku (np. 1.11–5.11), pierwsza i popołudniowa pn–pt.
     * Niestandardowy: wybrane dni; nocka w niedzielę liczy się do następnego tygodnia.
     */
    fun shiftStartingOn(d: LocalDate): Shift? {
        load()
        val dow = d.dayOfWeek.value // 1 = pon … 7 = nd
        val week = shiftFor(mondayOf(d))
        val nextWeek = shiftFor(mondayOf(d).plusWeeks(1))
        if (mode.value == "tesla") {
            return when (dow) {
                7 -> nextWeek?.takeIf { it.kind == NIGHT }
                in 1..4 -> week
                5 -> week?.takeIf { it.kind != NIGHT }
                else -> null
            }
        }
        if (dow !in days.value) return null
        if (dow == 7 && nextWeek?.overnight == true) return nextWeek
        return week?.takeIf { !it.off }
    }

    /** Dni rozpoczęcia zmian w tygodniu [monday] – do wyświetlenia (np. „1.11–5.11”). */
    fun startDays(monday: LocalDate, s: Shift?): String {
        if (s == null || s.off) return weekRange(monday)
        return if (mode.value == "tesla" && s.kind == NIGHT) "${monday.minusDays(1).format(DM)}–${monday.plusDays(3).format(DM)}"
        else if (mode.value == "tesla") "${monday.format(DM)}–${monday.plusDays(4).format(DM)}"
        else weekRange(monday)
    }

    /** Propozycja trasy teraz (przy „zapłonie”), albo null. */
    fun suggestion(now: LocalDateTime = LocalDateTime.now()): Suggestion? {
        load()
        val p = Prefs.profile.value
        val today = now.toLocalDate()
        val nowMin = now.hour * 60 + now.minute
        val s = shiftStartingOn(today)

        // do pracy: 2 h przed początkiem dzisiejszej zmiany
        if (p.work.isNotBlank() && s != null && !s.off && nowMin in (s.start - BEFORE_WORK_MIN) until s.start) {
            return Suggestion(true, p.work, s)
        }
        if (p.home.isBlank()) return null
        // do domu po zmianie dziennej
        if (s != null && !s.off && !s.overnight && nowMin in s.end until (s.end + AFTER_WORK_MIN)) return Suggestion(false, p.home, s)
        // do domu po nocce zaczętej wczoraj
        val y = shiftStartingOn(today.minusDays(1))
        if (y != null && y.overnight && nowMin in y.end until (y.end + AFTER_WORK_MIN)) return Suggestion(false, p.home, y)
        return null
    }

    fun hm(min: Int): String = String.format(Locale.US, "%d:%02d", (min / 60) % 24, min % 60)

    private val DM = DateTimeFormatter.ofPattern("d.MM")
    fun weekRange(monday: LocalDate): String = "${monday.format(DM)}–${monday.plusDays(6).format(DM)}"
}
