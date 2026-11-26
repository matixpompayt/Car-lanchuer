package pl.mati.carlauncher.ui

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.mati.carlauncher.data.Prefs
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Interfejs „Glass” w stylu iOS 26 (wczesny dostęp) – nakładka na każdy motyw:
 * karty z mrożonego szkła (rozmyte i nasycone tło motywu pod spodem, połysk, jasna krawędź),
 * kropla w menu, która przepływa między ikonami i powiększa je jak soczewka, przełączniki jak w iPhonie.
 * Radio ma Androida 10, więc bez shaderów – rozmycie liczymy raz z tła motywu do małej bitmapy.
 */
object GlassUi {
    var on by mutableStateOf(false)
    /** Rozmyte tło motywu (null przy jednolitym tle – wtedy szkło to sam półprzezroczysty odcień). */
    var blurred by mutableStateOf<ImageBitmap?>(null)
    /** Ostre tło motywu (połowa rozdzielczości) – do załamania światła na krawędziach gładkiego szkła. */
    var sharp by mutableStateOf<ImageBitmap?>(null)
    var rootSize by mutableStateOf(IntSize.Zero)
}

/** Ile szklanych warstw nad tłem (karta w karcie nie pokazuje tła drugi raz). */
val LocalGlassDepth = compositionLocalOf { 0 }

// ======================= tło motywu → rozmyta bitmapa =======================

/** Liczy rozmytą kopię tła motywu, gdy zmienia się motyw, tapeta, dzień/noc albo rozmiar ekranu. */
@Composable
fun GlassBackdropSync() {
    val glass by Prefs.glassUi.collectAsState()
    val wall by Prefs.wallpaper.collectAsState()
    val th = CarColors.theme
    val light = CarColors.isLight
    val size = GlassUi.rootSize
    LaunchedEffect(glass, th.id, wall, light, size) {
        if (!glass || size.width <= 0 || th.bg == BgStyle.SOLID) { GlassUi.blurred = null; GlassUi.sharp = null; return@LaunchedEffect }
        delay(300) // przejście dzień/noc trwa ok. 1 s – licz po zmianie, nie w trakcie
        val brush = themeBackground(th, wall)
        val bg = th.bg
        GlassUi.blurred = withContext(Dispatchers.Default) {
            try { renderBlur(brush, bg, size.width, size.height) } catch (_: Throwable) { null }
        }
        GlassUi.sharp = null
    }
}

/** Tło motywu do bitmapy w skali [k] (bez rozmycia). */
private fun renderBg(brush: Brush, bg: BgStyle, w: Int, h: Int, k: Float): ImageBitmap {
    val sw = max(4, (w * k).roundToInt()); val sh = max(4, (h * k).roundToInt())
    val img = ImageBitmap(sw, sh)
    val canvas = androidx.compose.ui.graphics.Canvas(img)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, canvas, Size(sw.toFloat(), sh.toFloat())) {
        scale(k, k, pivot = Offset.Zero) {
            drawRect(brush, size = Size(w.toFloat(), h.toFloat()))
            when (bg) {
                BgStyle.SPORT -> drawCarbon(w.toFloat(), h.toFloat())
                BgStyle.RETRO -> drawRetro(w.toFloat(), h.toFloat())
                BgStyle.MIAMI -> drawMiami(w.toFloat(), h.toFloat(), CarColors.isLight)
                else -> {}
            }
        }
    }
    return img
}

