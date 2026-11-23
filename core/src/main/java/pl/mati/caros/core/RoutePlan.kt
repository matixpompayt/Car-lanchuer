package pl.mati.caros.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Postoje na trasie – wspólne dla auta i telefonu: tankowania (tyle, ile trzeba, po każdym pełny bak)
 * i przerwy co ok. 2 h. Stacje i MOP-y z OpenStreetMap (jedno zapytanie, zapas: TomTom),
 * ceny na stacjach z oficjalnych źródeł (DE, FR, AT, IT, ES), w Polsce cena maksymalna,
 * w pozostałych krajach UE średnia z biuletynu Komisji Europejskiej. Najtańsze (w złotych) pierwsze.
 */
object RoutePlan {
    data class Place(val name: String, val detail: String, val lat: Double, val lon: Double)

    /**
     * Jedno miejsce do wyboru: [atKm] – km od startu, [atTime] – kiedy tam będziemy (ms), [offM] – ile od trasy,
     * [rest] – MOP / parking. Cena wybranego paliwa: [price] w walucie [cur], [pln] – w złotych; [src]: „station” –
     * na tej stacji, „max” – cena maksymalna w Polsce, „avg” – średnia w kraju (UE); [iso] – kraj; [cheap] – sieć zwykle tańsza.
     */
    data class Opt(
        val place: Place, val atKm: Double, val atTime: Long, val offM: Double, val rest: Boolean,
        val price: Double? = null, val cur: String? = null, val pln: Double? = null, val cheap: Boolean = false, val open: Boolean = true,
        val src: String? = null, val iso: String? = null,
    )

    /** Grupa: „fuel” (tankowanie nr [n]) albo „break” (przerwa po [hours] h) – kilka propozycji, najlepsza pierwsza. */
    data class Group(val kind: String, val hours: Int, val atKm: Double, val options: List<Opt>, val n: Int = 1)

    /**
     * [fullKm] – zasięg pełnego baku użyty w planie (z auta albo szacunek: [fullGuess]);
     * [deKeyMissing] – trasa przez Niemcy, a nie ma klucza Tankerkönig; [deOk] – czy ceny z Niemiec przyszły.
     */
    data class Result(
        val groups: List<Group>, val totalKm: Double, val fuel: FuelType,
        val fullKm: Double? = null, val fullGuess: Boolean = false, val deKeyMissing: Boolean = false, val deOk: Boolean? = null,
    )

    private var cacheKey = ""
    private var cacheTotal = 0.0
    private var cacheVal: Result? = null

