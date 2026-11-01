package pl.mati.carlauncher.data

import kotlinx.coroutines.flow.MutableStateFlow
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
    )

    val fills = MutableStateFlow<List<Fill>>(emptyList()) // najnowsze pierwsze
    val stats = MutableStateFlow(Stats(null, null, null, 0.0, 0.0, null, null, null, true))
    val tankL = MutableStateFlow(60)

    private var loaded = false
    private var lastReminder = 0L

    fun load() {
        if (loaded) return
        loaded = true
        val sp = Prefs.store ?: return
        tankL.value = sp.getInt("fuel_tank", 60)
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
        Prefs.store?.edit()?.putString("fuel_fills", a.toString())?.putInt("fuel_tank", tankL.value)?.apply()
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

    /** Spalanie, koszty, zasięg – po każdej zmianie i co jakiś czas (przebieg rośnie). */
    fun recompute() {
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
        val odo = if (ServiceRepository.odometerSet.value) ServiceRepository.odometerKm.value else null
        var left: Double? = null
        var range: Double? = null
        if (lastFull != null && odo != null) {
            val added = list.filter { it.odoKm > lastFull.odoKm }.sumOf { it.liters }
            val driven = (odo - lastFull.odoKm).coerceAtLeast(0.0)
            left = (tankL.value + added - driven * use / 100).coerceIn(0.0, tankL.value.toDouble())
            range = left / use * 100
        }
        stats.value = Stats(avg, lastL100, costKm, month.sumOf { it.cost }, month.sumOf { it.liters }, last?.pricePerL, left, range, avg == null)
        remind()
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
        val s = stats.value
        val a = JSONArray()
        fills.value.take(30).forEach { a.put(it.toJson()) }
        return JSONObject().put("tank", tankL.value).put("fills", a)
            .put("avg", s.avgL100 ?: JSONObject.NULL).put("range", s.rangeKm ?: JSONObject.NULL)
            .put("ckm", s.costPerKm ?: JSONObject.NULL).put("est", s.estimated)
    }
}
