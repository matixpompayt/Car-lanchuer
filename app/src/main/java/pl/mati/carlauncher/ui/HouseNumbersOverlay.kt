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
import kotlinx.coroutines.delay
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
@Composable
fun HouseNumbersOverlay(map: MapLibreMap?, style: Style?, enabled: Boolean, light: Boolean, modifier: Modifier) {
    if (!enabled || map == null || style == null) return
    val density = LocalDensity.current.density
    var feats by remember(map) { mutableStateOf<List<Pair<LatLng, String>>>(emptyList()) }
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
    // numery z wczytanych kafelków – co 1,5 s (w nawigacji kamera rusza się cały czas)
    LaunchedEffect(map, style) {
        while (true) {
            feats = try {
                if (map.cameraPosition.zoom < MIN_ZOOM - 0.3) emptyList() else {
                    val bounds = map.projection.visibleRegion.latLngBounds
                    val dLat = (bounds.latitudeNorth - bounds.latitudeSouth) * 0.25
                    val dLon = (bounds.longitudeEast - bounds.longitudeWest) * 0.25
                    val src = style.getSourceAs<VectorSource>("openmaptiles")
                    val seen = HashSet<String>()
                    src?.querySourceFeatures(arrayOf("housenumber"), null).orEmpty().mapNotNull { f ->
                        val p = f.geometry() as? Point ?: return@mapNotNull null
                        if (!f.hasProperty("housenumber")) return@mapNotNull null
                        val n = f.getStringProperty("housenumber") ?: return@mapNotNull null
                        val lat = p.latitude(); val lon = p.longitude()
                        if (lat < bounds.latitudeSouth - dLat || lat > bounds.latitudeNorth + dLat) return@mapNotNull null
                        if (lon < bounds.longitudeWest - dLon || lon > bounds.longitudeEast + dLon) return@mapNotNull null
                        // ten sam punkt bywa w kilku kafelkach
                        if (!seen.add("%.5f,%.5f,%s".format(lat, lon, n))) return@mapNotNull null
                        LatLng(lat, lon) to n
                    }.take(1500)
                }
            } catch (_: Exception) {
                emptyList()
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

    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") tick // odczyt = przerysowanie przy ruchu kamery
        val cam = map.cameraPosition
        if (cam.zoom < MIN_ZOOM) return@Canvas
        val list = feats
        if (list.isEmpty()) return@Canvas
        // rozmiar rośnie z przybliżeniem
        val sizePx = (11f + ((cam.zoom - MIN_ZOOM).toFloat().coerceIn(0f, 2.5f)) * 1.6f) * density
        fill.textSize = sizePx
        halo.textSize = sizePx
        halo.strokeWidth = 3.2f * density
        val proj = map.projection
        val w = size.width
        val h = size.height
        // bliżej dołu ekranu (bliżej auta) – pierwszeństwo
        val pts = ArrayList<Triple<Float, Float, String>>(list.size)
        for ((ll, n) in list) {
            val s = proj.toScreenLocation(ll)
            if (s.x < -40 || s.x > w + 40 || s.y < 0 || s.y > h + 20) continue
            pts += Triple(s.x, s.y, n)
        }
        pts.sortByDescending { it.second }
        val placed = ArrayList<RectF>()
        val pad = 3f * density
        drawIntoCanvas { c ->
            val nc = c.nativeCanvas
            for ((x, y, n) in pts) {
                if (placed.size >= 220) break
                val tw = fill.measureText(n)
                val r = RectF(x - tw / 2 - pad, y - sizePx - pad, x + tw / 2 + pad, y + pad)
                if (placed.any { RectF.intersects(it, r) }) continue
                placed += r
                nc.drawText(n, x, y - sizePx * 0.15f, halo)
                nc.drawText(n, x, y - sizePx * 0.15f, fill)
            }
        }
    }
}

private const val MIN_ZOOM = 16.5
