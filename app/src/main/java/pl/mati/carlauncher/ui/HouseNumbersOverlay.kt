package pl.mati.carlauncher.ui

import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.VectorSource
import org.maplibre.geojson.Point

/**
 * Numery domów rysowane NAD mapą.
 * MapLibre rysuje napisy z testem głębi, więc budynki 3D zasłaniają numery (punkt numeru leży w środku budynku).
 * W 3D bierzemy numery z wczytanych kafelków i rysujemy je sami na wierzchu – zawsze widoczne,
 * bez nachodzenia na siebie (bliższe auta mają pierwszeństwo).
 */
/** Numery domów do rysowania: współrzędne (szer., dł.) parami, teksty i szerokość tekstu na 1 px wielkości. */
private class Nums(val latLon: DoubleArray, val text: Array<String>, val widthPerPx: FloatArray) {
    val count get() = text.size
    companion object { val EMPTY = Nums(DoubleArray(0), emptyArray(), FloatArray(0)) }
}

private class NumBuf {
    var xy = DoubleArray(0)
    var order = IntArray(0)
    var ys = FloatArray(0)
    val placed = FloatArray(220 * 4)
}

/** Najwięcej numerów liczonych na klatkę – i tak mieści się ok. 220, a bliższe auta mają pierwszeństwo. */
private const val MAX_NUMS = 450

@Composable
fun HouseNumbersOverlay(map: MapLibreMap?, style: Style?, enabled: Boolean, light: Boolean, modifier: Modifier) {
    if (!enabled || map == null || style == null) return
    val density = LocalDensity.current.density
    var nums by remember(map) { mutableStateOf(Nums.EMPTY) }
    var tick by remember(map) { mutableIntStateOf(0) }

    // każdy ruch kamery → przerysuj (tylko faza rysowania, bez przeliczania kompozycji)
    DisposableEffect(map) {
        val move = MapLibreMap.OnCameraMoveListener { tick++ }
        val idle = MapLibreMap.OnCameraIdleListener { tick++ }
        map.addOnCameraMoveListener(move)
        map.addOnCameraIdleListener(idle)
        onDispose {
            map.removeOnCameraMoveListener(move)
            map.removeOnCameraIdleListener(idle)
        }
    }
    // numery z wczytanych kafelków – co 1,5 s (w nawigacji kamera rusza się cały czas);
    // z mapy czytamy na jej wątku, a wybór najbliższych i miary tekstu liczymy w tle
    LaunchedEffect(map, style) {
        val measure = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textSize = 100f }
        while (true) {
            nums = try {
                val cam = map.cameraPosition
                val target = cam.target
                if (cam.zoom < MIN_ZOOM - 0.3 || target == null) Nums.EMPTY else {
                    val bounds = map.projection.visibleRegion.latLngBounds
                    val box = doubleArrayOf(bounds.latitudeSouth, bounds.latitudeNorth, bounds.longitudeWest, bounds.longitudeEast)
                    val src = style.getSourceAs<VectorSource>("openmaptiles")
                    val feats = src?.querySourceFeatures(arrayOf("housenumber"), null).orEmpty()
                    val tLat = target.latitude
                    val tLon = target.longitude
                    withContext(Dispatchers.Default) { pick(feats, box, tLat, tLon, measure) }
                }
            } catch (_: Exception) {
                Nums.EMPTY
            }
            tick++
            delay(1500)
        }
    }

    val fill = remember(light) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor(if (light) "#2F3843" else "#E3E8EE")
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
    }
    val halo = remember(light) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor(if (light) "#F2F4F7" else "#0A0C0F")
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            setStyle(Paint.Style.STROKE)
            strokeJoin = Paint.Join.ROUND
        }
    }
    // bufory na klatkę (bez tworzenia obiektów przy każdym ruchu kamery)
    val buf = remember(map) { NumBuf() }

    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") tick // odczyt = przerysowanie przy ruchu kamery
        val cam = map.cameraPosition
        if (cam.zoom < MIN_ZOOM) return@Canvas
        val list = nums
        val n = list.count
        if (n == 0) return@Canvas
        // rozmiar rośnie z przybliżeniem
        val sizePx = (11f + ((cam.zoom - MIN_ZOOM).toFloat().coerceIn(0f, 2.5f)) * 1.6f) * density
        fill.textSize = sizePx
        halo.textSize = sizePx
        halo.strokeWidth = 3.2f * density
        val w = size.width
        val h = size.height
        if (buf.xy.size < n * 2) buf.xy = DoubleArray(n * 2)
        if (buf.order.size < n) { buf.order = IntArray(n); buf.ys = FloatArray(n) }
        // wszystkie punkty na ekran jednym wywołaniem
        MapProject.toScreen(map.projection, list.latLon, n, buf.xy)
        var m = 0
        for (i in 0 until n) {
            val x = buf.xy[i * 2].toFloat()
            val y = buf.xy[i * 2 + 1].toFloat()
            if (x < -40 || x > w + 40 || y < 0 || y > h + 20) continue
            buf.order[m] = i
            buf.ys[i] = y
            m++
        }
        // bliżej dołu ekranu (bliżej auta) – pierwszeństwo
        val idx = buf.order.copyOf(m).sortedByDescending { buf.ys[it] }
        val pad = 3f * density
        val placed = buf.placed
        var np = 0
        drawIntoCanvas { c ->
            val nc = c.nativeCanvas
            for (i in idx) {
                if (np >= 220) break
                val x = buf.xy[i * 2].toFloat()
                val y = buf.xy[i * 2 + 1].toFloat()
                val tw = list.widthPerPx[i] * sizePx
                val l = x - tw / 2 - pad
                val t = y - sizePx - pad
                val r = x + tw / 2 + pad
                val b = y + pad
                var hit = false
                var k = 0
                while (k < np) {
                    val o = k * 4
                    if (l < placed[o + 2] && placed[o] < r && t < placed[o + 3] && placed[o + 1] < b) { hit = true; break }
                    k++
                }
                if (hit) continue
                val o = np * 4
                placed[o] = l; placed[o + 1] = t; placed[o + 2] = r; placed[o + 3] = b
                np++
                val s = list.text[i]
                nc.drawText(s, x, y - sizePx * 0.15f, halo)
                nc.drawText(s, x, y - sizePx * 0.15f, fill)
            }
        }
    }
}

