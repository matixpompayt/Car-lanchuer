package pl.mati.carlauncher.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID

/**
 * Tankowania (w radiu albo z CAR OS Connect): średnie spalanie z tankowań „do pełna”,
 * koszt kilometra, szacowany zasięg i przypomnienie o tankowaniu.
 */
object FuelRepository {

    data class Fill(
        val id: String,
        val time: Long,
        val odoKm: Int,
        val liters: Double,
        val cost: Double,
        val full: Boolean,
    ) {
        val pricePerL get() = if (liters > 0) cost / liters else 0.0

        fun toJson(): JSONObject = JSONObject().put("id", id).put("t", time).put("o", odoKm).put("l", liters).put("c", cost).put("f", full)

        companion object {
            fun from(o: JSONObject) = Fill(
                o.optString("id").ifBlank { UUID.randomUUID().toString() }, o.optLong("t"), o.optInt("o"),
                o.optDouble("l", 0.0), o.optDouble("c", 0.0), o.optBoolean("f", true),
            )
        }
    }

    data class Stats(
        val avgL100: Double?,      // średnie spalanie (z tankowań do pełna)
        val lastL100: Double?,     // ostatni odcinek pełny → pełny
        val costPerKm: Double?,
        val monthCost: Double,
        val monthLiters: Double,
        val lastPrice: Double?,
        val fuelLeftL: Double?,
        val rangeKm: Double?,
        val estimated: Boolean,    // zasięg liczony ze spalania domyślnego (za mało tankowań)
        /** Ile w baku – z czujnika auta (dekoder CAN radia), nie z tankowań. */
        val fromCan: Boolean = false,
        /** Zasięg przed korektą (do kalibracji z licznikiem). */
        val rangeRawKm: Double? = null,
        /** Zasięg podany przez samo auto (CAN) – korekta nie dotyczy. */
        val rangeFromCar: Boolean = false,
    )

    val fills = MutableStateFlow<List<Fill>>(emptyList()) // najnowsze pierwsze
    val stats = MutableStateFlow(Stats(null, null, null, 0.0, 0.0, null, null, null, true))
    val tankL = MutableStateFlow(60)
    /** Korekta zasięgu (mnożnik, 1 = bez) – żeby zgadzał się z licznikiem auta. */
    val rangeK = MutableStateFlow(1.0)

    private var loaded = false
    private var lastReminder = 0L

    fun load() {
        if (loaded) return
        loaded = true
        val sp = Prefs.store ?: return
        tankL.value = sp.getInt("fuel_tank", 60)
        rangeK.value = sp.getFloat("fuel_rk", 1f).toDouble().coerceIn(0.5, 1.5)
        watchCan()
        try {
            val a = JSONArray(sp.getString("fuel_fills", "[]"))
            fills.value = (0 until a.length()).map { Fill.from(a.getJSONObject(it)) }.sortedByDescending { it.odoKm * 10_000_000_000L + it.time / 1000 }
        } catch (_: Exception) {
        }
        recompute()
    }

    private fun save() {
        val a = JSONArray()
        fills.value.take(300).forEach { a.put(it.toJson()) }
        Prefs.store?.edit()?.putString("fuel_fills", a.toString())?.putInt("fuel_tank", tankL.value)
            ?.putFloat("fuel_rk", rangeK.value.toFloat())?.apply()
        recompute()
        ConnectRepository.publishStatusSoon()
    }

    fun add(f: Fill) {
        load()
        fills.value = (fills.value.filter { it.id != f.id } + f).sortedByDescending { it.odoKm * 10_000_000_000L + it.time / 1000 }
        // przebieg z licznika przy tankowaniu poprawia licznik serwisowy
        if (f.odoKm > 0 && (!ServiceRepository.odometerSet.value || f.odoKm >= ServiceRepository.odometerKm.value - 50)) {
            if (System.currentTimeMillis() - f.time < 6 * 3_600_000L) ServiceRepository.setOdometer(f.odoKm)
        }
        save()
    }

    fun remove(id: String) {
        load()
        fills.value = fills.value.filter { it.id != id }
        save()
    }

    fun setTank(l: Int) {
        tankL.value = l.coerceIn(20, 150)
        save()
    }

    /** Korekta zasięgu jako mnożnik (0,5–1,5; 1 = bez korekty). */
    fun setRangeK(k: Double) {
        load()
        rangeK.value = (Math.round(k.coerceIn(0.5, 1.5) * 1000) / 1000.0)
        save()
    }

