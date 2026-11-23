package pl.mati.caros.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Ceny na konkretnych stacjach.
 *
 * Niemcy: Tankerkönig (oficjalne dane MTS-K, aktualne co kilka minut) – potrzebny darmowy klucz.
 * Polska: nie ma publicznego źródła cen na poszczególnych stacjach – jest tylko cena maksymalna / średnia
 * z cen maksymalnych w aplikacji; przy stacjach pokazujemy wtedy podpowiedź „zwykle tańsza sieć”.
 */
object StationPrices {
    /** Stacja z ceną wybranego paliwa ([cur] – waluta, zwykle EUR). */
    class Tk(val name: String, val brand: String, val lat: Double, val lon: Double, val price: Double, val open: Boolean, val cur: String = "EUR")

    /** Kraje z cenami na poszczególnych stacjach (oficjalne dane). */
    val stationCountries = setOf("DE", "FR", "AT", "IT", "ES")

    /**
     * Stacje z cenami w danym kraju wokół punktów. null – źródło nie odpowiedziało.
     * DE – Tankerkönig (klucz), FR – prix-carburants.gouv.fr, AT – E-Control, IT – MIMIT Osservaprezzi, ES – Ministerio (geoportal).
     */
    suspend fun stations(iso: String, fuel: FuelType, centers: List<Pair<Double, Double>>, tk: String): List<Tk>? = when (iso) {
        "DE" -> if (tk.isBlank()) emptyList() else germany(tk, fuel, centers)
        "FR" -> parallel(centers) { (lat, lon) -> france(fuel, lat, lon) }
        "AT" -> parallel(centers) { (lat, lon) -> austria(fuel, lat, lon) }
        "IT" -> parallel(centers) { (lat, lon) -> italy(fuel, lat, lon) }
        "ES" -> spain(fuel, centers)
        else -> emptyList()
    }

    private suspend fun parallel(centers: List<Pair<Double, Double>>, one: (Pair<Double, Double>) -> List<Tk>): List<Tk>? {
        if (centers.isEmpty()) return emptyList()
        val res = coroutineScope { centers.map { c -> async(Dispatchers.IO) { try { one(c) } catch (_: Exception) { null } } }.awaitAll() }
        if (res.all { it == null }) return null
        return res.filterNotNull().flatten().distinctBy { "%.4f,%.4f".format(Locale.US, it.lat, it.lon) }
    }