    /**
     * Postoje dla trasy [lats]/[lons] (czas jazdy [travelS], wyjazd [departMs]).
     * [rangeKm] – ile zostało zasięgu (null = nie wiadomo → bez tankowań), [reserveKm] – minimalny zasięg z ustawień,
     * [fullKmCar] – zasięg pełnego baku, [plMax] – cena maksymalna w Polsce dla [fuel].
     * Wynik zapamiętany dla tej samej trasy. Rzuca [java.io.IOException], gdy nie udało się nic sprawdzić.
     */
    suspend fun stops(
        ctx: Context, lats: DoubleArray, lons: DoubleArray, travelS: Double, departMs: Long,
        rangeKm: Double?, reserveKm: Int, fuel: FuelType, tkKey: String, tomtomKey: String?,
        fullKmCar: Double? = null, plMax: Double? = null, allowTomTom: () -> Boolean = { true },
    ): Result = withContext(Dispatchers.IO) {
        val n = lats.size
        if (n < 2) return@withContext Result(emptyList(), 0.0, fuel)
        val cum = Geo.cumulative(lats, lons)
        val total = cum.last()
        val totalKm = total / 1000
        val key = String.format(
            java.util.Locale.US, "%.3f,%.3f|%.3f,%.3f|%d|%d|%s|%d|%d",
            lats[0], lons[0], lats[n - 1], lons[n - 1], ((rangeKm ?: -1.0) / 20).roundToInt(), reserveKm, fuel.key, tkKey.length,
            ((fullKmCar ?: -1.0) / 20).roundToInt(),
        )
        // ta sama trasa (np. tylko doszedł postój z listy) – bez ponownego szukania
        synchronized(this@RoutePlan) {
            if (key == cacheKey && abs(total - cacheTotal) < maxOf(3000.0, cacheTotal * 0.03)) cacheVal?.let { return@withContext it }
        }
        val speed = total / travelS.coerceAtLeast(60.0) // m/s
        val fullGuess = fullKmCar == null
        val fullKm = fullKmCar ?: maxOf(rangeKm ?: 0.0, 650.0)
        val wins = ArrayList<AlongRoute.Window>()
        val meta = ArrayList<Pair<String, Int>>()
        // tankowania: kolejno, zanim zasięg spadnie poniżej minimum; po każdym – pełny bak
        val fuelAt = ArrayList<Double>()
        if (rangeKm != null) {
            var pos = 0.0
            var left = rangeKm * 1000
            var k = 0
            while (left - (total - pos) < reserveKm * 1000 && k < 6) {
                val target = (pos + left - reserveKm * 1000).coerceIn(pos + 5_000, total)
                val back = minOf(70_000.0, target - pos - 5_000).coerceAtLeast(5_000.0)
                wins += AlongRoute.Window(target, back, 10_000.0, "fuel"); meta += "fuel" to (++k)
                val at = (target - minOf(20_000.0, back / 2)).coerceAtLeast(pos + 1_000)
                fuelAt += at
                pos = at
                left = fullKm * 1000
                if (fullKm <= reserveKm + 30) break // bak za mały na sensowny plan
            }
        }
        // przerwy co ok. 2 h – pomiń, gdy w pobliżu (25 km) i tak jest tankowanie
        var t = 2 * 3600.0
        var nb = 0
        while (t < travelS - 20 * 60 && nb < 6) {
            val target = t * speed
            if (fuelAt.none { abs(it - target) < 25_000 }) {
                wins += AlongRoute.Window(target, 30_000.0, 20_000.0, "rest"); meta += "break" to (t / 3600).roundToInt()
                nb++
            }
            t += 2 * 3600.0
        }
        if (wins.isEmpty()) return@withContext Result(emptyList(), totalKm, fuel, if (rangeKm != null) fullKm else null, fullGuess)
        val fuelIdx = meta.indices.filter { meta[it].first == "fuel" }

        // kraje w oknach tankowania (co ~8 km) → które źródła cen zapytać i w jakich punktach
        val perCountry = HashMap<String, ArrayList<Pair<Double, Double>>>()
        for (i in fuelIdx) {
            val w = wins[i]
            val lo = (w.targetM - w.backM).coerceAtLeast(0.0)
            val hi = (w.targetM + w.fwdM).coerceAtMost(total)
            var m = lo
            var lastCenter = -1e12
            while (m <= hi) {
                val k = cum.indexOfFirst { it >= m }.let { if (it < 0) n - 1 else it }
                val iso = Countries.at(ctx, lats[k], lons[k])
                if (iso != null && iso in StationPrices.stationCountries) {
                    val step = when (iso) { "DE" -> 40_000.0; "FR" -> 25_000.0; "IT" -> 18_000.0; "AT" -> 15_000.0; else -> 30_000.0 }
                    val list = perCountry.getOrPut(iso) { ArrayList() }
                    if (m - lastCenter >= step || list.isEmpty()) { list += lats[k] to lons[k]; lastCenter = m }
                }
                m += 8_000.0
            }
        }
        // równolegle: stacje z OSM (jedno zapytanie) + ceny na stacjach w krajach z danymi + kurs euro + średnie UE
        val (hits, priced, rate) = coroutineScope {
            val osm = async {
                AlongRoute.findAll(lats, lons, cum, wins, if (fuelIdx.isNotEmpty()) 8 else 3, tomtomKey, allowTomTom, fuelTag = StationPrices.osmTag(fuel))
            }
            val px = perCountry.map { (iso, centers) -> async { iso to StationPrices.stations(iso, fuel, centers, tkKey) } }
            val eur = async { if (fuelIdx.isNotEmpty()) StationPrices.eurPln(ctx) else null }
            val eu = async { if (fuelIdx.isNotEmpty()) EuFuel.refresh(ctx) }
            eu.await()
            Triple(osm.await(), px.awaitAll(), eur.await())
        }
        val deOk = priced.firstOrNull { it.first == "DE" }?.let { it.second != null }
        val allPx = priced.flatMap { it.second.orEmpty() }

        val groups = wins.indices.map { i ->
            val (kind, h) = meta[i]
            var opts = hits[i].map { x ->
                Opt(
                    Place(x.name, if (x.rest) "Miejsce na przerwę" else "Stacja paliw", x.lat, x.lon),
                    x.atM / 1000, departMs + (x.atM / speed * 1000).toLong(), x.offM, x.rest,
                    cheap = !x.rest && StationPrices.cheapBrand(x.brand + " " + x.name),
                )
            }
            if (kind == "fuel") {
                val w = wins[i]
                val lo = (w.targetM - w.backM).coerceAtLeast(0.0)
                val hi = (w.targetM + w.fwdM).coerceAtMost(total)
                val list = opts.toMutableList()
                // ceny na stacjach: dopisz do stacji z OSM (do 150 m) albo dodaj stację, której w OSM nie było
                for (t2 in allPx) {
                    val (off, at) = AlongRoute.nearRoute(t2.lat, t2.lon, lats, lons, cum, lo, hi)
                    if (off > 1200 || at < lo - 500 || at > hi + 500) continue
                    val pln = if (t2.cur == "PLN") t2.price else rate?.let { t2.price * it }
                    val j = list.indexOfFirst { o -> o.src != "station" && Geo.dist(o.place.lat, o.place.lon, t2.lat, t2.lon) < 150 }
                    if (j >= 0) list[j] = list[j].copy(price = t2.price, cur = t2.cur, pln = pln, open = t2.open, src = "station")
                    else list += Opt(
                        Place(t2.name, "Stacja paliw", t2.lat, t2.lon), at / 1000, departMs + (at / speed * 1000).toLong(), off, false,
                        price = t2.price, cur = t2.cur, pln = pln, open = t2.open, src = "station",
                    )
                }
                // bez ceny na stacji: Polska – cena maksymalna, reszta UE – średnia w kraju
                for (k in list.indices) {
                    val o = list[k]
                    val iso = Countries.at(ctx, o.place.lat, o.place.lon)
                    if (o.src == "station") { list[k] = o.copy(iso = iso); continue }
                    list[k] = when {
                        iso == "PL" && plMax != null -> o.copy(price = plMax, cur = "PLN", pln = plMax, src = "max", iso = iso)
                        else -> EuFuel.price(iso, fuel)?.let { e -> o.copy(price = e, cur = "EUR", pln = rate?.let { e * it }, src = "avg", iso = iso) } ?: o.copy(iso = iso)
                    }
                }
                opts = sortByPrice(list).take(4)
            }
            Group(kind, h, wins[i].targetM / 1000, opts, if (kind == "fuel") h else 1)
        }
        val needDe = perCountry.containsKey("DE") && tkKey.isBlank() && fuel != FuelType.LPG
        val res = Result(groups, totalKm, fuel, fullKm, fullGuess, needDe, deOk)
        synchronized(this@RoutePlan) { cacheKey = key; cacheTotal = total; cacheVal = res }
        res
    }