private fun renderBlur(brush: Brush, bg: BgStyle, w: Int, h: Int): ImageBitmap {
    val k = 1f / 6f
    val sw = max(4, (w * k).roundToInt()); val sh = max(4, (h * k).roundToInt())
    val img = ImageBitmap(sw, sh)
    val canvas = androidx.compose.ui.graphics.Canvas(img)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, canvas, Size(sw.toFloat(), sh.toFloat())) {
        scale(k, k, pivot = Offset.Zero) {
            drawRect(brush, size = Size(w.toFloat(), h.toFloat()))
            when (bg) {
                BgStyle.SPORT -> drawCarbon(w.toFloat(), h.toFloat())
                BgStyle.RETRO -> drawRetro(w.toFloat(), h.toFloat())
                BgStyle.MIAMI -> drawMiami(w.toFloat(), h.toFloat(), CarColors.isLight)
                else -> {}
            }
        }
    }
    // nasycenie (jak „vibrancy” w iOS) + rozmycie
    val src = img.asAndroidBitmap()
    val out = android.graphics.Bitmap.createBitmap(sw, sh, android.graphics.Bitmap.Config.ARGB_8888)
    android.graphics.Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.5f) }) })
    val px = IntArray(sw * sh)
    out.getPixels(px, 0, sw, 0, 0, sw, sh)
    repeat(3) { boxBlur(px, sw, sh, 4) }
    out.setPixels(px, 0, sw, 0, 0, sw, sh)
    return out.asImageBitmap()
}

private fun boxBlur(px: IntArray, w: Int, h: Int, r: Int) {
    val tmp = IntArray(px.size)
    val d = 2 * r + 1
    for (y in 0 until h) {
        val row = y * w
        var sr = 0; var sg = 0; var sb = 0
        for (i in -r..r) { val p = px[row + i.coerceIn(0, w - 1)]; sr += (p shr 16) and 255; sg += (p shr 8) and 255; sb += p and 255 }
        for (x in 0 until w) {
            tmp[row + x] = (0xFF shl 24) or ((sr / d) shl 16) or ((sg / d) shl 8) or (sb / d)
            val po = px[row + (x - r).coerceIn(0, w - 1)]
            val pi = px[row + (x + r + 1).coerceIn(0, w - 1)]
            sr += ((pi shr 16) and 255) - ((po shr 16) and 255)
            sg += ((pi shr 8) and 255) - ((po shr 8) and 255)
            sb += (pi and 255) - (po and 255)
        }
    }
    for (x in 0 until w) {
        var sr = 0; var sg = 0; var sb = 0
        for (i in -r..r) { val p = tmp[i.coerceIn(0, h - 1) * w + x]; sr += (p shr 16) and 255; sg += (p shr 8) and 255; sb += p and 255 }
        for (y in 0 until h) {
            px[y * w + x] = (0xFF shl 24) or ((sr / d) shl 16) or ((sg / d) shl 8) or (sb / d)
            val po = tmp[(y - r).coerceIn(0, h - 1) * w + x]
            val pi = tmp[(y + r + 1).coerceIn(0, h - 1) * w + x]
            sr += ((pi shr 16) and 255) - ((po shr 16) and 255)
            sg += ((pi shr 8) and 255) - ((po shr 8) and 255)
            sb += (pi and 255) - (po and 255)
        }
    }
}

// ======================= powierzchnia: szkło albo zwykły kolor =======================

/**
 * Tło karty / wiersza / przycisku. Bez Glass – dokładnie jak dotąd (kolor w kształcie).
 * Z Glass – kawałek rozmytego tła motywu pod spodem, odcień w kolorze motywu, połysk i jasna krawędź.
 * heavy = okna i nakładki na mapie (mocniejszy odcień, żeby tekst był czytelny).
 */
fun Modifier.carSurface(shape: Shape, color: Color = CarColors.Card, heavy: Boolean = false): Modifier =
    if (!GlassUi.on) this.background(color, shape)
    else glassSurface(shape, color == CarColors.Overlay, heavy) { color }

/**
 * Jak [carSurface], ale kolor czytany dopiero przy rysowaniu (miganie kafla) – zmienia się co klatkę
 * bez przeliczania całego kafla od nowa.
 */
fun Modifier.carSurfaceAnimated(shape: Shape, heavy: Boolean = false, colorOf: () -> Color): Modifier =
    if (!GlassUi.on) this.drawWithCache {
        val o = shape.createOutline(size, layoutDirection, this)
        onDrawBehind { drawOutline(o, colorOf()) }
    }
    else glassSurface(shape, false, heavy, colorOf)

