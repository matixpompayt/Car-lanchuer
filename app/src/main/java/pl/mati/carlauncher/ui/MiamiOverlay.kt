package pl.mati.carlauncher.ui

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.sources.VectorSource
import org.maplibre.geojson.LineString
import org.maplibre.geojson.MultiLineString
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Styl Miami w 3D: palmy wzdłuż ulic i ciepła poświata zachodu słońca nad horyzontem.
 * MapLibre nie ma modeli 3D, więc palmy rysujemy sami nad mapą (jak numery domów):
 * miejsca bierzemy z dróg we wczytanych kafelkach (co ~24 m, na zmianę po obu stronach),
 * a wielkość z perspektywy kamery (dalej = mniejsze, bliższe rysowane na wierzchu).
 */
@Composable
fun MiamiOverlay(map: MapLibreMap?, style: Style?, enabled: Boolean, light: Boolean, avoid: (() -> LatLng?)?, modifier: Modifier) {
    if (!enabled || map == null || style == null) return
    val density = LocalDensity.current.density
    var palms by remember(map) { mutableStateOf<List<Palm>>(emptyList()) }
    var tick by remember(map) { mutableIntStateOf(0) }

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
    // miejsca palm z dróg we wczytanych kafelkach – co 2 s (liczenie w tle)
    LaunchedEffect(map, style) {
        while (true) {
            try {
                if (map.cameraPosition.zoom >= MIN_ZOOM - 0.4) {
                    val b = map.projection.visibleRegion.latLngBounds
                    val src = style.getSourceAs<VectorSource>("openmaptiles")
                    val feats = src?.querySourceFeatures(arrayOf("transportation"), FILTER).orEmpty()
                    val box = doubleArrayOf(b.latitudeSouth, b.latitudeNorth, b.longitudeWest, b.longitudeEast)
                    palms = withContext(Dispatchers.Default) { place(feats, box) }
                } else if (palms.isNotEmpty()) palms = emptyList()
            } catch (_: Exception) {
            }
            tick++
            delay(2000)
        }
    }

    val sprites = remember(light) { listOf(palmBitmap(light, 0), palmBitmap(light, 1), palmBitmap(light, 2)) }
    val paint = remember { Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG) }
    val pb = remember(map) { PalmBuf() }
    val haze = remember(light) {
        if (light) listOf(Color(0x66FF9A62), Color(0x26FF7FB0), Color.Transparent)
        else listOf(Color(0x80FF4FA3), Color(0x338A3FD1), Color.Transparent)
    }

    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") tick
        val cam = map.cameraPosition
        val w = size.width
        val h = size.height
        // z góry (bez pochylenia) palmy i horyzont nie mają sensu
        if (cam.tilt < 15.0) return@Canvas
        if (cam.zoom >= MIN_ZOOM && palms.isNotEmpty()) {
            val proj = map.projection
            // piksele na metr w kilku wierszach ekranu (perspektywa) – potem interpolacja po y
            val rows = floatArrayOf(0.2f, 0.4f, 0.6f, 0.8f, 1.0f)
            val ppm = FloatArray(rows.size)
            for (i in rows.indices) {
                val y = rows[i] * h
                val a = proj.fromScreenLocation(PointF(w / 2, y))
                val c = proj.fromScreenLocation(PointF(w / 2 + 100f, y))
                val m = meters(a.latitude, a.longitude, c.latitude, c.longitude)
                ppm[i] = if (m > 0.01 && m < 5000) (100.0 / m).toFloat() else 0f
            }
            fun ppmAt(y: Float): Float {
                val t = y / h
                if (t <= rows[0]) return ppm[0]
                for (i in 1 until rows.size) if (t <= rows[i]) {
                    val f = (t - rows[i - 1]) / (rows[i] - rows[i - 1])
                    return ppm[i - 1] + (ppm[i] - ppm[i - 1]) * f
                }
                return ppm.last()
            }
            val car = try { avoid?.invoke()?.let { proj.toScreenLocation(it) } } catch (_: Exception) { null }
            val carR = 64f * density
            val b = proj.visibleRegion.latLngBounds
            val tilt = (cam.tilt / 90.0).toFloat().coerceIn(0f, 1f)
            val vis = ArrayList<FloatArray>(300)
            // palmy w widoku na ekran jednym wywołaniem (zamiast osobno każdą – setki na klatkę)
            val all = palms
            if (pb.ll.size < all.size * 2) { pb.ll = DoubleArray(all.size * 2); pb.xy = DoubleArray(all.size * 2); pb.idx = IntArray(all.size) }
            var cnt = 0
            for ((i, p) in all.withIndex()) {
                if (p.lat < b.latitudeSouth || p.lat > b.latitudeNorth || p.lon < b.longitudeWest || p.lon > b.longitudeEast) continue
                pb.ll[cnt * 2] = p.lat; pb.ll[cnt * 2 + 1] = p.lon
                pb.idx[cnt] = i
                cnt++
            }
            MapProject.toScreen(proj, pb.ll, cnt, pb.xy)
            for (j in 0 until cnt) {
                val p = all[pb.idx[j]]
                val sx = pb.xy[j * 2].toFloat()
                val sy = pb.xy[j * 2 + 1].toFloat()
                if (sx < -60 || sx > w + 60 || sy < h * 0.08f || sy > h + 80) continue
                if (car != null && hypot(sx - car.x, sy - car.y) < carR) continue
                val k = ppmAt(sy)
                if (k <= 0f) continue
                // pionowy obiekt w pochylonym widoku: skrót zależny od pochylenia
                val hp = (p.height * k * (0.35f + 0.6f * tilt)).coerceAtMost(150f * density)
                if (hp < 9f * density) continue
                vis += floatArrayOf(sx, sy, hp, p.kind.toFloat(), if (p.flip) 1f else 0f)
            }
            // najdalsze (wyżej na ekranie) najpierw, bliższe na wierzchu; najwyżej ~260 palm
            vis.sortBy { it[1] }
            val draw = if (vis.size > 260) vis.subList(vis.size - 260, vis.size) else vis
            drawIntoCanvas { c ->
                val nc = c.nativeCanvas
                val dst = RectF()
                for (v in draw) {
                    val bmp = sprites[v[3].toInt() % sprites.size]
                    val hp = v[2]
                    val wp = hp * bmp.width / bmp.height
                    dst.set(v[0] - wp * 0.5f, v[1] - hp, v[0] + wp * 0.5f, v[1])
                    if (v[4] > 0.5f) {
                        nc.save()
                        nc.scale(-1f, 1f, v[0], v[1])
                        nc.drawBitmap(bmp, null, dst, paint)
                        nc.restore()
                    } else {
                        nc.drawBitmap(bmp, null, dst, paint)
                    }
                }
            }
        }
        // poświata zachodu słońca przy horyzoncie (też na dalekich palmach)
        drawRect(
            Brush.verticalGradient(haze, startY = 0f, endY = h * 0.48f),
            topLeft = Offset.Zero, size = Size(w, h * 0.48f),
        )
    }
}

