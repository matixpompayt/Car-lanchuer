package pl.mati.caros.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Kraj dla punktu – granice państw Europy z Natural Earth (uproszczone, dokładność kilkaset metrów),
 * zapisane w assets/europe.json. Działa bez internetu.
 */
object Countries {
    private class Poly(val rings: List<DoubleArray>, val minX: Double, val minY: Double, val maxX: Double, val maxY: Double)

    @Volatile private var data: Map<String, List<Poly>>? = null

    val names = mapOf(
        "PL" to "Polska", "DE" to "Niemcy", "CZ" to "Czechy", "SK" to "Słowacja", "AT" to "Austria", "HU" to "Węgry",
        "LT" to "Litwa", "LV" to "Łotwa", "EE" to "Estonia", "FR" to "Francja", "IT" to "Włochy", "ES" to "Hiszpania",
        "PT" to "Portugalia", "NL" to "Holandia", "BE" to "Belgia", "LU" to "Luksemburg", "DK" to "Dania", "SE" to "Szwecja",
        "FI" to "Finlandia", "IE" to "Irlandia", "SI" to "Słowenia", "HR" to "Chorwacja", "RO" to "Rumunia", "BG" to "Bułgaria",
        "GR" to "Grecja", "CY" to "Cypr", "MT" to "Malta", "CH" to "Szwajcaria", "NO" to "Norwegia", "GB" to "Wielka Brytania",
        "UA" to "Ukraina", "BY" to "Białoruś", "MD" to "Mołdawia", "RS" to "Serbia", "BA" to "Bośnia i Hercegowina",
        "ME" to "Czarnogóra", "MK" to "Macedonia Płn.", "AL" to "Albania", "XK" to "Kosowo", "IS" to "Islandia",
        "LI" to "Liechtenstein", "AD" to "Andora", "MC" to "Monako", "SM" to "San Marino", "RU" to "Rosja", "TR" to "Turcja",
    )

    fun name(iso: String?) = iso?.let { names[it] ?: it } ?: "?"

    private fun load(ctx: Context): Map<String, List<Poly>> {
        data?.let { return it }
        synchronized(this) {
            data?.let { return it }
            val m = HashMap<String, List<Poly>>()
            try {
                val js = JSONObject(ctx.assets.open("europe.json").bufferedReader().use { it.readText() })
                for (iso in js.keys()) {
                    val polys = js.getJSONArray(iso)
                    val list = ArrayList<Poly>()
                    for (i in 0 until polys.length()) {
                        val rs = polys.getJSONArray(i)
                        val rings = ArrayList<DoubleArray>()
                        var minX = 1e9; var minY = 1e9; var maxX = -1e9; var maxY = -1e9
                        for (k in 0 until rs.length()) {
                            val a = rs.getJSONArray(k)
                            val d = DoubleArray(a.length()) { a.getDouble(it) }
                            if (k == 0) for (j in d.indices step 2) {
                                minX = minOf(minX, d[j]); maxX = maxOf(maxX, d[j]); minY = minOf(minY, d[j + 1]); maxY = maxOf(maxY, d[j + 1])
                            }
                            rings += d
                        }
                        if (rings.isNotEmpty()) list += Poly(rings, minX, minY, maxX, maxY)
                    }
                    m[iso] = list
                }
            } catch (_: Exception) {
            }
            data = m
            return m
        }
    }

    private fun inRing(x: Double, y: Double, r: DoubleArray): Boolean {
        var inside = false
        val n = r.size / 2
        var j = n - 1
        for (i in 0 until n) {
            val xi = r[i * 2]; val yi = r[i * 2 + 1]
            val xj = r[j * 2]; val yj = r[j * 2 + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    /** Kod kraju (ISO, np. „PL”) dla punktu albo null (morze / poza Europą). */
    fun at(ctx: Context, lat: Double, lon: Double): String? {
        val m = load(ctx)
        for ((iso, polys) in m) for (p in polys) {
            if (lon < p.minX || lon > p.maxX || lat < p.minY || lat > p.maxY) continue
            if (!inRing(lon, lat, p.rings[0])) continue
            if (p.rings.drop(1).any { inRing(lon, lat, it) }) continue
            return iso
        }
        // przy samej granicy / na wybrzeżu (uproszczone granice) – najbliższy kraj w promieniu ~3 km
        val d = 0.03
        for (dx in listOf(-d, d, 0.0)) for (dy in listOf(0.0, -d, d)) {
            if (dx == 0.0 && dy == 0.0) continue
            for ((iso, polys) in m) for (p in polys) {
                if (lon + dx < p.minX || lon + dx > p.maxX || lat + dy < p.minY || lat + dy > p.maxY) continue
                if (inRing(lon + dx, lat + dy, p.rings[0])) return iso
            }
        }
        return null
    }
}

/**
 * Średnie ceny paliw w krajach UE (Komisja Europejska, Weekly Oil Bulletin, co tydzień) – plik docs/fuel_eu.json
 * w repo CAR OS (aktualizowany przez GitHub Actions). EUR za litr: pb95, on, lpg.
 */
object EuFuel {
    private const val URL_JSON = "https://raw.githubusercontent.com/matixpompayt/Car-lanchuer/main/docs/fuel_eu.json"
    val prices = MutableStateFlow<Map<String, Map<String, Double>>>(emptyMap())
    val date = MutableStateFlow<String?>(null)
    private var lastFetch = 0L
    private fun cache(ctx: Context) = File(ctx.filesDir, "fuel_eu.json")

    private fun parse(text: String) {
        val o = JSONObject(text)
        val p = o.optJSONObject("prices") ?: return
        val m = HashMap<String, Map<String, Double>>()
        for (iso in p.keys()) {
            val c = p.getJSONObject(iso)
            m[iso] = c.keys().asSequence().associateWith { c.getDouble(it) }
        }
        prices.value = m
        // JSON null → optString daje "null"
        date.value = o.optString("date").takeIf { it.isNotBlank() && it != "null" }
    }

    /** Pobiera plik (najwyżej co 6 h); przy błędzie zostaje kopia z telefonu. */
    suspend fun refresh(ctx: Context) = withContext(Dispatchers.IO) {
        if (prices.value.isEmpty()) try { cache(ctx).takeIf { it.exists() }?.readText()?.let { parse(it) } } catch (_: Exception) {}
        if (System.currentTimeMillis() - lastFetch < 6 * 3600_000L && prices.value.isNotEmpty()) return@withContext
        try {
            val c = URL(URL_JSON + "?t=" + System.currentTimeMillis() / 3_600_000).openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 12000
            c.setRequestProperty("User-Agent", AlongRoute.userAgent)
            val text = try {
                if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
                c.inputStream.bufferedReader().use { it.readText() }
            } finally { c.disconnect() }
            parse(text)
            cache(ctx).writeText(text)
            lastFetch = System.currentTimeMillis()
        } catch (_: Exception) {
        }
    }

    fun key(f: FuelType) = when (f) {
        FuelType.ON -> "on"
        FuelType.LPG -> "lpg"
        else -> "pb95"
    }

    /** Średnia cena (EUR/l) wybranego paliwa w kraju; dla 98 – średnia 95 (+ ok. 8%). */
    fun price(iso: String?, f: FuelType): Double? {
        val c = prices.value[iso ?: return null] ?: return null
        val v = c[key(f)] ?: return null
        return if (f == FuelType.PB98) v * 1.08 else v
    }
}