private fun Modifier.glassSurface(shape: Shape, onMap: Boolean, heavy: Boolean, colorOf: () -> Color): Modifier =
    composed {
        val depth = LocalGlassDepth.current
        val light = CarColors.isLight
        val frost by Prefs.glassFrost.collectAsState()
        val pos = remember { mutableStateOf(Offset.Zero) }
        this.onGloballyPositioned { pos.value = it.positionInRoot() }
            .drawWithCache {
                val outline = shape.createOutline(size, layoutDirection, this)
                val path = Path().apply { addOutline(outline) }
                val clear = !frost
                val sheen = Brush.verticalGradient(
                    0f to Color.White.copy(alpha = if (light) 0.32f else if (clear) 0.13f else 0.10f),
                    0.45f to Color.Transparent,
                    startY = 0f, endY = size.height,
                )
                // gładkie szkło: jaśniejsza, ostrzejsza krawędź (światło z lewej u góry)
                val rim = Brush.linearGradient(
                    0f to Color.White.copy(alpha = if (light) 0.95f else if (clear) 0.75f else 0.48f),
                    0.45f to Color.White.copy(alpha = if (light) 0.35f else if (clear) 0.10f else 0.05f),
                    1f to Color.White.copy(alpha = if (light) 0.7f else if (clear) 0.38f else 0.20f),
                    start = Offset.Zero, end = Offset(size.width, size.height),
                )
                val stroke = Stroke((if (clear) 1.3f else 1f).dp.toPx())
                val glow = Stroke(5.dp.toPx())
                // radio jest słabe: gładkie szkło bez „soczewki” na krawędzi (pierścienie tła kosztowały dużo rysowania)
                val lens = emptyList<LensRing>()
                onDrawBehind {
                    val color = colorOf()
                    val blur = GlassUi.blurred
                    val sharp = GlassUi.sharp
                    val root = GlassUi.rootSize
                    // nakładki na mapie (Overlay) nie pokazują tapety – pod nimi jest mapa
                    val sample = depth == 0 && blur != null && root.width > 0 && !onMap
                    // odcień w kolorze motywu: na rozmytym tle lżejszy, bez tła (mapa, czerń) – mocniejszy;
                    // gładkie szkło – prawie przezroczyste (widać tło motywu pod kartą)
                    val a = when {
                        depth > 0 -> if (light) 0.30f else 0.0f
                        clear && !heavy && !onMap -> if (light) 0.30f else 0.20f
                        sample -> if (heavy) 0.62f else if (light) 0.52f else 0.40f
                        else -> if (heavy) 0.88f else 0.74f
                    }
                    clipPath(path) {
                        if (sample && (!clear || heavy)) {
                            val p = pos.value
                            drawImage(blur!!, dstOffset = IntOffset(-p.x.roundToInt(), -p.y.roundToInt()), dstSize = root, filterQuality = FilterQuality.Low)
                        }
                        if (lens.isNotEmpty() && sharp != null && root.width > 0) {
                            val p = pos.value
                            val c = Offset(size.width / 2f, size.height / 2f)
                            lens.forEach { r ->
                                clipPath(r.ring) {
                                    scale(r.sx, r.sy, pivot = c) {
                                        drawImage(sharp, dstOffset = IntOffset(-p.x.roundToInt(), -p.y.roundToInt()), dstSize = root, filterQuality = FilterQuality.Low)
                                    }
                                }
                            }
                        }
                        if (a > 0f) drawRect(color.copy(alpha = (color.alpha * a).coerceIn(0f, 1f)))
                        drawRect(Color.White.copy(alpha = if (depth > 0) (if (light) 0.0f else 0.06f) else if (light) 0.08f else if (clear) 0.03f else 0.05f))
                        drawRect(sheen)
                        // grubość szkła: miękka poświata od środka krawędzi
                        if (clear && depth == 0) drawOutline(outline, Color.White.copy(alpha = if (light) 0.18f else 0.07f), style = glow)
                    }
                    drawOutline(outline, rim, style = stroke)
                }
            }
    }