private const val MIN_ZOOM = 15.2

private class PalmBuf {
    var ll = DoubleArray(0)
    var xy = DoubleArray(0)
    var idx = IntArray(0)
}

private class Palm(val lat: Double, val lon: Double, val height: Float, val kind: Int, val flip: Boolean)

private val FILTER: Expression = Expression.match(
    Expression.get("class"),
    Expression.literal("minor"), Expression.literal(true),
    Expression.literal("service"), Expression.literal(true),
    Expression.literal("tertiary"), Expression.literal(true),
    Expression.literal("secondary"), Expression.literal(true),
    Expression.literal("primary"), Expression.literal(true),
    Expression.literal(false),
)

private fun meters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dy = (lat2 - lat1) * 111_320.0
    val dx = (lon2 - lon1) * 111_320.0 * cos(Math.toRadians((lat1 + lat2) / 2))
    return sqrt(dx * dx + dy * dy)
}

/** Palmy co ~24 m wzdłuż dróg, na zmianę po lewej i prawej, odsunięte od osi drogi. */
private fun place(feats: List<org.maplibre.geojson.Feature>, box: DoubleArray): List<Palm> {
    val dLat = (box[1] - box[0]) * 0.3
    val dLon = (box[3] - box[2]) * 0.3
    val s = box[0] - dLat; val n = box[1] + dLat; val wl = box[2] - dLon; val e = box[3] + dLon
    val seen = HashSet<Long>()
    val out = ArrayList<Palm>()
    for (f in feats) {
        val cls = try { f.getStringProperty("class") } catch (_: Exception) { null } ?: continue
        // odsunięcie od osi: szersze drogi dalej
        val off = when (cls) { "primary" -> 11.0; "secondary" -> 9.5; "tertiary" -> 8.0; "service" -> 5.0; else -> 6.5 }
        val step = if (cls == "service") 30.0 else 24.0
        val lines: List<List<org.maplibre.geojson.Point>> = when (val g = f.geometry()) {
            is LineString -> listOf(g.coordinates())
            is MultiLineString -> g.coordinates()
            else -> continue
        }
        for (pts in lines) {
            if (pts.size < 2) continue
            var acc = step * 0.5
            var side = 1
            for (i in 1 until pts.size) {
                val a = pts[i - 1]; val b = pts[i]
                val la = a.latitude(); val lo = a.longitude()
                val segM = meters(la, lo, b.latitude(), b.longitude())
                if (segM < 0.5) continue
                val kx = 111_320.0 * cos(Math.toRadians(la))
                val ux = (b.longitude() - lo) * kx / segM
                val uy = (b.latitude() - la) * 111_320.0 / segM
                var d = acc
                while (d < segM) {
                    val px = lo + ux * d / kx
                    val py = la + uy * d / 111_320.0
                    // prostopadle do drogi: (-uy, ux)
                    val ox = -uy * off * side
                    val oy = ux * off * side
                    val lon = px + ox / kx
                    val lat = py + oy / 111_320.0
                    side = -side
                    d += step
                    if (lat < s || lat > n || lon < wl || lon > e) continue
                    // ten sam odcinek bywa w kilku kafelkach – siatka ~7 m
                    val key = ((lat * 15000).toLong() shl 32) xor (lon * 15000).toLong()
                    if (!seen.add(key)) continue
                    val hsh = abs((lat * 1e6).toLong() * 31 + (lon * 1e6).toLong())
                    out += Palm(lat, lon, 8f + (hsh % 5).toFloat(), (hsh % 3).toInt(), (hsh / 3) % 2 == 0L)
                    if (out.size >= 1600) return out
                }
                acc = d - segM
            }
        }
    }
    return out
}

