package pl.mati.carlauncher.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/**
 * Ceny paliw w Polsce: maksymalne ceny z obwieszczeń Ministra Energii (ON, Pb95, Pb98)
 * i średnia LPG. Plik docs/fuel.json aktualizuje co pół godziny GitHub Actions
 * (tools/fuelprices/fetch.py); tu tylko go pobieramy i liczymy „dziś / jutro / zmiana”.
 */
object FuelPrices {
    private const val MAX_VALID_DAYS = 5L
    const val URL_JSON = "https://raw.githubusercontent.com/matixpompayt/Car-lanchuer/main/docs/fuel.json"

    enum class Fuel(val key: String, val label: String, val short: String, val color: Long) {
        ON("on", "Olej napędowy", "ON", 0xFF38BDF8),
        PB95("pb95", "Benzyna 95", "Pb95", 0xFF34D399),
        PB98("pb98", "Benzyna 98", "Pb98", 0xFFFBBF24),
        LPG("lpg", "Autogaz · średnia", "LPG", 0xFFA78BFA),
    }

    /** Jeden dzień: data i ceny (null = brak danych dla paliwa). */
    data class Day(val date: LocalDate, val prices: Map<Fuel, Double>)

    data class Snapshot(
        /** Dzień po dniu (ostatnia obowiązująca cena; pusta mapa = brak cen maksymalnych), do 90 dni wstecz. */
        val days: List<Day>,
        val today: Map<Fuel, Double>,
        val tomorrow: Map<Fuel, Double>,
        /** Jutro obowiązuje to samo obwieszczenie co dziś (weekend). */
        val tomorrowSame: Boolean,
        /** Zmiana dziś względem poprzedniej ceny (LPG: względem ceny sprzed tygodnia). */
        val change: Map<Fuel, Double>,
        /** Od kiedy obowiązuje dzisiejsza cena (albo ostatnia znana, gdy dziś brak). */
        val latestFrom: LocalDate?,
        val lpgDate: LocalDate?,
        val updated: String,
    ) {
        fun price(f: Fuel) = today[f]
        /** Dziś nie obowiązuje żadne obwieszczenie (program wstrzymany / brak danych). */
        val noCurrent get() = today[Fuel.ON] == null && today[Fuel.PB95] == null
    }

    val data = MutableStateFlow<Snapshot?>(null)
    val loading = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var appCtx: Context? = null
    private var lastFetch = 0L

    fun start(ctx: Context) {
        if (appCtx != null) return
        appCtx = ctx.applicationContext
        fav.value = favorite(ctx)
        scope.launch {
            try { cache()?.takeIf { it.exists() }?.readText()?.let { data.value = parse(it) } } catch (_: Exception) {}
            while (isActive) {
                refresh(false)
                delay(30 * 60_000L)
            }
        }
    }

    fun refreshNow() { scope.launch { refresh(true) } }
    fun refreshIfStale() { scope.launch { refresh(false) } }

    /** Ulubione paliwo (kafel, wyróżnienie). */
    fun favorite(ctx: Context): Fuel =
        Fuel.entries.firstOrNull { it.key == ctx.getSharedPreferences("fuel_prices", Context.MODE_PRIVATE).getString("fav", "on") } ?: Fuel.ON

    val fav = MutableStateFlow(Fuel.ON)

    fun setFavorite(ctx: Context, f: Fuel) {
        fav.value = f
        ctx.getSharedPreferences("fuel_prices", Context.MODE_PRIVATE).edit().putString("fav", f.key).apply()
    }

    private fun cache() = appCtx?.let { File(it.filesDir, "fuel.json") }

    private suspend fun refresh(force: Boolean) {
        if (!force && System.currentTimeMillis() - lastFetch < 10 * 60_000L) return
        loading.value = true
        try {
            val c = URL(URL_JSON + "?t=" + System.currentTimeMillis() / 600_000).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 15_000
            c.setRequestProperty("User-Agent", "CarOS")
            val text = try {
                if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
                c.inputStream.bufferedReader().use { it.readText() }
            } finally { c.disconnect() }
            data.value = parse(text)
            cache()?.writeText(text)
            lastFetch = System.currentTimeMillis()
            error.value = null
        } catch (e: Exception) {
            error.value = if (data.value == null) "Brak połączenia – nie udało się pobrać cen" else null
        } finally {
            loading.value = false
        }
    }