/** Pierścień przy krawędzi karty i skala, w jakiej rysujemy w nim tło (mniejsza = mocniej załamane). */
private class LensRing(val ring: Path, val sx: Float, val sy: Float)

/** 3 pierścienie od środka do krawędzi – im bliżej brzegu, tym więcej tła zza krawędzi (jak w soczewce). */
private fun glassLensRings(shape: Shape, size: Size, dir: LayoutDirection, d: Density): List<LensRing> {
    if (size.width < 24f || size.height < 24f) return emptyList()
    val bevel = minOf(with(d) { 16.dp.toPx() }, size.minDimension * 0.3f)
    val refr = with(d) { 9.dp.toPx() }
    val steps = 3
    fun inset(i: Float): Path {
        val w = size.width - 2 * i
        val h = size.height - 2 * i
        return Path().apply {
            addOutline(shape.createOutline(Size(w.coerceAtLeast(1f), h.coerceAtLeast(1f)), dir, d))
            translate(Offset(i, i))
        }
    }
    return (0 until steps).map { k ->
        val outer = bevel * (steps - k) / steps          // od środka ku krawędzi
        val inner = bevel * (steps - k - 1) / steps
        val ring = Path().apply { op(inset(inner), inset(outer), androidx.compose.ui.graphics.PathOperation.Difference) }
        // przesunięcie rośnie do krawędzi (^2)
        val t = (k + 1f) / steps
        val off = refr * t * t
        LensRing(ring, 1f - 2f * off / size.width, 1f - 2f * off / size.height)
    }
}

// ======================= kształt kropli =======================

/**
 * Kropla wzdłuż osi (poziomo albo pionowo): przednia i tylna „czapka” połączone gładko.
 * fc/rc – środki przodu i tyłu na osi, cc – środek w poprzek, rf/rr – promienie czapek.
 */
internal fun dropPath(fc: Float, rc: Float, cc: Float, rf: Float, rr: Float, dir: Int, vertical: Boolean): Path {
    val pts = ArrayList<Offset>(80)
    fun add(a: Float, c: Float) { pts += if (vertical) Offset(c, a) else Offset(a, c) }
    fun bez(a0: Float, c0: Float, a1: Float, c1: Float, a2: Float, c2: Float, a3: Float, c3: Float) {
        for (i in 0..14) {
            val t = i / 14f; val u = 1 - t
            add(u * u * u * a0 + 3 * u * u * t * a1 + 3 * u * t * t * a2 + t * t * t * a3, u * u * u * c0 + 3 * u * u * t * c1 + 3 * u * t * t * c2 + t * t * t * c3)
        }
    }
    val k = abs(fc - rc) * 0.5f
    bez(rc, cc - rr, rc + dir * k, cc - rr, fc - dir * k, cc - rf, fc, cc - rf)
    for (i in 1..18) { val th = Math.toRadians(-90.0 + 180.0 * i / 18).toFloat(); add(fc + dir * rf * cos(th), cc + rf * sin(th)) }
    bez(fc, cc + rf, fc - dir * k, cc + rf, rc + dir * k, cc + rr, rc, cc + rr)
    for (i in 1..17) { val th = Math.toRadians(90.0 - 180.0 * i / 18).toFloat(); add(rc - dir * rr * cos(th), cc + rr * sin(th)) }
    return Path().apply {
        moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
        close()
    }
}

