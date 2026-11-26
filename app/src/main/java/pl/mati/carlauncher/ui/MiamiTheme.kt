package pl.mati.carlauncher.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.mati.carlauncher.data.LocationRepository
import pl.mati.carlauncher.data.Prefs
import pl.mati.carlauncher.data.SpeedLimitRepository
import pl.mati.carlauncher.data.Vehicle
import pl.mati.carlauncher.data.WeatherRepository
import kotlin.math.cos
import kotlin.math.sin

/*
 * Motyw „Miami”: zachód słońca nad oceanem, panorama miasta, palmy; wieczorem neon, w dzień pastele.
 * Mapa w stylu Miami (palmy, realistyczne budynki), duża prędkość w kolorach zachodu, odtwarzacz z poświatą.
 */

val MIAMI_PINK = Color(0xFFFF4FA3)
private val MIAMI_ORANGE = Color(0xFFFF9A3D)
private val MIAMI_GOLD = Color(0xFFFFD54A)
private val MIAMI_TEAL = Color(0xFF22D3E6)

/** Gradient zachodu (napisy, ramki). */
val MiamiSunset = Brush.linearGradient(listOf(MIAMI_GOLD, MIAMI_ORANGE, MIAMI_PINK))
private val MiamiFrame = Brush.linearGradient(listOf(MIAMI_PINK, MIAMI_ORANGE, MIAMI_TEAL))

// ======================= tło =======================

@Composable
fun MiamiBackground() {
    val light = CarColors.isLight
    Canvas(Modifier.fillMaxSize()) { drawMiami(size.width, size.height, light) }
}

/** Niebo, słońce, panorama, ocean z odbiciem i palmy (to samo tło liczy rozmycie pod szkłem). */
fun DrawScope.drawMiami(w: Float, h: Float, light: Boolean) {
    val horizon = h * 0.64f
    // niebo
    drawRect(
        Brush.verticalGradient(
            if (light) listOf(Color(0xFF7ED3F0), Color(0xFFBFE6F2), Color(0xFFFFD6B8), Color(0xFFFFB3C2))
            else listOf(Color(0xFF0E0624), Color(0xFF2E0E58), Color(0xFF9A2C78), Color(0xFFFF7A5C)),
            0f, horizon,
        ),
        size = Size(w, horizon),
    )
    if (!light) {
        // gwiazdy
        for (i in 0 until 40) {
            val x = ((i * 7919) % 1000) / 1000f * w
            val y = ((i * 104729) % 1000) / 1000f * horizon * 0.45f
            drawCircle(Color.White.copy(alpha = 0.25f + (i % 4) * 0.12f), 1.2f + (i % 3) * 0.6f, Offset(x, y))
        }
    }
    // słońce z poświatą
    val sc = Offset(w * 0.62f, if (light) horizon - h * 0.26f else horizon)
    val r = h * (if (light) 0.09f else 0.17f)
    drawCircle(
        Brush.radialGradient(listOf((if (light) Color(0xFFFFF4C8) else Color(0xFFFF7A9A)).copy(alpha = 0.55f), Color.Transparent), sc, r * 3.2f),
        r * 3.2f, sc,
    )
    clipRect(bottom = horizon) {
        drawCircle(
            Brush.verticalGradient(
                if (light) listOf(Color(0xFFFFFBE6), Color(0xFFFFE9A8)) else listOf(Color(0xFFFFE89A), Color(0xFFFF9A5C), Color(0xFFFF4F8A)),
                sc.y - r, sc.y + r,
            ),
            r, sc,
        )
    }
    // panorama miasta na horyzoncie
    val sky1 = if (light) Color(0xFFE9B8CC) else Color(0xFF3A1250)
    val sky2 = if (light) Color(0xFFD3B8E8) else Color(0xFF250A3C)
    var x = w * 0.08f
    var i = 0
    while (x < w * 0.95f) {
        val bw = w * (0.018f + (i * 37 % 5) * 0.006f)
        val bh = h * (0.03f + (i * 53 % 9) * 0.014f + if (i % 7 == 3) 0.09f else 0f)
        val col = if (i % 2 == 0) sky1 else sky2
        drawRect(col, Offset(x, horizon - bh), Size(bw, bh))
        if (!light) {
            // świecące okna
            var wy = horizon - bh + 4f
            while (wy < horizon - 3f) {
                var wx = x + 2f
                while (wx < x + bw - 2f) {
                    if (((wx * 13 + wy * 7).toInt() % 5) == 0) drawRect(Color(0xFFFFC58A).copy(alpha = 0.8f), Offset(wx, wy), Size(2f, 2f))
                    wx += 5f
                }
                wy += 6f
            }
        }
        x += bw + w * (0.002f + (i % 3) * 0.004f)
        i++
    }
    // ocean z odbiciem słońca
    drawRect(
        Brush.verticalGradient(
            if (light) listOf(Color(0xFF46CFE0), Color(0xFF1E9DC0)) else listOf(Color(0xFF3A1458), Color(0xFF120624)),
            horizon, h,
        ),
        Offset(0f, horizon), Size(w, h - horizon),
    )
    for (k in 0 until 14) {
        val t = k / 14f
        val y = horizon + 6f + (h - horizon) * t * t * 0.9f
        val half = r * (1.1f - t * 0.6f) * (if (k % 2 == 0) 1f else 0.7f)
        drawRect(
            (if (light) Color.White else Color(0xFFFF8A9A)).copy(alpha = 0.55f * (1f - t)),
            Offset(sc.x - half, y), Size(half * 2, 2.5f + t * 3f),
        )
    }
    drawLine((if (light) Color.White else Color(0xFFFF9AB8)).copy(alpha = 0.7f), Offset(0f, horizon), Offset(w, horizon), 1.5f)
    // palmy po bokach
    val palm = if (light) Color(0xFF1F4A4E) else Color(0xFF0A0414)
    drawPalm(w * 0.06f, h, h * 0.78f, 0.16f, palm)
    drawPalm(w * 0.13f, h, h * 0.55f, -0.08f, palm)
    drawPalm(w * 0.93f, h, h * 0.7f, -0.18f, palm)
    drawPalm(w * 0.985f, h, h * 0.92f, -0.05f, palm)
}