/** Numery w widoku (+25 % marginesu), bez powtórek z sąsiednich kafelków, najbliższe kamerze najpierw. */
private fun pick(feats: List<org.maplibre.geojson.Feature>, box: DoubleArray, tLat: Double, tLon: Double, measure: Paint): Nums {
    val dLat = (box[1] - box[0]) * 0.25
    val dLon = (box[3] - box[2]) * 0.25
    val kx = kotlin.math.cos(Math.toRadians(tLat))
    val seen = HashSet<String>()
    class C(val lat: Double, val lon: Double, val n: String, val d: Double)
    val cand = ArrayList<C>()
    for (f in feats) {
        val p = f.geometry() as? Point ?: continue
        if (!f.hasProperty("housenumber")) continue
        val n = try { f.getStringProperty("housenumber") } catch (_: Exception) { null } ?: continue
        val lat = p.latitude(); val lon = p.longitude()
        if (lat < box[0] - dLat || lat > box[1] + dLat) continue
        if (lon < box[2] - dLon || lon > box[3] + dLon) continue
        // ten sam punkt bywa w kilku kafelkach
        if (!seen.add("${Math.round(lat * 1e5)},${Math.round(lon * 1e5)},$n")) continue
        val dy = lat - tLat; val dx = (lon - tLon) * kx
        cand += C(lat, lon, n, dy * dy + dx * dx)
    }
    if (cand.isEmpty()) return Nums.EMPTY
    cand.sortBy { it.d }
    val k = minOf(cand.size, MAX_NUMS)
    val ll = DoubleArray(k * 2)
    val wpp = FloatArray(k)
    val widths = HashMap<String, Float>()
    val texts = Array(k) { i ->
        val c = cand[i]
        ll[i * 2] = c.lat; ll[i * 2 + 1] = c.lon
        wpp[i] = widths.getOrPut(c.n) { measure.measureText(c.n) / 100f }
        c.n
    }
    return Nums(ll, texts, wpp)
}

private const val MIN_ZOOM = 16.5