/** Palma rysowana raz (3 odmiany: prosta, pochylona, niska z gęstą koroną). */
private fun palmBitmap(light: Boolean, kind: Int): Bitmap {
    val w = 250
    val h = 260
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val trunkCol = if (light) 0xFF9A7552.toInt() else 0xFF6E4E63.toInt()
    val trunkDark = if (light) 0xFF6E5038.toInt() else 0xFF45304A.toInt()
    val leaf = if (light) 0xFF2FA05C.toInt() else 0xFF23A27C.toInt()
    val leafDark = if (light) 0xFF1E7A43.toInt() else 0xFF15695A.toInt()
    val rim = if (light) 0x00000000 else 0xAAFF6FC4.toInt()
    val lean = when (kind) { 1 -> 34f; 2 -> -12f; else -> 12f }
    val topY = if (kind == 2) 110f else 70f
    val bx = w / 2f
    val cx = bx + lean
    // pień – zwężający się łuk
    val trunk = Path().apply {
        moveTo(bx - 9f, h.toFloat())
        quadTo(bx - 6f + lean * 0.2f, (h + topY) / 2, cx - 4f, topY + 6f)
        lineTo(cx + 4f, topY + 6f)
        quadTo(bx + 6f + lean * 0.2f, (h + topY) / 2, bx + 9f, h.toFloat())
        close()
    }
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = trunkCol
    c.drawPath(trunk, p)
    // pierścienie na pniu
    p.color = trunkDark
    p.strokeWidth = 2f
    var y = h - 14f
    while (y > topY + 16) {
        val t = (h - y) / (h - topY)
        val x = bx + (cx - bx) * t * t
        c.drawLine(x - 7f + t * 3, y, x + 7f - t * 3, y - 2f, p)
        y -= 13f
    }
    if (rim != 0) {
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2.2f
        p.color = rim
        c.drawPath(trunk, p)
        p.style = Paint.Style.FILL
    }
    // liście – 8 wygiętych liści z korony
    val fronds = if (kind == 2) 9 else 8
    for (i in 0 until fronds) {
        val ang = Math.toRadians(-180.0 + i * (180.0 / (fronds - 1)) + (if (i % 2 == 0) 6 else -6))
        val len = (if (kind == 2) 70f else 78f) * (if (i == 0 || i == fronds - 1) 0.85f else 1f)
        val ex = cx + (cos(ang) * len).toFloat()
        // liście opadają na końcach
        val ey = topY + (sin(ang) * len * 0.55).toFloat() + len * 0.45f * (1f - abs(sin(ang)).toFloat())
        val mx = cx + (cos(ang) * len * 0.5).toFloat()
        val my = topY + (sin(ang) * len * 0.75).toFloat() - 6f
        val nx = -(ey - topY); val ny = (ex - cx)
        val nl = hypot(nx, ny).coerceAtLeast(1f)
        val wdt = 10f
        val leafPath = Path().apply {
            moveTo(cx, topY)
            quadTo(mx + nx / nl * wdt, my + ny / nl * wdt, ex, ey)
            quadTo(mx - nx / nl * wdt * 0.4f, my - ny / nl * wdt * 0.4f, cx, topY)
            close()
        }
        p.color = if (i % 2 == 0) leaf else leafDark
        c.drawPath(leafPath, p)
        if (rim != 0) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1.4f
            p.color = 0x66FF8AD8
            c.drawPath(leafPath, p)
            p.style = Paint.Style.FILL
        }
    }
    // kokosy
    p.color = trunkDark
    c.drawCircle(cx - 5f, topY + 8f, 5f, p)
    c.drawCircle(cx + 5f, topY + 9f, 5f, p)
    return bmp
}