    /**
     * Najtańsze (w złotych) pierwsze; przy tej samej cenie (co 5 gr) – otwarte, cena ze stacji przed szacunkiem,
     * tańsza sieć, potem kolejność z OSM (lepiej położone).
     */
    fun sortByPrice(list: List<Opt>): List<Opt> {
        val order = HashMap<Opt, Int>()
        list.forEachIndexed { k, o -> order.putIfAbsent(o, k) }
        return list.sortedWith(
            compareBy<Opt>(
                { if (it.open) 0 else 1 },
                { it.pln?.let { p -> (p * 20).roundToInt() } ?: Int.MAX_VALUE },
                { if (it.src == "station") 0 else 1 },
                { if (it.cheap) 0 else 1 },
                { order[it] ?: 999 },
            ),
        )
    }

    /** Pełny bak w km (pojemność / średnie spalanie), gdy wiadomo. */
    fun fullRangeKm(tankL: Double, avgL100: Double?): Double? {
        val avg = avgL100 ?: return null
        if (tankL <= 0 || avg < 2) return null
        return tankL / avg * 100
    }

    /** Ile zasięgu zostanie przy postoju na [atKm] km (dla grupy tankowania nr n – po poprzednim pełnym baku). */
    fun rangeLeftAt(res: Result, rangeKm: Double, atKm: Double): Double {
        var lastFillKm = 0.0
        var left = rangeKm
        for (g in res.groups) {
            if (g.kind != "fuel" || g.atKm >= atKm) continue
            lastFillKm = g.options.firstOrNull()?.atKm ?: g.atKm
            left = res.fullKm ?: rangeKm
        }
        return left - (atKm - lastFillKm)
    }

    /** Opis ceny: „6,29 zł/l” albo „1,72 € (7,35 zł)/l”. */
    fun priceText(o: Opt): String? {
        val p = o.price ?: return null
        val pl = java.util.Locale("pl")
        return if (o.cur == "PLN") String.format(pl, "%.2f zł/l", p)
        else String.format(pl, "%.3f €", p) + (o.pln?.let { String.format(pl, " (%.2f zł)", it) } ?: "") + "/l"
    }
}