internal fun DrawScope.drawDropGlass(path: Path, light: Boolean, lift: Float, vertical: Boolean, under: Boolean) {
    val l = lift.coerceIn(0f, 1f)
    if (under) {
        if (!light) translate(left = if (vertical) 2.dp.toPx() else 0f, top = if (vertical) 0f else 3.dp.toPx()) {
            drawPath(path, Color.Black.copy(alpha = 0.10f + 0.10f * l))
        }
        drawPath(path, Color.White.copy(alpha = if (light) 0.55f else 0.11f + 0.05f * l))
    } else {
        val b = path.getBounds()
        clipPath(path) {
            drawRect(
                Brush.verticalGradient(
                    0f to Color.White.copy(alpha = if (light) 0.40f else 0.20f),
                    0.5f to Color.Transparent,
                    1f to Color.White.copy(alpha = if (light) 0.18f else 0.06f),
                    startY = b.top, endY = b.bottom,
                ),
            )
        }
        drawPath(
            path,
            Brush.linearGradient(
                0f to Color.White.copy(alpha = if (light) 0.95f else 0.72f),
                0.5f to Color.White.copy(alpha = if (light) 0.35f else 0.10f),
                1f to Color.White.copy(alpha = if (light) 0.7f else 0.34f),
                start = b.topLeft, end = b.bottomRight,
            ),
            style = Stroke(1.3.dp.toPx()),
        )
    }
}

// ======================= kropla w menu =======================

/** Położenie elementów menu (w układzie ekranu), po których płynie kropla. */
class LiquidNavState(val vertical: Boolean) {
    val items = mutableStateMapOf<Int, Rect>()
}

@Composable
fun rememberLiquidNav(vertical: Boolean) = remember(vertical) { LiquidNavState(vertical) }

fun Modifier.liquidNavItem(st: LiquidNavState, i: Int): Modifier =
    this.onGloballyPositioned { st.items[i] = it.boundsInRoot() }

/**
 * Rysuje kroplę pod zaznaczonym elementem i przelewa ją do nowego (przód szybciej, tył dogania,
 * sprężynuje), a w czasie ruchu powiększa ikony pod sobą jak soczewka. selected < 0 – bez kropli.
 * inset – o ile kropla jest mniejsza od elementu (z każdej strony).
 */