/** Sylwetka palmy: wygięty pień i liście. [lean] – pochylenie (część wysokości). */
private fun DrawScope.drawPalm(x: Float, base: Float, height: Float, lean: Float, col: Color) {
    val topX = x + lean * height
    val topY = base - height
    val trunk = Path().apply {
        moveTo(x - height * 0.022f, base)
        quadraticBezierTo(x + lean * height * 0.15f, base - height * 0.5f, topX - height * 0.01f, topY)
        lineTo(topX + height * 0.01f, topY)
        quadraticBezierTo(x + lean * height * 0.15f + height * 0.02f, base - height * 0.5f, x + height * 0.022f, base)
        close()
    }
    drawPath(trunk, col)
    val len = height * 0.36f
    for (k in 0 until 9) {
        val a = Math.toRadians(-185.0 + k * 23.0)
        val ex = topX + (cos(a) * len).toFloat()
        val ey = topY + (sin(a) * len * 0.45f).toFloat() + len * 0.42f * (1f - kotlin.math.abs(sin(a)).toFloat())
        val mx = topX + (cos(a) * len * 0.5).toFloat()
        val my = topY + (sin(a) * len * 0.7).toFloat() - len * 0.1f
        val leaf = Path().apply {
            moveTo(topX, topY)
            quadraticBezierTo(mx, my - len * 0.06f, ex, ey)
            quadraticBezierTo(mx, my + len * 0.08f, topX, topY + len * 0.03f)
            close()
        }
        drawPath(leaf, col)
    }
}

// ======================= ekran główny =======================

