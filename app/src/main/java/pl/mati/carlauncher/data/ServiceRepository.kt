package pl.mati.carlauncher.data

import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.roundToInt

/** Przebieg + pozycje serwisowe z interwałem w km i/lub miesiącach. */
object ServiceRepository {

    data class Item(
        val id: String,
        val name: String,
        val intervalKm: Int,      // 0 = bez limitu km
        val intervalMonths: Int,  // 0 = bez limitu czasu
        val lastKm: Int,          // -1 = nieznane
        val lastDate: Long,       // 0 = nieznane
    ) {
        fun toJson(): JSONObject = JSONObject().put("id", id).put("n", name).put("ik", intervalKm)
            .put("im", intervalMonths).put("lk", lastKm).put("ld", lastDate)

        companion object {
            fun from(o: JSONObject) = Item(
                o.getString("id"), o.getString("n"), o.getInt("ik"), o.getInt("im"), o.getInt("lk"), o.getLong("ld"),
            )
        }
    }

    data class Due(val item: Item, val kmLeft: Int?, val daysLeft: Int?, val fraction: Float) {
        val known: Boolean get() = kmLeft != null || daysLeft != null
        val overdue: Boolean get() = (kmLeft != null && kmLeft <= 0) || (daysLeft != null && daysLeft <= 0)
        val soon: Boolean get() = !overdue && ((kmLeft != null && kmLeft <= 1000) || (daysLeft != null && daysLeft <= 30))
    }

    val items = MutableStateFlow<List<Item>>(emptyList())
    val odometerKm = MutableStateFlow(0.0)
    val odometerSet = MutableStateFlow(false)

    private var loaded = false
    private const val DAY = 86_400_000L

    fun load() {
        if (loaded) return
        val sp = Prefs.store ?: return
        loaded = true
        odometerKm.value = sp.getFloat("odo_km", 0f).toDouble()
        odometerSet.value = sp.getBoolean("odo_set", false)
        val str = sp.getString("service_items", null)
        items.value = if (str == null) defaults() else try {
            val arr = JSONArray(str)
            (0 until arr.length()).map { Item.from(arr.getJSONObject(it)) }
        } catch (_: Exception) {
            defaults()
        }
    }

    private fun defaults(): List<Item> {
        val diesel = Prefs.profile.value.isDiesel
        return listOfNotNull(
            Item("oil", "Olej silnikowy i filtr", 15000, 12, -1, 0),
            Item("timing", "Rozrząd z pompą wody", 120000, 60, -1, 0),
            Item("fuel", if (diesel) "Filtr paliwa" else "Świece zapłonowe", if (diesel) 30000 else 60000, if (diesel) 24 else 48, -1, 0),
            Item("air", "Filtr powietrza", 30000, 24, -1, 0),
            Item("cabin", "Filtr kabinowy", 15000, 12, -1, 0),
            Item("brake", "Płyn hamulcowy", 0, 24, -1, 0),
            Item("inspection", "Przegląd techniczny", 0, 12, -1, 0),
            Item("insurance", "Ubezpieczenie OC", 0, 12, -1, 0),
        )
    }

    fun save() {
        val arr = JSONArray()
        items.value.forEach { arr.put(it.toJson()) }
        Prefs.store?.edit()
            ?.putString("service_items", arr.toString())
            ?.putFloat("odo_km", odometerKm.value.toFloat())
            ?.putBoolean("odo_set", odometerSet.value)
            ?.apply()
    }

    fun addMeters(m: Double) {
        if (odometerSet.value && m > 0) odometerKm.value += m / 1000.0
    }

    fun setOdometer(km: Int) {
        odometerKm.value = km.toDouble()
        odometerSet.value = true
        save()
    }

    fun update(item: Item) {
        items.value = items.value.map { if (it.id == item.id) item else it }
        save()
    }

    fun markDone(id: String) {
        val km = if (odometerSet.value) odometerKm.value.roundToInt() else -1
        items.value = items.value.map { if (it.id == id) it.copy(lastKm = km, lastDate = System.currentTimeMillis()) else it }
        save()
    }

    fun due(item: Item, now: Long = System.currentTimeMillis()): Due {
        val odo = odometerKm.value
        val kmLeft = if (item.intervalKm > 0 && item.lastKm >= 0 && odometerSet.value) (item.lastKm + item.intervalKm - odo).roundToInt() else null
        val daysLeft = if (item.intervalMonths > 0 && item.lastDate > 0) {
            ((item.lastDate + (item.intervalMonths * 30.44 * DAY).toLong() - now) / DAY).toInt()
        } else null
        val fKm = if (kmLeft != null) 1f - kmLeft.toFloat() / item.intervalKm else 0f
        val fDays = if (daysLeft != null) 1f - daysLeft.toFloat() / (item.intervalMonths * 30.44f) else 0f
        return Due(item, kmLeft, daysLeft, max(fKm, fDays).coerceIn(0f, 1f))
    }

    fun dueList(): List<Due> = items.value.map { due(it) }
        .sortedWith(compareByDescending<Due> { it.overdue }.thenByDescending { it.soon }.thenByDescending { it.fraction })

    fun overdue(): List<Due> = dueList().filter { it.overdue }

    /** Wywoływane przy starcie: przypomnienie w centrum sterowania. */
    fun checkReminders() {
        load()
        val urgent = dueList().filter { it.overdue || it.soon }
        if (urgent.isEmpty()) {
            NotificationRepository.removeSystem("service")
            return
        }
        val first = urgent.first()
        val more = if (urgent.size > 1) " (+${urgent.size - 1})" else ""
        NotificationRepository.postSystem(
            "service",
            if (first.overdue) "Serwis po terminie" else "Zbliża się serwis",
            "${first.item.name}: ${describe(first)}$more",
            "service",
        )
    }

    fun describe(d: Due): String {
        if (!d.known) return "ustaw, kiedy ostatnio"
        val parts = mutableListOf<String>()
        d.kmLeft?.let { parts += if (it <= 0) "${-it} km po terminie" else "za ${"%,d".format(it).replace(',', ' ')} km" }
        d.daysLeft?.let { parts += if (it <= 0) "${-it} dni po terminie" else if (it < 60) "za $it dni" else "za ${(it / 30.44).roundToInt()} mies." }
        return parts.joinToString(" lub ")
    }
}