fun Modifier.liquidNavHost(st: LiquidNavState, selected: Int, inset: Dp = 0.dp): Modifier = composed {
    val front = remember { Animatable(0f) }
    val rear = remember { Animatable(0f) }
    val cross = remember { Animatable(0f) }
    val len = remember { Animatable(0f) }
    val thick = remember { Animatable(0f) }
    val lift = remember { Animatable(0f) }
    val vis = remember { Animatable(0f) }
    var ready by remember { mutableStateOf(false) }
    var dir by remember { mutableStateOf(1) }
    val origin = remember { mutableStateOf(Offset.Zero) }
    val target = st.items[selected]
    val light = CarColors.isLight
    val glassAnim by Prefs.glassAnim.collectAsState()
    val allAnim by Prefs.animations.collectAsState()
    // animacje wyłączone (tu albo globalnie) – kropla od razu na miejscu, bez przelewania i lupy
    val anim = glassAnim && allAnim
    LaunchedEffect(selected, target) {
        if (target == null || selected < 0) { vis.animateTo(0f, spring(stiffness = 600f)); return@LaunchedEffect }
        val c = target.center
        val ax = if (st.vertical) c.y else c.x
        val cr = if (st.vertical) c.x else c.y
        val l = if (st.vertical) target.height else target.width
        val t = if (st.vertical) target.width else target.height
        if (!ready || vis.value < 0.05f) {
            front.snapTo(ax); rear.snapTo(ax); cross.snapTo(cr); len.snapTo(l); thick.snapTo(t)
            ready = true
            vis.animateTo(1f, spring(stiffness = 500f))
            return@LaunchedEffect
        }
        if (abs(front.value - ax) < 0.5f && abs(cross.value - cr) < 0.5f) {
            launch { len.animateTo(l) }; launch { thick.animateTo(t) }
            return@LaunchedEffect
        }
        dir = if (ax > front.value) 1 else -1
        if (!anim) {
            front.snapTo(ax); rear.snapTo(ax); cross.snapTo(cr); len.snapTo(l); thick.snapTo(t); lift.snapTo(0f)
            return@LaunchedEffect
        }
        launch { front.animateTo(ax, spring(dampingRatio = 0.78f, stiffness = 420f)) }
        launch { delay(45); rear.animateTo(ax, spring(dampingRatio = 0.55f, stiffness = 190f)) }
        launch { cross.animateTo(cr, spring(dampingRatio = 0.8f, stiffness = 500f)) }
        launch { len.animateTo(l, spring(dampingRatio = 0.8f, stiffness = 500f)) }
        launch { thick.animateTo(t, spring(dampingRatio = 0.8f, stiffness = 500f)) }
        launch {
            lift.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 900f))
            delay(170)
            lift.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = 260f))
        }
    }
    this.onGloballyPositioned { origin.value = it.positionInRoot() }
        .drawWithContent {
            val v = vis.value.coerceIn(0f, 1f)
            if (!ready || v < 0.02f) { drawContent(); return@drawWithContent }
            val o = origin.value
            val oa = if (st.vertical) o.y else o.x
            val oc = if (st.vertical) o.x else o.y
            val ins = inset.toPx()
            val l = (len.value - 2 * ins).coerceAtLeast(4f)
            val t = (thick.value - 2 * ins).coerceAtLeast(4f)
            val lf = lift.value.coerceIn(0f, 1f)
            val fc0 = front.value - oa
            val rc0 = rear.value - oa
            val gap = abs(fc0 - rc0)
            val s = min(1f, gap / max(l, 1f))
            val d = when { fc0 > rc0 + 0.5f -> 1; fc0 < rc0 - 0.5f -> -1; else -> dir }
            val r = min(l, t) / 2f * (1f + 0.04f * lf) * (0.6f + 0.4f * v)
            val rf = r * (1f - 0.06f * s)
            val rr = r * (1f - 0.30f * s)
            val fcap = fc0 + d * max(0f, l / 2f - rf)
            val rcap = rc0 - d * max(0f, l / 2f - rr)
            val path = dropPath(fcap, rcap, cross.value - oc, rf, rr, d, st.vertical)
            drawDropGlass(path, light, lf, st.vertical, under = true)
            val mag = 1f + 0.22f * lf
            if (mag > 1.01f) {
                val center = if (st.vertical) Offset(cross.value - oc, (fcap + rcap) / 2f) else Offset((fcap + rcap) / 2f, cross.value - oc)
                clipPath(path, ClipOp.Difference) { this@drawWithContent.drawContent() }
                clipPath(path) { scale(mag, center) { this@drawWithContent.drawContent() } }
            } else drawContent()
            drawDropGlass(path, light, lf, st.vertical, under = false)
        }
}

// ======================= przełącznik iOS 26 =======================

/**
 * Przełącznik jak w iOS 26: gałka-pastylka; przy zmianie zamienia się na chwilę w szklaną kroplę
 * (większą od toru, z powiększonym kolorem pod sobą), przeskakuje sprężyście i wraca do białej gałki.
 */