@Composable
fun HomeMiami() {
    val keysStr by Prefs.homeTiles.collectAsState()
    val keys = remember(keysStr) { Prefs.homeTiles.value.split(",").filter { k -> TILE_DEFS.any { it.first == k } && k != "speed" }.distinct().take(2) }
    Row(
        Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 56.dp, bottom = 120.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            Modifier.weight(1.45f).fillMaxHeight().clip(RoundedCornerShape(26.dp))
                .border(2.dp, MiamiFrame, RoundedCornerShape(26.dp)),
        ) { MapCard(Modifier.fillMaxSize(), showSpeed = false) }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            MiamiSpeed(Modifier.fillMaxWidth())
            MiamiMedia(Modifier.fillMaxWidth().weight(1f))
            if (keys.isNotEmpty()) CompositionLocalProvider(LocalMinimalTiles provides true) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    keys.forEach { k ->
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).carSurface(RoundedCornerShape(18.dp), CarColors.Card, heavy = true)
                                .padding(horizontal = 6.dp),
                        ) { TileContent(k, Modifier.fillMaxWidth(), 26.sp) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiamiSpeed(modifier: Modifier) {
    val d by Vehicle.data.collectAsState()
    val gps by LocationRepository.gpsSpeed.collectAsState()
    val lim by SpeedLimitRepository.limit.collectAsState()
    val over by SpeedLimitRepository.over.collectAsState()
    val temp by WeatherRepository.temperature.collectAsState()
    val now by rememberNow()
    val speed = d.speed ?: gps
    val shape = RoundedCornerShape(24.dp)
    Row(
        modifier.clip(shape).carSurface(shape, CarColors.Card, heavy = true).border(1.5.dp, MiamiFrame, shape)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            speed?.toString() ?: "--",
            style = TextStyle(brush = MiamiSunset, fontSize = 88.sp, fontFamily = CarColors.theme.numFont, lineHeight = 92.sp),
        )
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Text("km/h", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = CarColors.Muted)
            lim?.let {
                Box(
                    Modifier.padding(top = 6.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (over) Color(0xFFFF4D6D) else MIAMI_PINK.copy(alpha = 0.18f))
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                ) { Text("limit $it", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = if (over) Color.White else MIAMI_PINK) }
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(formatTime(now), fontSize = 34.sp, fontWeight = FontWeight.Bold, fontFamily = CarColors.theme.numFont, color = CarColors.Text)
            temp?.let { Text("$it°", fontSize = 20.sp, color = MIAMI_TEAL) }
        }
    }
}

@Composable
private fun MiamiMedia(modifier: Modifier) {
    val m = rememberNowPlaying()
    val shape = RoundedCornerShape(24.dp)
    val glow: androidx.compose.runtime.State<Float>? = if (m.playing) {
        val t = rememberInfiniteTransition(label = "mm")
        t.animateFloat(0.35f, 0.8f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "mmg")
    } else null
    Column(
        modifier.clip(shape).carSurface(shape, CarColors.Card, heavy = true).clickable { UiState.go(Screen.MUSIC) }.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.weight(1f)) {
            Box(
                Modifier.size(104.dp).drawBehind {
                    // poświata pod okładką – pulsuje, gdy gra muzyka
                    val a = glow?.value ?: 0.3f
                    drawCircle(Brush.radialGradient(listOf(MIAMI_PINK.copy(alpha = a), Color.Transparent)), size.minDimension * 0.75f)
                },
                contentAlignment = Alignment.Center,
            ) { AlbumArt(m.art, 92.dp, 18.dp) }
            Column(Modifier.weight(1f)) {
                Text(m.title ?: "Nic nie gra", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = CarColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(m.artist ?: m.appName ?: "", fontSize = 16.sp, color = CarColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(Icons.Rounded.SkipPrevious, "Poprzedni", 56.dp, { Player.previous(m) })
            Box(
                Modifier.size(66.dp).clip(RoundedCornerShape(33.dp)).background(MiamiSunset).clickable { Player.playPause(m) },
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Icon(
                    if (m.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (m.playing) "Pauza" else "Odtwórz",
                    tint = Color.White, modifier = Modifier.size(36.dp),
                )
            }
            RoundIconButton(Icons.Rounded.SkipNext, "Następny", 56.dp, { Player.next(m) })
        }
    }
}

/** Mała makieta motywu Miami (wybór motywu w Ustawieniach). */
@Composable
fun MiamiPreview(m: Modifier) {
    Box(m) {
        Canvas(Modifier.fillMaxSize()) { drawMiami(size.width, size.height, false) }
        Row(Modifier.fillMaxSize().padding(start = 6.dp, end = 6.dp, top = 8.dp, bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.weight(1.4f).fillMaxHeight().clip(RoundedCornerShape(6.dp)).background(Color(0xFF22143A)).border(1.dp, MiamiFrame, RoundedCornerShape(6.dp)))
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xCC24143E))) {
                    Box(Modifier.padding(3.dp).size(width = 20.dp, height = 10.dp).background(MiamiSunset))
                }
                Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(5.dp)).background(Color(0xCC24143E)))
            }
        }
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp).size(width = 50.dp, height = 9.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xE61A0B2E)))
    }
}
