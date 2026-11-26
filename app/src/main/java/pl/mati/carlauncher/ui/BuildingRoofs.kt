package pl.mati.carlauncher.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.VectorSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.MultiPolygon
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Prawdziwe bryły budynków ponad samymi ścianami:
 *  – domy: dach dwuspadowy albo czterospadowy z okapem i kominem,
 *  – bloki i biurowce: attyka (murek dookoła dachu) i maszynownie / klatki na dachu.
 *
 * Kafelki OSM mają tylko obrys i wysokość, więc kształt dachu liczymy sami z obrysu
 * (najmniejszy prostokąt otaczający). MapLibre umie tylko „wyciągać” płaskie wielokąty w górę,
 * dlatego skośny dach składamy z cienkich warstw coraz węższych ku kalenicy –
 * z odległości nawigacji wygląda jak dach spadzisty.
 * Liczone dla budynków w pobliżu kamery (najbliższe kilkaset), co ok. 1,5 s.
 */
object BuildingRoofs {
    const val SOURCE = "caros-roof3d"
    private const val SUFFIX = "-roof3d"

    /** Warstwa z dachami (dodawana razem z budynkami, więc zmienia się z motywem). */
    fun layer(prefix: String, style: Style, miami: Boolean, light: Boolean): FillExtrusionLayer {
        if (style.getSource(SOURCE) == null) style.addSource(GeoJsonSource(SOURCE))
        val rc = BuildingSkins.roofColors(miami, light)
        fun col(i: Int) = Expression.color(rc[i])
        val roof = Expression.match(
            Expression.toNumber(Expression.get("ci")), col(0),
            Expression.stop(1, col(1)), Expression.stop(2, col(2)), Expression.stop(3, col(3)), Expression.stop(4, col(4)), Expression.stop(5, col(5)),
        )
        val (chimney, parapet, box) = when {
            miami && light -> Triple(0xFFC9786A, 0xFFEADCEB, 0xFFF0E4EE)
            miami -> Triple(0xFF6A3A48, 0xFF4E3E66, 0xFF5A4A72)
            light -> Triple(0xFF9A4E3E, 0xFFC4C8CD, 0xFFD3D6DA)
            else -> Triple(0xFF4A2E2A, 0xFF30353B, 0xFF3A4048)
        }
        val color = Expression.match(
            Expression.toNumber(Expression.get("k")), roof,
            Expression.stop(1, Expression.color(chimney.toInt())),
            Expression.stop(2, Expression.color(parapet.toInt())),
            Expression.stop(3, Expression.color(box.toInt())),
        )
        val l = FillExtrusionLayer(prefix + SUFFIX, SOURCE).withProperties(
            PropertyFactory.fillExtrusionColor(color),
            PropertyFactory.fillExtrusionHeight(Expression.toNumber(Expression.get("t"))),
            PropertyFactory.fillExtrusionBase(Expression.toNumber(Expression.get("b"))),
            PropertyFactory.fillExtrusionOpacity(1f),
            PropertyFactory.fillExtrusionVerticalGradient(false),
        )
        l.minZoom = 15.4f
        return l
    }

    // ---------- odświeżanie ----------

    private class Raw(val id: Double?, val ring: DoubleArray /* lon,lat,lon,lat… */, val h: Double, val minH: Double, val dist: Double)