@Composable
fun GlassSwitch(on: Boolean) {
    val accent = LocalAccent.current
    val light = CarColors.isLight
    val pos = remember { Animatable(if (on) 1f else 0f) }
    val press = remember { Animatable(0f) }
    LaunchedEffect(on) {
        val t = if (on) 1f else 0f
        if (abs(pos.value - t) < 0.001f) return@LaunchedEffect
        launch {
            press.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 900f))
            delay(190)
            press.animateTo(0f, spring(dampingRatio = 0.55f, stiffness = 300f))
        }
        pos.animateTo(t, spring(dampingRatio = 0.62f, stiffness = 380f))
    }
    val off = if (light) Color(0xFF787880).copy(alpha = 0.24f) else Color.White.copy(alpha = 0.18f)
    androidx.compose.foundation.Canvas(Modifier.size(60.dp, 34.dp)) {
        // sprężyny przestrzeliwują – kolory i przezroczystość zawsze w 0..1
        val p = press.value.coerceIn(0f, 1f)
        val t = pos.value
        val trackCol = androidx.compose.ui.graphics.lerp(off, accent, t.coerceIn(0f, 1f))
        val cr = androidx.compose.ui.geometry.CornerRadius(size.height / 2f)
        drawRoundRect(trackCol, cornerRadius = cr)
        val kw = 34.dp.toPx(); val kh = 26.dp.toPx()
        val travel = size.width - 4.dp.toPx() - kw
        val kx = 2.dp.toPx() + t.coerceIn(-0.05f, 1.05f) * travel
        val cx = kx + kw / 2f; val cy = size.height / 2f
        val a = 1f - p
        if (a > 0.01f) {
            drawRoundRect(Color.Black.copy(alpha = 0.16f * a), Offset(kx, cy - kh / 2f + 1.5.dp.toPx()), Size(kw, kh), androidx.compose.ui.geometry.CornerRadius(kh / 2f))
            drawRoundRect(Color.White.copy(alpha = a), Offset(kx, cy - kh / 2f), Size(kw, kh), androidx.compose.ui.geometry.CornerRadius(kh / 2f))
        }
        if (p > 0.01f) {
            val lw = kw + 12.dp.toPx() * p; val lh = kh + 12.dp.toPx() * p
            val path = Path().apply {
                addRoundRect(androidx.compose.ui.geometry.RoundRect(cx - lw / 2f, cy - lh / 2f, cx + lw / 2f, cy + lh / 2f, androidx.compose.ui.geometry.CornerRadius(lh / 2f)))
            }
            translate(top = 2.dp.toPx()) { drawPath(path, Color.Black.copy(alpha = 0.18f * p)) }
            clipPath(path) {
                drawRect(Color.White.copy(alpha = (if (light) 0.35f else 0.10f) * p))
                // soczewka: tor pod kroplą powiększony
                scale(1.3f, Offset(cx, cy)) { drawRoundRect(trackCol.copy(alpha = trackCol.alpha * p), cornerRadius = cr) }
                drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.30f * p), 0.5f to Color.Transparent, startY = cy - lh / 2f, endY = cy + lh / 2f))
            }
            drawPath(
                path,
                Brush.linearGradient(
                    0f to Color.White.copy(alpha = 0.85f * p), 0.5f to Color.White.copy(alpha = 0.12f * p), 1f to Color.White.copy(alpha = 0.45f * p),
                    start = Offset(cx - lw / 2f, cy - lh / 2f), end = Offset(cx + lw / 2f, cy + lh / 2f),
                ),
                style = Stroke(1.3.dp.toPx()),
            )
        }
    }
}

// ======================= wybór stylu szkła (jak w telefonie) =======================

/** „Styl wizualny”: gładkie szkło (przejrzyste, załamanie na krawędziach) albo matowe (rozmyte). */
@Composable
fun GlassStylePicker() {
    val frost by Prefs.glassFrost.collectAsState()
    androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
        androidx.compose.material3.Text("Styl szkła", fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = CarColors.Text)
        androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)) {
            GlassStyleTile("Gładkie szkło", "Przejrzyste, tło zagina się na krawędziach", sel = !frost, frost = false) { Prefs.setGlassFrost(false) }
            GlassStyleTile("Matowe szkło", "Rozmyte tło, lepiej czytelne", sel = frost, frost = true) { Prefs.setGlassFrost(true) }
        }
    }
}