    /** Kalibracja z licznikiem: auto pokazuje [clusterKm] – mnożnik tak, żeby CAR OS pokazywał to samo. Zwraca mnożnik albo null. */
    fun calibrate(clusterKm: Int): Double? {
        load()
        try { recompute() } catch (_: Exception) {}
        val raw = stats.value.rangeRawKm?.takeIf { it > 5 } ?: return null
        if (clusterKm !in 1..3000) return null
        setRangeK(clusterKm / raw)
        return rangeK.value
    }

    /** Spalanie, koszty, zasięg – po każdej zmianie i co jakiś czas (przebieg rośnie). */
    fun recompute() {
        // przebieg musi być już wczytany – inaczej zasięg wychodził pusty (start radia: paliwo przed serwisem)
        try { ServiceRepository.load() } catch (_: Exception) {}
        val list = fills.value.sortedBy { it.odoKm }
        // odcinki pełny → pełny
        var totalL = 0.0
        var totalKm = 0.0
        var lastL100: Double? = null
        var prevFull: Fill? = null
        var litersSince = 0.0
        for (f in list) {
            if (prevFull == null) {
                if (f.full) prevFull = f
                continue
            }
            litersSince += f.liters
            if (f.full) {
                val km = (f.odoKm - prevFull.odoKm).toDouble()
                if (km in 30.0..3000.0 && litersSince > 0) {
                    totalL += litersSince
                    totalKm += km
                    lastL100 = litersSince / km * 100
                }
                prevFull = f
                litersSince = 0.0
            }
        }
        val avg = if (totalKm >= 100) totalL / totalKm * 100 else null
        // koszt na km: wszystko po pierwszym tankowaniu / przejechane od niego
        val first = list.firstOrNull()
        val last = list.lastOrNull()
        val costKm = if (first != null && last != null && last.odoKm - first.odoKm >= 200) {
            list.drop(1).sumOf { it.cost } / (last.odoKm - first.odoKm)
        } else null
        val monthStart = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
        }.timeInMillis
        val month = list.filter { it.time >= monthStart }
        // ile zostało w baku: od ostatniego „do pełna” + dolane później − spalone wg przebiegu
        val lastFull = list.lastOrNull { it.full }
        val use = avg ?: (if (Prefs.profile.value.isDiesel) 6.5 else 8.0)
        // bez ustawionego licznika: przebieg z ostatniego tankowania + trasy przejechane od tamtej pory
        val odo = if (ServiceRepository.odometerSet.value) ServiceRepository.odometerKm.value
        else lastFull?.let { lf -> lf.odoKm + (try { TripRepository.summary(lf.time).distanceM } catch (_: Exception) { 0.0 }) / 1000.0 }
        var left: Double? = null
        var range: Double? = null
        var fromCar = false
        if (lastFull != null && odo != null) {
            val added = list.filter { it.odoKm > lastFull.odoKm }.sumOf { it.liters }
            val driven = (odo - lastFull.odoKm).coerceAtLeast(0.0)
            left = (tankL.value + added - driven * use / 100).coerceIn(0.0, tankL.value.toDouble())
            range = left / use * 100
        }
        // poziom paliwa z czujnika auta (CAN) – dokładniejszy niż szacunek z tankowań
        try { autoMapFuel(left) } catch (_: Exception) {}
        val can = canLevelL()
        if (can != null) {
            left = can
            val carRange = Vehicle.data.value.rangeKm?.takeIf { it in 5..2500 }?.toDouble()
            fromCar = carRange != null
            range = carRange ?: (can / use * 100)
        }
        // korekta z licznika (zasięg podany przez samo auto – bez korekty, to już wartość z licznika)
        val raw = range
        if (range != null && !fromCar) range *= rangeK.value
        stats.value = Stats(avg, lastL100, costKm, month.sumOf { it.cost }, month.sumOf { it.liters }, last?.pricePerL, left, range, avg == null, can != null, raw, fromCar)
        remind()
    }

    /** Litry w baku z dekodera CAN radia (pole „Paliwo w baku” albo „Paliwo” w % pojemności), null – brak. */
    fun canLevelL(): Double? {
        val d = Vehicle.data.value
        val tank = tankL.value.toDouble()
        d.fuelL?.takeIf { it in 1..250 }?.let { return it.toDouble().coerceAtMost(tank) }
        d.fuelPct?.takeIf { it in 1..100 }?.let { return tank * it / 100.0 }
        return null
    }

    private var canWatch = false

    /** Zmiana poziomu paliwa z CAN → przelicz zasięg (i wyślij do telefonu). */
    private fun watchCan() {
        if (canWatch) return
        canWatch = true
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default).launch {
            var lastSent = Double.NaN
            Vehicle.data.map { it.fuelL to it.fuelPct }.distinctUntilChanged().collect {
                try { recompute() } catch (_: Exception) {}
                val l = canLevelL() ?: return@collect
                // do telefonu najwyżej przy zmianie o 2 l (poziom faluje w czasie jazdy)
                if (lastSent.isNaN() || kotlin.math.abs(l - lastSent) >= 2.0) {
                    lastSent = l
                    ConnectRepository.publishStatusSoon()
                }
            }
        }
    }

    private var autoKind = ""
    private var autoHits = 0

    /**
     * Pole „fuel_level” z MainApp (TopWay) nie ma stałej jednostki – zależnie od przejściówki CAN to litry,
     * dziesiąte litra albo procent baku. Gdy nic nie jest przypisane, porównaj z szacunkiem z tankowań
     * i po 3 zgodnych odczytach przypisz sam (zawsze można zmienić w „Dane z radia”).
     */
    private fun autoMapFuel(estLeft: Double?) {
        val m = CanRepository.mappings.value
        if (m.containsKey(CanRepository.Field.FUEL) || m.containsKey(CanRepository.Field.FUEL_L)) return
        val est = estLeft ?: return
        val sig = CanRepository.signals["ts:base/fuel_level"] ?: return
        if (System.currentTimeMillis() - sig.lastUpdate > 60_000) return
        val v = sig.value
        if (v <= 0 || v > 2500) return
        val tank = tankL.value.toDouble()
        val cands = listOf(
            Triple("l", 1.0, v), Triple("l", 0.1, v * 0.1), Triple("%", 1.0, if (v <= 100) tank * v / 100 else Double.NaN),
        ).filter { !it.third.isNaN() && it.third <= tank * 1.05 }
        val best = cands.minByOrNull { kotlin.math.abs(it.third - est) } ?: return
        val err = kotlin.math.abs(best.third - est)
        val second = cands.filter { it != best }.minOfOrNull { kotlin.math.abs(it.third - est) } ?: Double.MAX_VALUE
        val kind = if (err < tank * 0.2 && err < second * 0.6) best.first + best.second else ""
        if (kind.isEmpty() || (autoKind.isNotEmpty() && kind != autoKind)) { autoKind = ""; autoHits = 0; return }
        autoKind = kind
        if (++autoHits < 3) return
        CanRepository.assign(
            if (best.first == "l") CanRepository.Field.FUEL_L else CanRepository.Field.FUEL,
            CanRepository.Mapping("ts:base/fuel_level", best.second),
        )
    }

    private fun remind() {
        val r = stats.value.rangeKm ?: return
        if (r > 80) {
            NotificationRepository.removeSystem("fuel")
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastReminder < 3 * 3_600_000L) return
        lastReminder = now
        NotificationRepository.postSystem("fuel", "Czas zatankować", "Zostało ok. ${r.toInt()} km jazdy", "fuel")
    }

    // ---------- CAR OS Connect ----------

    fun toJson(): JSONObject {
        load()
        // zawsze świeży zasięg (przebieg rośnie w czasie jazdy)
        try { recompute() } catch (_: Exception) {}
        val s = stats.value
        val a = JSONArray()
        fills.value.take(30).forEach { a.put(it.toJson()) }
        return JSONObject().put("tank", tankL.value).put("fills", a)
            .put("avg", s.avgL100 ?: JSONObject.NULL).put("range", s.rangeKm ?: JSONObject.NULL)
            .put("ckm", s.costPerKm ?: JSONObject.NULL).put("est", s.estimated)
            .put("use", s.avgL100 ?: (if (Prefs.profile.value.isDiesel) 6.5 else 8.0))
            .put("left", s.fuelLeftL ?: JSONObject.NULL)
            // protokół 2: skąd poziom paliwa – "can" (czujnik auta) albo "est" (szacunek z tankowań)
            .put("src", if (s.fromCan) "can" else "est")
            // korekta zasięgu: mnożnik, zasięg przed korektą, czy zasięg podało samo auto
            .put("rk", rangeK.value).put("rraw", s.rangeRawKm ?: JSONObject.NULL).put("rcar", s.rangeFromCar)
    }
}