    private val cache = object : LinkedHashMap<String, List<Feature>>(2048, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Feature>>?) = size > 6000
    }
    /** Co jest teraz pokazane – osobno dla każdej mapy (stylu). */
    private val lastSig = java.util.WeakHashMap<Style, Long>()
    private val lastCam = java.util.WeakHashMap<Style, DoubleArray>()

    /** Odczyt budynków z kafelków (wątek mapy) i przeliczenie dachów (w tle). */
    suspend fun refresh(map: MapLibreMap, style: Style, maxCount: Int) {
        val src = style.getSourceAs<GeoJsonSource>(SOURCE) ?: return
        val on = style.layers.any {
            it.id.startsWith(CarMapStyle.BUILDINGS_3D) && it.id.endsWith(SUFFIX) &&
                (try { it.visibility.value } catch (_: Exception) { Property.VISIBLE }) != Property.NONE
        }
        val cam = map.cameraPosition
        val target = cam.target
        if (!on || cam.zoom < 15.3 || target == null) {
            if ((lastSig[style] ?: 0L) != 0L) { src.setGeoJson(FeatureCollection.fromFeatures(emptyList())); lastSig[style] = 0L }
            lastCam.remove(style)
            return
        }
        // kamera prawie stoi – nie czytaj kafelków co chwilę (raz na 6 s, gdy dochodzą nowe kafelki)
        val now = System.currentTimeMillis()
        val lc = lastCam[style]
        if (lc != null && abs(lc[0] - target.latitude) * KY < 25 && abs(lc[1] - target.longitude) * KY * cos(Math.toRadians(target.latitude)) < 25 &&
            abs(lc[2] - cam.zoom) < 0.3 && now - lc[3] < 6000) return
        lastCam[style] = doubleArrayOf(target.latitude, target.longitude, cam.zoom, now.toDouble())
        val vs = style.getSourceAs<VectorSource>("openmaptiles") ?: return
        val bb = map.projection.visibleRegion.latLngBounds
        val mLat = (bb.latitudeNorth - bb.latitudeSouth) * 0.3
        val mLon = (bb.longitudeEast - bb.longitudeWest) * 0.3
        val s = bb.latitudeSouth - mLat; val n = bb.latitudeNorth + mLat
        val w = bb.longitudeWest - mLon; val e = bb.longitudeEast + mLon
        val tLat = target.latitude; val tLon = target.longitude
        val kx = cos(Math.toRadians(tLat))
        // odczyt z kafelków musi być na wątku mapy; przeliczanie obrysów – już w tle
        val feats = vs.querySourceFeatures(arrayOf("building"), null)
        val prev = lastSig[style] ?: 0L
        val res = withContext(Dispatchers.Default) {
        val raws = ArrayList<Raw>()
        for (f in feats) {
            if (f.hasProperty("hide_3d") && f.getBooleanProperty("hide_3d") == true) continue
            val polys: List<List<List<Point>>> = when (val g = f.geometry()) {
                is Polygon -> listOf(g.coordinates())
                is MultiPolygon -> g.coordinates()
                else -> continue
            }
            val h = (if (f.hasProperty("render_height")) f.getNumberProperty("render_height")?.toDouble() else null) ?: 6.0
            val mh = (if (f.hasProperty("render_min_height")) f.getNumberProperty("render_min_height")?.toDouble() else null) ?: 0.0
            val id = f.id()?.toDoubleOrNull()
            for (p in polys) {
                val outer = p.firstOrNull() ?: continue
                if (outer.size < 4) continue
                val p0 = outer[0]
                if (p0.latitude() < s || p0.latitude() > n || p0.longitude() < w || p0.longitude() > e) continue
                val arr = DoubleArray(outer.size * 2)
                for (i in outer.indices) { arr[i * 2] = outer[i].longitude(); arr[i * 2 + 1] = outer[i].latitude() }
                val dy = p0.latitude() - tLat; val dx = (p0.longitude() - tLon) * kx
                raws += Raw(id, arr, h, mh, dy * dy + dx * dx)
            }
        }
            raws.sortBy { it.dist }
            // ten sam budynek bywa w kilku kafelkach: identyczny – bierzemy raz, przycięty na granicy – bez dachu
            val byId = HashMap<Double, MutableList<Raw>>()
            for (r in raws) r.id?.let { byId.getOrPut(it) { ArrayList() } += r }
            val out = ArrayList<Feature>()
            var sig = 17L
            var used = 0
            val done = HashSet<String>()
            for (r in raws) {
                if (used >= maxCount) break
                val key = keyOf(r)
                if (!done.add(key)) continue
                val id = r.id
                if (id != null) {
                    val same = byId[id].orEmpty()
                    if (same.size > 1 && same.any { abs(areaDeg(it.ring) - areaDeg(r.ring)) > areaDeg(r.ring) * 0.03 }) continue
                }
                val list = synchronized(cache) { cache[key] } ?: build(r).also { synchronized(cache) { cache[key] = it } }
                if (list.isEmpty()) continue
                used++
                sig = sig * 31 + key.hashCode()
                out += list
            }
            if (sig == prev) null else sig to FeatureCollection.fromFeatures(out)
        }
        if (res != null) { src.setGeoJson(res.second); lastSig[style] = res.first }
    }

    private fun keyOf(r: Raw): String {
        val sb = StringBuilder()
        sb.append(r.id ?: 0.0).append('|').append(r.ring.size).append('|')
        sb.append((r.ring[0] * 1e5).roundToLong()).append(',').append((r.ring[1] * 1e5).roundToLong()).append('|').append(r.h)
        return sb.toString()
    }

    private fun areaDeg(a: DoubleArray): Double {
        var s = 0.0
        val n = a.size / 2
        for (i in 0 until n) {
            val j = (i + 1) % n
            s += a[i * 2] * a[j * 2 + 1] - a[j * 2] * a[i * 2 + 1]
        }
        return abs(s / 2)
    }

    // ---------- geometria ----------

    private const val KY = 111_320.0

    /** Dachy jednego budynku jako warstwy (b – spód, t – góra, k – rodzaj, ci – kolor dachu). */
    private fun build(r: Raw): List<Feature> {
        var n = r.ring.size / 2
        if (n >= 2 && r.ring[0] == r.ring[(n - 1) * 2] && r.ring[1] == r.ring[(n - 1) * 2 + 1]) n-- // zamknięcie pierścienia
        if (n < 3) return emptyList()
        var lon0 = 0.0; var lat0 = 0.0
        for (i in 0 until n) { lon0 += r.ring[i * 2]; lat0 += r.ring[i * 2 + 1] }
        lon0 /= n; lat0 /= n
        val kx = KY * cos(Math.toRadians(lat0))
        val xs = DoubleArray(n) { (r.ring[it * 2] - lon0) * kx }
        val ys = DoubleArray(n) { (r.ring[it * 2 + 1] - lat0) * KY }
        // pole obrysu
        var area = 0.0
        for (i in 0 until n) { val j = (i + 1) % n; area += xs[i] * ys[j] - xs[j] * ys[i] }
        area = abs(area / 2)
        // najmniejszy prostokąt otaczający (po kierunkach krawędzi)
        var best = Double.MAX_VALUE
        var ux = 1.0; var uy = 0.0; var cu = 0.0; var cv = 0.0; var hu = 0.0; var hv = 0.0
        for (i in 0 until n) {
            val j = (i + 1) % n
            val dx = xs[j] - xs[i]; val dy = ys[j] - ys[i]
            val len = sqrt(dx * dx + dy * dy)
            if (len < 0.3) continue
            val ex = dx / len; val ey = dy / len
            var minU = Double.MAX_VALUE; var maxU = -Double.MAX_VALUE; var minV = Double.MAX_VALUE; var maxV = -Double.MAX_VALUE
            for (k in 0 until n) {
                val u = xs[k] * ex + ys[k] * ey
                val v = -xs[k] * ey + ys[k] * ex
                minU = min(minU, u); maxU = max(maxU, u); minV = min(minV, v); maxV = max(maxV, v)
            }
            val a = (maxU - minU) * (maxV - minV)
            if (a < best) {
                best = a; ux = ex; uy = ey
                cu = (minU + maxU) / 2; cv = (minV + maxV) / 2; hu = (maxU - minU) / 2; hv = (maxV - minV) / 2
            }
        }
        if (best == Double.MAX_VALUE || best < 1.0) return emptyList()
        // środek prostokąta, oś długa u (pół-długość a), krótka v (pół-szerokość b)
        val cx = cu * ux - cv * uy
        val cy = cu * uy + cv * ux
        var axU = ux; var ayU = uy; var a = hu; var b = hv
        if (hv > hu) { axU = -uy; ayU = ux; a = hv; b = hu }
        val axV = -ayU; val ayV = axU
        val fill = area / (4 * a * b)

        val h = r.h
        val idv = r.id ?: 0.0
        // ten sam „los” co w warstwach z kafelków (kolor dachu = kolor okapu)
        val hash = (((idv * 7 + h * 131).roundToLong() % 9973) + 9973) % 9973
        val ci = (floor(hash / 16.0).toLong() % 6).toInt()
        val cap = when { h <= 7 -> 0.25; h > 45 -> 1.2; else -> 0.6 }
        val top0 = h + cap

        fun rect(du: Double, dv: Double, hu2: Double, hv2: Double): List<Point> {
            val ccx = cx + du * axU + dv * axV
            val ccy = cy + du * ayU + dv * ayV
            val c = listOf(
                Pair(hu2, hv2), Pair(-hu2, hv2), Pair(-hu2, -hv2), Pair(hu2, -hv2), Pair(hu2, hv2),
            )
            return c.map { (pu, pv) ->
                val x = ccx + pu * axU + pv * axV
                val y = ccy + pu * ayU + pv * ayV
                Point.fromLngLat(lon0 + x / kx, lat0 + y / KY)
            }
        }
        fun feat(ring: List<List<Point>>, base: Double, top: Double, k: Int): Feature {
            val f = Feature.fromGeometry(Polygon.fromLngLats(ring))
            f.addNumberProperty("b", base)
            f.addNumberProperty("t", top)
            f.addNumberProperty("k", k)
            f.addNumberProperty("ci", ci)
            return f
        }

        val out = ArrayList<Feature>()
        val house = h <= 8.5 && r.minH < 0.5 && area in 40.0..450.0 && b >= 2.0 && fill >= 0.82
        if (house) {
            // dach: okap 0,35 m, spadek ok. 37°, 8 warstw
            val o = 0.35
            val bb = b + o
            val aa = a + o
            val rise = (b * 0.75).coerceIn(1.6, 4.2)
            val gable = a / b >= 1.35 && hash % 5 < 3
            val steps = 8
            for (k in 0 until steps) {
                val t = k.toDouble() / steps
                val hv2 = bb * (1 - t)
                val hu2 = if (gable) aa else (aa - bb * t)
                if (hv2 <= 0.05 || hu2 <= 0.05) break
                out += feat(listOf(rect(0.0, 0.0, hu2, hv2)), top0 + rise * t, top0 + rise * (k + 1) / steps, 0)
            }
            // komin
            if (hash % 3 != 0L) {
                val su = (if (hash % 2 == 0L) 1 else -1) * a * 0.42
                val sv = b * 0.32
                var f = 1 - sv / bb
                if (!gable) f = min(f, (aa - abs(su)) / bb)
                val topC = top0 + rise * f.coerceIn(0.1, 1.0) + 0.9
                out += feat(listOf(rect(su, sv, 0.32, 0.32)), top0, topC, 1)
            }
        } else if (h > 8.5 && r.minH < 0.5 && fill >= 0.9 && b >= 3.0) {
            // attyka: murek 0,9 m dookoła dachu
            val outer = rect(0.0, 0.0, a, b)
            val inner = rect(0.0, 0.0, a - 0.45, b - 0.45).reversed()
            out += feat(listOf(outer, inner), top0, top0 + 0.9, 2)
            // maszynownia / klatka schodowa na dachu (1–2), na wieżowcach większa
            if (a >= 7) {
                val big = h > 45
                val boxU = if (big) min(a * 0.35, 9.0) else 1.6
                val boxV = if (big) min(b * 0.45, 7.0) else 1.2
                val hb = if (big) 4.5 else 2.4
                val pos = if (!big && a > 22) listOf(-a * 0.5, a * 0.5) else listOf(if (big) 0.0 else a * 0.25 * (if (hash % 2 == 0L) 1 else -1))
                for (pu in pos) out += feat(listOf(rect(pu, 0.0, boxU, boxV)), top0, top0 + hb, 3)
            }
        }
        return out
    }
}

/** Liczy dachy dla budynków przy kamerze (tylko gdy włączone „budynki jak prawdziwe” i 3D). */
@Composable
fun BuildingRoofsOverlay(map: MapLibreMap?, style: Style?, enabled: Boolean, maxCount: Int = 600) {
    if (!enabled || map == null || style == null) return
    LaunchedEffect(map, style) {
        while (true) {
            try { BuildingRoofs.refresh(map, style, maxCount) } catch (_: Exception) {}
            delay(1500)
        }
    }
}