@Composable
private fun GlassStyleTile(title: String, sub: String, sel: Boolean, frost: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(26.dp)
    androidx.compose.foundation.layout.Column(
        Modifier.width(300.dp).clip(shape).clickable(onClick = onClick).padding(bottom = 6.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
    ) {
        androidx.compose.foundation.Canvas(
            Modifier.size(width = 300.dp, height = 176.dp)
                .border(if (sel) 3.dp else 1.dp, if (sel) accent else CarColors.Border, shape)
                .padding(6.dp),
        ) { drawGlassPreview(frost) }
        androidx.compose.material3.Text(title, fontSize = 19.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = if (sel) accent else CarColors.Text)
        androidx.compose.material3.Text(sub, fontSize = 14.sp, color = CarColors.Muted, maxLines = 1)
    }
}

/** Podgląd: niebieskie fale (jak tapeta w telefonie) i szklana kapsuła na środku. */
private fun DrawScope.drawGlassPreview(frost: Boolean) {
    val w = size.width
    val h = size.height
    val r = 22.dp.toPx()
    val bg = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, w, h, androidx.compose.ui.geometry.CornerRadius(r))) }
    fun scene(dx: Float = 0f, dy: Float = 0f, sx: Float = 1f, sy: Float = 1f) {
        scale(sx, sy, pivot = Offset(w / 2f, h / 2f)) {
            translate(dx, dy) {
                drawRect(Brush.linearGradient(listOf(Color(0xFFDDE6F3), Color(0xFF8FB8EA), Color(0xFF2E6BD6)), Offset(0f, 0f), Offset(w, h)))
                val p1 = Path().apply { moveTo(-0.1f * w, 0.25f * h); cubicTo(0.35f * w, 0.05f * h, 0.55f * w, 0.95f * h, 1.1f * w, 0.75f * h) }
                drawPath(p1, Color(0xFF1C47B8), style = Stroke(0.07f * h))
                val p2 = Path().apply { moveTo(0.75f * w, -0.1f * h); cubicTo(0.9f * w, 0.3f * h, 0.85f * w, 0.7f * h, 0.98f * w, 1.1f * h) }
                drawPath(p2, Color(0xFF0B2E8F), style = Stroke(0.05f * h))
                drawCircle(Color(0xFF4A86E0), radius = 0.45f * h, center = Offset(0.15f * w, 1.05f * h))
            }
        }
    }
    clipPath(bg) {
        scene()
        // kapsuła
        val pw = w * 0.72f
        val ph = h * 0.34f
        val px = (w - pw) / 2f
        val py = (h - ph) / 2f
        val pill = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(px, py, px + pw, py + ph, androidx.compose.ui.geometry.CornerRadius(ph / 2f))) }
        clipPath(pill) {
            if (frost) {
                // matowe: mleczne i rozmyte (przybliżenie: kilka przesuniętych kopii + mleko)
                for (i in -2..2) for (j in -1..1) scene(i * 6f, j * 6f)
                drawRect(Color.White.copy(alpha = 0.42f))
            } else {
                // gładkie: ostre tło, przy krawędzi zagięte (pierścienie z rosnącą skalą)
                scene()
                val steps = 4
                for (k in 0 until steps) {
                    val t = (k + 1f) / steps
                    val ins = ph * 0.32f * (1f - t)
                    val ring = Path().apply {
                        val inner = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(px + ins + 4f, py + ins + 4f, px + pw - ins - 4f, py + ph - ins - 4f, androidx.compose.ui.geometry.CornerRadius((ph / 2f - ins - 4f).coerceAtLeast(1f)))) }
                        val outer = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(px + ins, py + ins, px + pw - ins, py + ph - ins, androidx.compose.ui.geometry.CornerRadius((ph / 2f - ins).coerceAtLeast(1f)))) }
                        op(outer, inner, androidx.compose.ui.graphics.PathOperation.Difference)
                    }
                    val off = 14f * t * t
                    clipPath(ring) { scene(sx = 1f - 2f * off / pw, sy = 1f - 2f * off / ph) }
                }
                drawRect(Color.White.copy(alpha = 0.10f))
            }
            drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.35f), 0.5f to Color.Transparent, startY = py, endY = py + ph))
        }
        drawPath(pill, Brush.linearGradient(listOf(Color.White, Color.White.copy(alpha = 0.2f), Color.White.copy(alpha = 0.7f)), Offset(px, py), Offset(px + pw, py + ph)), style = Stroke(2.dp.toPx()))
    }
}