    fun parse(text: String): Snapshot {
        val o = JSONObject(text)
        // obwieszczenia: cena od dnia „from” do następnego obwieszczenia
        val max = ArrayList<Pair<LocalDate, Map<Fuel, Double>>>()
        o.optJSONArray("history")?.let { a ->
            for (i in 0 until a.length()) {
                val h = a.getJSONObject(i)
                val d = try { LocalDate.parse(h.getString("from")) } catch (_: Exception) { continue }
                max += d to mapOf(Fuel.ON to h.optDouble("on"), Fuel.PB95 to h.optDouble("pb95"), Fuel.PB98 to h.optDouble("pb98")).filterValues { !it.isNaN() }
            }
        }
        max.sortBy { it.first }
        val lpg = ArrayList<Pair<LocalDate, Double>>()
        o.optJSONArray("lpgHistory")?.let { a ->
            for (i in 0 until a.length()) {
                val h = a.getJSONObject(i)
                val d = try { LocalDate.parse(h.getString("date")) } catch (_: Exception) { continue }
                lpg += d to h.optDouble("price")
            }
        }
        lpg.sortBy { it.first }

        val today = LocalDate.now()
        // obwieszczenie obowiązuje do następnego (w piątek: sob–pon); po >5 dniach bez nowego uznajemy, że cen maksymalnych nie ma
        fun maxEntry(d: LocalDate) = max.lastOrNull { !it.first.isAfter(d) }?.takeIf { !it.first.plusDays(MAX_VALID_DAYS).isBefore(d) }
        fun maxAt(d: LocalDate) = maxEntry(d)?.second
        fun lpgAt(d: LocalDate) = lpg.lastOrNull { !it.first.isAfter(d) }?.takeIf { !it.first.plusDays(10).isBefore(d) }?.second

        val cur = maxEntry(today)
        val todayMax = cur?.second ?: emptyMap()
        val todayLpg = lpgAt(today)
        val now = HashMap(todayMax).apply { todayLpg?.let { put(Fuel.LPG, it) } }

        val tomorrow = HashMap<Fuel, Double>()
        max.firstOrNull { it.first == today.plusDays(1) }?.second?.let { tomorrow.putAll(it) }
        // piątkowe obwieszczenie obowiązuje sob–pon, więc w weekend „jutro” już znamy (ta sama cena)
        var tomorrowSame = false
        if (tomorrow.isEmpty() && cur != null && cur.first.dayOfWeek == java.time.DayOfWeek.SATURDAY &&
            !today.plusDays(1).isAfter(cur.first.plusDays(2))
        ) { tomorrow.putAll(cur.second); tomorrowSame = true }

        val change = HashMap<Fuel, Double>()
        val prevMax = cur?.let { c -> max.lastOrNull { it.first.isBefore(c.first) }?.second }
        for (f in listOf(Fuel.ON, Fuel.PB95, Fuel.PB98)) {
            val a = todayMax[f]; val b = prevMax?.get(f)
            if (a != null && b != null) change[f] = a - b
        }
        val lpgWeek = lpg.lastOrNull { !it.first.isAfter(today.minusDays(7)) }?.second
        if (todayLpg != null && lpgWeek != null) change[Fuel.LPG] = todayLpg - lpgWeek

        // dni do wykresu: maks. 90 dni wstecz, do jutra (jeśli już jest obwieszczenie)
        val first = (listOfNotNull(max.firstOrNull()?.first, lpg.firstOrNull()?.first).minOrNull() ?: today)
            .let { if (it.isBefore(today.minusDays(90))) today.minusDays(90) else it }
        val last = if (tomorrow.isNotEmpty() && !tomorrowSame) today.plusDays(1) else today
        val days = ArrayList<Day>()
        var d = first
        while (!d.isAfter(last)) {
            val m = HashMap<Fuel, Double>()
            maxAt(d)?.let { m.putAll(it) }
            lpgAt(d)?.let { m[Fuel.LPG] = it }
            days += Day(d, m)
            d = d.plusDays(1)
        }
        return Snapshot(
            days, now, tomorrow, tomorrowSame, change,
            cur?.first ?: max.lastOrNull()?.first,
            lpg.lastOrNull()?.first,
            o.optString("updated"),
        )
    }
}