    private fun cap(s: String) = s.lowercase(Locale.ROOT).split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.ROOT) } }

    /** Francja – oficjalny przepływ „Prix des carburants” (co 10 min), stacje w promieniu 15 km. */
    private fun france(fuel: FuelType, lat: Double, lon: Double): List<Tk> {
        val where = String.format(Locale.US, "within_distance(geom, geom'POINT(%.5f %.5f)', 15km)", lon, lat)
        val url = "https://data.economie.gouv.fr/api/explore/v2.1/catalog/datasets/prix-des-carburants-en-france-flux-instantane-v2/records?limit=100&where=" +
            java.net.URLEncoder.encode(where, "UTF-8")
        val a = JSONObject(http(url)).optJSONArray("results") ?: return emptyList()
        val out = ArrayList<Tk>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val g = o.optJSONObject("geom") ?: continue
            val price = when (fuel) {
                FuelType.ON -> o.optDouble("gazole_prix")
                FuelType.PB95 -> listOf(o.optDouble("sp95_prix"), o.optDouble("e10_prix")).filter { !it.isNaN() && it > 0 }.minOrNull() ?: Double.NaN
                FuelType.PB98 -> o.optDouble("sp98_prix")
                FuelType.LPG -> o.optDouble("gplc_prix")
            }
            if (price.isNaN() || price <= 0.3) continue
            val city = cap(o.optString("ville").trim())
            out += Tk("Station $city".trim(), "", g.optDouble("lat"), g.optDouble("lon"), if (price > 20) price / 1000 else price, true)
        }
        return out
    }

    /** Austria – E-Control Spritpreisrechner (najtańsze stacje w okolicy punktu). */
    private fun austria(fuel: FuelType, lat: Double, lon: Double): List<Tk> {
        val type = when (fuel) { FuelType.ON -> "DIE"; FuelType.LPG -> return emptyList(); else -> "SUP" }
        val url = String.format(Locale.US, "https://api.e-control.at/sprit/1.0/search/gas-stations/by-address?latitude=%.5f&longitude=%.5f&fuelType=%s&includeClosed=false", lat, lon, type)
        val a = org.json.JSONArray(http(url))
        val out = ArrayList<Tk>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val loc = o.optJSONObject("location") ?: continue
            val pr = o.optJSONArray("prices") ?: continue
            var price = Double.NaN
            for (k in 0 until pr.length()) pr.getJSONObject(k).let { if (it.optString("fuelType") == type) price = it.optDouble("amount") }
            if (price.isNaN() || price <= 0.3) continue
            val name = o.optString("name").trim().ifEmpty { "Tankstelle" }
            out += Tk("$name ${loc.optString("city").trim()}".trim(), name, loc.optDouble("latitude"), loc.optDouble("longitude"), price, o.optBoolean("open", true))
        }
        return out
    }

    /** Włochy – MIMIT Osservaprezzi carburanti (wyszukiwanie w obszarze ok. 20 × 20 km). */
    private fun italy(fuel: FuelType, lat: Double, lon: Double): List<Tk> {
        val type = when (fuel) { FuelType.ON -> "2-x"; FuelType.LPG -> "4-x"; else -> "1-x" }
        val d = 0.1
        val pts = org.json.JSONArray()
        listOf(lat - d to lon - d, lat - d to lon + d, lat + d to lon + d, lat + d to lon - d).forEach { (a, b) -> pts.put(JSONObject().put("lat", a).put("lng", b)) }
        val body = JSONObject().put("points", pts).put("fuelType", type).put("priceOrder", "asc").toString()
        val res = JSONObject(http("https://carburanti.mise.gov.it/ospzApi/search/zone", body)).optJSONArray("results") ?: return emptyList()
        val out = ArrayList<Tk>()
        for (i in 0 until res.length()) {
            val o = res.getJSONObject(i)
            val loc = o.optJSONObject("location") ?: continue
            val fuels = o.optJSONArray("fuels") ?: continue
            var best = Double.NaN
            for (k in 0 until fuels.length()) {
                val f = fuels.getJSONObject(k)
                val n = f.optString("name").lowercase(Locale.ROOT)
                val ok = when (fuel) {
                    FuelType.ON -> n.contains("gasolio") || n.contains("diesel")
                    FuelType.LPG -> n.contains("gpl")
                    FuelType.PB98 -> n.contains("benzina") && (n.contains("98") || n.contains("100") || n.contains("plus"))
                    FuelType.PB95 -> n.contains("benzina") && !n.contains("98") && !n.contains("100")
                }
                val p = f.optDouble("price")
                if (ok && !p.isNaN() && p > 0.3 && (best.isNaN() || p < best)) best = p
            }
            if (best.isNaN()) continue
            val brand = o.optString("brand").trim()
            out += Tk(listOf(brand, o.optString("name").trim()).filter { it.isNotEmpty() }.distinct().firstOrNull() ?: "Distributore", brand, loc.optDouble("lat"), loc.optDouble("lng"), best, true)
        }
        return out
    }

    private var esCache: List<Array<Any>>? = null
    private var esAt = 0L

    /** Hiszpania – Ministerio (cała lista stacji, ok. 12 tys., zapamiętana na 1 h; czytana strumieniowo). */
    private suspend fun spain(fuel: FuelType, centers: List<Pair<Double, Double>>): List<Tk>? = withContext(Dispatchers.IO) {
        val col = when (fuel) {
            FuelType.ON -> "Precio Gasoleo A"
            FuelType.PB95 -> "Precio Gasolina 95 E5"
            FuelType.PB98 -> "Precio Gasolina 98 E5"
            FuelType.LPG -> "Precio Gases licuados del petróleo"
        }
        val cols = listOf("Precio Gasoleo A", "Precio Gasolina 95 E5", "Precio Gasolina 98 E5", "Precio Gases licuados del petróleo")
        val ci = cols.indexOf(col)
        val all = esCache?.takeIf { System.currentTimeMillis() - esAt < 3600_000L } ?: try {
            val c = URL("https://sedeaplicaciones.minetur.gob.es/ServiciosRESTCarburantes/PreciosCarburantes/EstacionesTerrestres/").openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 30_000
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("User-Agent", AlongRoute.userAgent)
            val list = ArrayList<Array<Any>>()
            try {
                if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
                android.util.JsonReader(c.inputStream.bufferedReader()).use { r ->
                    r.beginObject()
                    while (r.hasNext()) {
                        if (r.nextName() != "ListaEESSPrecio") { r.skipValue(); continue }
                        r.beginArray()
                        while (r.hasNext()) {
                            r.beginObject()
                            var name = ""; var lat = Double.NaN; var lon = Double.NaN
                            val pr = DoubleArray(cols.size) { Double.NaN }
                            while (r.hasNext()) {
                                val k = r.nextName()
                                if (r.peek() != android.util.JsonToken.STRING) { r.skipValue(); continue }
                                val v = r.nextString()
                                fun dbl() = v.replace(',', '.').toDoubleOrNull() ?: Double.NaN
                                when {
                                    k == "Rótulo" -> name = v.trim()
                                    k == "Latitud" -> lat = dbl()
                                    k.startsWith("Longitud") -> lon = dbl()
                                    else -> cols.indexOf(k).let { if (it >= 0) pr[it] = dbl() }
                                }
                            }
                            r.endObject()
                            if (!lat.isNaN() && !lon.isNaN()) list += arrayOf(name, lat, lon, pr)
                        }
                        r.endArray()
                    }
                    r.endObject()
                }
            } finally { c.disconnect() }
            esCache = list; esAt = System.currentTimeMillis()
            list
        } catch (_: Exception) {
            return@withContext null
        }
        val out = ArrayList<Tk>()
        for (a in all) {
            val lat = a[1] as Double; val lon = a[2] as Double
            if (centers.none { (cl, co) -> kotlin.math.abs(cl - lat) < 0.2 && kotlin.math.abs(co - lon) < 0.3 }) continue
            val p = (a[3] as DoubleArray)[ci]
            if (p.isNaN() || p <= 0.3) continue
            out += Tk(cap(a[0] as String).ifEmpty { "Gasolinera" }, a[0] as String, lat, lon, p, true)
        }
        out
    }


    val tkKey = MutableStateFlow("")
    private var inited = false
    private fun sp(ctx: Context) = ctx.getSharedPreferences("fuel_prices", Context.MODE_PRIVATE)

    fun init(ctx: Context) {
        if (inited) return
        inited = true
        tkKey.value = sp(ctx).getString("tk_key", "") ?: ""
    }

    fun setKey(ctx: Context, k: String) {
        val v = k.trim()
        tkKey.value = v
        sp(ctx).edit().putString("tk_key", v).apply()
    }

    /** Rodzaj paliwa dla Tankerkönig (nie ma tam LPG ani 98 – dla 98 bierzemy E5). */
    fun tkType(f: FuelType): String? = when (f) {
        FuelType.ON -> "diesel"
        FuelType.PB95, FuelType.PB98 -> "e5"
        FuelType.LPG -> null
    }

    /** Tag OSM z rodzajem paliwa (do odsiania stacji, które go nie mają). */
    fun osmTag(f: FuelType): String = when (f) {
        FuelType.ON -> "diesel"
        FuelType.PB95 -> "octane_95"
        FuelType.PB98 -> "octane_98"
        FuelType.LPG -> "lpg"
    }

    /**
     * Stacje z cenami wokół podanych punktów (promień do 25 km) – zapytania równolegle.
     * null, gdy wszystkie zapytania się nie udały (zły klucz, brak internetu).
     */
    suspend fun germany(key: String, fuel: FuelType, centers: List<Pair<Double, Double>>, radKm: Int = 25): List<Tk>? {
        val type = tkType(fuel) ?: return emptyList()
        if (key.isBlank() || centers.isEmpty()) return emptyList()
        val res = coroutineScope {
            centers.map { (lat, lon) ->
                async(Dispatchers.IO) {
                    try {
                        val url = String.format(
                            Locale.US,
                            "https://creativecommons.tankerkoenig.de/json/list.php?lat=%.5f&lng=%.5f&rad=%d&sort=price&type=%s&apikey=%s",
                            lat, lon, radKm.coerceIn(1, 25), type, java.net.URLEncoder.encode(key, "UTF-8"),
                        )
                        val js = JSONObject(http(url))
                        if (!js.optBoolean("ok")) null
                        else {
                            val a = js.optJSONArray("stations")
                            val out = ArrayList<Tk>()
                            if (a != null) for (i in 0 until a.length()) {
                                val o = a.getJSONObject(i)
                                val p = o.opt("price")
                                val price = (p as? Number)?.toDouble() ?: continue
                                if (price <= 0.3) continue
                                val brand = o.optString("brand").trim()
                                val name = o.optString("name").trim()
                                val place = o.optString("place").trim()
                                val label = when {
                                    brand.isNotEmpty() && place.isNotEmpty() -> "$brand ${place.lowercase(Locale.GERMAN).replaceFirstChar { it.titlecase(Locale.GERMAN) }}"
                                    brand.isNotEmpty() -> brand
                                    else -> name.ifEmpty { "Tankstelle" }
                                }
                                out += Tk(label, brand, o.optDouble("lat"), o.optDouble("lng"), price, o.optBoolean("isOpen", true))
                            }
                            out
                        }
                    } catch (_: Exception) {
                        null
                    }
                }
            }.awaitAll()
        }
        if (res.all { it == null }) return null
        return res.filterNotNull().flatten().distinctBy { "%.5f,%.5f".format(Locale.US, it.lat, it.lon) }
    }

    /** Sprawdzenie klucza (Berlin, 2 km). null = działa, inaczej komunikat błędu. */
    suspend fun test(key: String): String? = withContext(Dispatchers.IO) {
        try {
            val js = JSONObject(http("https://creativecommons.tankerkoenig.de/json/list.php?lat=52.52&lng=13.40&rad=2&sort=dist&type=all&apikey=" + java.net.URLEncoder.encode(key.trim(), "UTF-8")))
            if (js.optBoolean("ok")) null else js.optString("message").ifBlank { "klucz nie działa" }
        } catch (e: Exception) {
            e.message ?: "brak połączenia"
        }
    }

    private var eurRate: Double? = null
    private var eurAt = 0L

    /** Kurs EUR → PLN (średni NBP), zapamiętany na 12 h. */
    suspend fun eurPln(ctx: Context): Double? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        eurRate?.let { if (now - eurAt < 12 * 3600_000L) return@withContext it }
        val p = sp(ctx)
        try {
            val js = JSONObject(http("https://api.nbp.pl/api/exchangerates/rates/a/eur/?format=json"))
            val mid = js.getJSONArray("rates").getJSONObject(0).getDouble("mid")
            eurRate = mid; eurAt = now
            p.edit().putFloat("eur_pln", mid.toFloat()).apply()
            mid
        } catch (_: Exception) {
            p.getFloat("eur_pln", 0f).takeIf { it > 1f }?.toDouble()?.also { eurRate = it }
        }
    }

    /**
     * Sieci, które w Polsce zwykle mają tańsze paliwo niż duże marki (hipermarkety, sieci niezależne).
     * To tylko podpowiedź – nie cena.
     */
    private val cheapBrands = listOf(
        "moya", "amic", "avia", "huzar", "auchan", "carrefour", "leclerc", "intermarch", "kaufland",
        "watkem", "pieprzyk", "unimot", "costco", "makro",
    )

    fun cheapBrand(name: String): Boolean {
        val n = name.lowercase(Locale("pl"))
        return cheapBrands.any { n.contains(it) }
    }

    private fun http(url: String, postJson: String? = null): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 12000
        c.setRequestProperty("User-Agent", AlongRoute.userAgent)
        c.setRequestProperty("Accept", "application/json")
        if (postJson != null) {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(postJson.toByteArray()) }
        }
        try {
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
