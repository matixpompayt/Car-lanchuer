package pl.mati.carlauncher.data

import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Ulubione i ostatnie cele nawigacji (zapisywane w ustawieniach → trafiają do kopii zapasowej). */
object PlacesRepository {

    val favorites = MutableStateFlow<List<RouteRepository.Place>>(emptyList())
    val recents = MutableStateFlow<List<RouteRepository.Place>>(emptyList())

    private var loaded = false
    private const val MAX_RECENTS = 8

    fun load() {
        if (loaded) return
        val sp = Prefs.store ?: return
        loaded = true
        favorites.value = decode(sp.getString("fav_places", null))
        recents.value = decode(sp.getString("recent_places", null))
    }

    private fun same(a: RouteRepository.Place, b: RouteRepository.Place) =
        RouteRepository.dist(a.lat, a.lon, b.lat, b.lon) < 40

    fun isFavorite(p: RouteRepository.Place) = favorites.value.any { same(it, p) }

    fun toggleFavorite(p: RouteRepository.Place): Boolean {
        load()
        val fav = isFavorite(p)
        favorites.value = if (fav) favorites.value.filterNot { same(it, p) } else favorites.value + p
        save()
        return !fav
    }

    fun removeFavorite(p: RouteRepository.Place) {
        favorites.value = favorites.value.filterNot { same(it, p) }
        save()
    }

    fun addRecent(p: RouteRepository.Place) {
        load()
        if (p.name == "Dom" || p.name == "Praca") return
        recents.value = (listOf(p) + recents.value.filterNot { same(it, p) }).take(MAX_RECENTS)
        save()
    }

    fun removeRecent(p: RouteRepository.Place) {
        recents.value = recents.value.filterNot { same(it, p) }
        save()
    }

    fun clearRecents() {
        recents.value = emptyList()
        save()
    }

    private fun save() {
        Prefs.store?.edit()
            ?.putString("fav_places", encode(favorites.value))
            ?.putString("recent_places", encode(recents.value))
            ?.apply()
    }

    private fun encode(list: List<RouteRepository.Place>): String {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("n", it.name).put("d", it.detail).put("lat", it.lat).put("lon", it.lon)) }
        return a.toString()
    }

    private fun decode(raw: String?): List<RouteRepository.Place> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                RouteRepository.Place(o.optString("n"), o.optString("d"), o.getDouble("lat"), o.getDouble("lon"))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
