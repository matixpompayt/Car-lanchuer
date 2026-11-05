package pl.mati.carlauncher.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.mati.carlauncher.data.FuelRepository
import pl.mati.carlauncher.data.LocationRepository
import pl.mati.carlauncher.data.Prefs
import pl.mati.carlauncher.data.SpeedLimitRepository
import pl.mati.carlauncher.data.TripRepository
import pl.mati.carlauncher.data.Vehicle
import pl.mati.carlauncher.data.WeatherRepository
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private fun homeKeys(): List<String> =
    Prefs.homeTiles.value.split(",").filter { k -> TILE_DEFS.any { it.first == k } }.distinct().take(MAX_TILES)

// =====================================================================
// VW Nocny – dwa zegary jak w Touranie, mapa pośrodku, wyświetlacz MFA
// =====================================================================

private val MFA_RED = Color(0xFFFF4A3D)

@Composable
fun HomeVw() {
    val d by Vehicle.data.collectAsState()
    val gps by LocationRepository.gpsSpeed.collectAsState()
    val temp by WeatherRepository.temperature.collectAsState()
    val fs by FuelRepository.stats.collectAsState()
    val lim by SpeedLimitRepository.limit.collectAsState()
    val trips by TripRepository.trips.collectAsState()
    val now by rememberNow()
    val speed = d.speed ?: gps
    val today = androidx.compose.runtime.remember(trips.size) { TripRepository.summary(TripRepository.startOfDay()).distanceM / 1000 }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.width(286.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RoundGauge(speed?.toFloat(), 220f, 20f, 1f, null, speed?.toString() ?: "--", "km/h", Modifier.size(278.dp))
            VwMfa(
                listOf(
                    "GODZ." to formatTime(now),
                    "TEMP. ZEWN." to ((d.outside ?: temp)?.let { "$it °C" } ?: "--"),
                    "ZASIĘG" to (fs.rangeKm?.let { "${it.roundToInt()} km" } ?: "--"),
                    "DZIŚ" to String.format(PL, "%.1f km", today),
                ),
                Modifier.fillMaxWidth().weight(1f),
            )
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MapCard(Modifier.weight(1f).fillMaxWidth(), showSpeed = false)
            VwMediaStrip(Modifier.fillMaxWidth().height(76.dp))
        }
        Column(Modifier.width(286.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val rpm = d.rpm
            RoundGauge(rpm?.let { it / 1000f }, 6f, 1f, 1f, 4.5f, rpm?.toString() ?: "--", "obr/min", Modifier.size(278.dp))
            VwMfa(
                listOf(
                    "PŁYN CHŁ." to (d.coolant?.let { "$it °C" } ?: "--"),
                    "NAPIĘCIE" to (d.voltage?.let { String.format(PL, "%.1f V", it) } ?: "--"),
                    "DOŁADOWANIE" to (d.boostBar?.let { String.format(PL, "%.1f bar", it) } ?: "--"),
                    "OGRANICZENIE" to (lim?.let { "$it km/h" } ?: "--"),
                ),
                Modifier.fillMaxWidth().weight(1f),
            )
        }
    }
}

/** Okrągły zegar: skala w kolorze tekstu, czerwona wskazówka i strefa (jak w VW). */
@Composable
fun RoundGauge(
    value: Float?, max: Float, step: Float, labelDiv: Float, redFrom: Float?,
    digital: String, unit: String, modifier: Modifier,
) {
    val accent = LocalAccent.current
    val scale = CarColors.Text2
    val tm = rememberTextMeasurer()
    val v by animateFloatAsState((value ?: 0f).coerceIn(0f, max), spring(dampingRatio = 0.8f, stiffness = 120f), label = "gauge")
    val numStyle = TextStyle(color = scale, fontSize = 17.sp, fontFamily = Condensed)
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2 * 0.95f
            val c = center
            val start = 135f
            val sweep = 270f
            fun polar(deg: Float, rad: Float) = Offset(c.x + cos(Math.toRadians(deg.toDouble())).toFloat() * rad, c.y + sin(Math.toRadians(deg.toDouble())).toFloat() * rad)
            val ring = 3.dp.toPx()
            drawArc(scale.copy(alpha = 0.35f), start, sweep, false, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = Stroke(ring))
            redFrom?.let {
                val a = start + sweep * (it / max)
                val rr = r - 6.dp.toPx()
                drawArc(accent.copy(alpha = 0.75f), a, start + sweep - a, false, Offset(c.x - rr, c.y - rr), Size(rr * 2, rr * 2), style = Stroke(7.dp.toPx()))
            }
            val majors = (max / step).roundToInt()
            for (i in 0..majors * 2) {
                val deg = start + sweep * (i * step / 2f / max)
                val major = i % 2 == 0
                val inner = r - (if (major) 20.dp else 11.dp).toPx()
                drawLine(scale, polar(deg, inner), polar(deg, r - 2.dp.toPx()), if (major) 3.dp.toPx() else 1.5.dp.toPx())
            }
            for (i in 0..majors) {
                val deg = start + sweep * (i * step / max)
                val txt = ((i * step) / labelDiv).roundToInt().toString()
                val l = tm.measure(txt, numStyle)
                val p = polar(deg, r - 40.dp.toPx())
                drawText(l, topLeft = Offset(p.x - l.size.width / 2f, p.y - l.size.height / 2f))
            }
            val deg = start + sweep * (v / max)
            drawLine(accent.copy(alpha = 0.3f), polar(deg, -12.dp.toPx()), polar(deg, r - 22.dp.toPx()), 11.dp.toPx(), cap = StrokeCap.Round)
            drawLine(accent, polar(deg, -12.dp.toPx()), polar(deg, r - 22.dp.toPx()), 4.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(Color(0xFF15181D), 15.dp.toPx(), c)
            drawCircle(accent, 6.dp.toPx(), c)
        }
        Column(Modifier.align(Alignment.Center).padding(top = 130.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(digital, fontSize = 30.sp, fontFamily = Condensed, color = CarColors.Text)
            Text(unit, fontSize = 13.sp, color = CarColors.Muted)
        }
    }
}

/** Czerwony wyświetlacz MFA jak w zegarach VW. */
@Composable
private fun VwMfa(lines: List<Pair<String, String>>, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(6.dp)).background(Color.Black).border(1.dp, CarColors.Border, RoundedCornerShape(6.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        lines.forEach { (k, v) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(k, fontSize = 14.sp, fontFamily = MONO, color = MFA_RED.copy(alpha = 0.7f), modifier = Modifier.weight(1f), maxLines = 1)
                Text(v, fontSize = 18.sp, fontFamily = MONO, color = MFA_RED, maxLines = 1)
            }
        }
    }
}

@Composable
private fun VwMediaStrip(modifier: Modifier) {
    val m = rememberNowPlaying()
    val accent = LocalAccent.current
    Row(
        modifier.clip(RoundedCornerShape(6.dp)).background(CarColors.Card).border(1.dp, CarColors.Border, RoundedCornerShape(6.dp))
            .clickable { UiState.go(Screen.MUSIC) }.padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("MEDIA", fontSize = 14.sp, letterSpacing = 2.sp, color = CarColors.Muted)
        Column(Modifier.weight(1f)) {
            Text(m.title ?: "Nic nie gra", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(m.artist ?: m.appName ?: "", fontSize = 14.sp, color = CarColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        RoundIconButton(Icons.Rounded.SkipPrevious, "Poprzedni", 50.dp, { Player.previous(m) }, tint = CarColors.Text2)
        RoundIconButton(if (m.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (m.playing) "Pauza" else "Odtwórz", 50.dp, { Player.playPause(m) }, tint = accent)
        RoundIconButton(Icons.Rounded.SkipNext, "Następny", 50.dp, { Player.next(m) }, tint = CarColors.Text2)
    }
}

// =====================================================================
// Sport – wielka prędkość, telemetria w paskach, mapa obok
// =====================================================================

@Composable
fun HomeSport() {
    val d by Vehicle.data.collectAsState()
    val gps by LocationRepository.gpsSpeed.collectAsState()
    val lim by SpeedLimitRepository.limit.collectAsState()
    val over by SpeedLimitRepository.over.collectAsState()
    val road by SpeedLimitRepository.roadName.collectAsState()
    val accent = LocalAccent.current
    val speed = d.speed ?: gps
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    speed?.toString() ?: "--", fontSize = 140.sp, lineHeight = 140.sp, fontFamily = Condensed,
                    color = if (over) Color(0xFFFF1E1E) else CarColors.Text,
                )
                Column(Modifier.padding(start = 10.dp, bottom = 22.dp)) {
                    Text("KM/H", fontSize = 22.sp, fontFamily = Condensed, color = accent)
                    lim?.let {
                        Box(
                            Modifier.padding(top = 6.dp).size(54.dp).clip(CircleShape).background(Color.White).border(6.dp, Color(0xFFE0262B), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { Text(it.toString(), color = Color(0xFF111111), fontWeight = FontWeight.Bold, fontSize = 20.sp) }
                    }
                }
            }
            Text((road ?: "").uppercase(PL), fontSize = 15.sp, letterSpacing = 1.5.sp, color = CarColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            TelemetryBar("OBROTY", d.rpm?.toFloat(), 0f, 4500f, d.rpm?.toString() ?: "--", "obr/min", 4000f)
            TelemetryBar("DOŁADOWANIE", d.boostBar, 0f, 2f, d.boostBar?.let { String.format(PL, "%.2f", it) } ?: "--", "bar", null)
            TelemetryBar("PŁYN CHŁODZĄCY", d.coolant?.toFloat(), 40f, 120f, d.coolant?.toString() ?: "--", "°C", 105f)
            TelemetryBar("NAPIĘCIE", d.voltage, 11f, 15f, d.voltage?.let { String.format(PL, "%.1f", it) } ?: "--", "V", null)
            Spacer(Modifier.weight(1f))
            MiniPlayer(Modifier.fillMaxWidth().height(80.dp))
        }
        MapCard(Modifier.weight(1.15f).fillMaxHeight(), showSpeed = false)
    }
}

/** Pasek telemetrii z pochylonych segmentów. */
@Composable
private fun TelemetryBar(label: String, value: Float?, min: Float, max: Float, text: String, unit: String, warnFrom: Float?) {
    val accent = LocalAccent.current
    val frac by animateFloatAsState(value?.let { ((it - min) / (max - min)).coerceIn(0f, 1f) } ?: 0f, label = "tb")
    val warn = warnFrom != null && value != null && value >= warnFrom
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(label, fontSize = 13.sp, letterSpacing = 1.5.sp, color = CarColors.Muted, modifier = Modifier.weight(1f))
            Text(text, fontSize = 24.sp, fontFamily = Condensed, color = if (warn) Color(0xFFFF1E1E) else CarColors.Text)
            Text(" $unit", fontSize = 13.sp, color = CarColors.Muted, modifier = Modifier.padding(bottom = 3.dp))
        }
        Canvas(Modifier.fillMaxWidth().height(12.dp)) {
            val n = 30
            val gap = 3.dp.toPx()
            val w = (size.width - gap * (n - 1)) / n
            val lit = (frac * n).roundToInt()
            val skew = size.height * 0.5f
            for (i in 0 until n) {
                val x = i * (w + gap)
                val col = if (i < lit) (if (warn) Color(0xFFFF1E1E) else accent) else Color.White.copy(alpha = 0.07f)
                val p = androidx.compose.ui.graphics.Path().apply {
                    moveTo(x + skew, 0f); lineTo(x + w + skew, 0f); lineTo(x + w, size.height); lineTo(x, size.height); close()
                }
                drawPath(p, col)
            }
        }
    }
}

// =====================================================================
// Pełna mapa – mapa pod wszystkim, pływające okienka
// =====================================================================

@Composable
fun HomeFullMap() {
    val guide by pl.mati.carlauncher.data.Guidance.state.collectAsState()
    val gnav by pl.mati.carlauncher.data.NavRepository.state.collectAsState()
    val keysStr by Prefs.homeTiles.collectAsState()
    val keys = androidx.compose.runtime.remember(keysStr) { homeKeys() }
    Box(Modifier.fillMaxSize()) {
        MapCard(Modifier.fillMaxSize(), topPad = 46.dp, bottomPad = 92.dp)
        if (guide == null && gnav == null) FloatingMedia(Modifier.align(Alignment.TopCenter).padding(top = 54.dp))
        CompositionLocalProvider(LocalMinimalTiles provides true) {
            Column(Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 188.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                keys.take(4).forEach { k ->
                    Box(Modifier.width(184.dp).clip(RoundedCornerShape(20.dp)).background(CarColors.Overlay).padding(horizontal = 6.dp, vertical = 2.dp)) {
                        TileContent(k, Modifier.fillMaxWidth(), 26.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun FloatingMedia(modifier: Modifier) {
    val m = rememberNowPlaying()
    Row(
        modifier.width(520.dp).clip(RoundedCornerShape(30.dp)).background(CarColors.Overlay)
            .clickable { UiState.go(Screen.MUSIC) }.padding(start = 8.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AlbumArt(m.art, 46.dp, 23.dp)
        Column(Modifier.weight(1f)) {
            Text(m.title ?: "Nic nie gra", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(m.artist ?: m.appName ?: "", fontSize = 13.sp, color = CarColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        RoundIconButton(Icons.Rounded.SkipPrevious, "Poprzedni", 46.dp, { Player.previous(m) })
        RoundIconButton(if (m.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (m.playing) "Pauza" else "Odtwórz", 46.dp, { Player.playPause(m) }, filled = true)
        RoundIconButton(Icons.Rounded.SkipNext, "Następny", 46.dp, { Player.next(m) })
    }
}

// =====================================================================
// Retro 80s – neonowa prędkość, kaseta, mapa w neonowej ramce
// =====================================================================

private val NEON_PINK = Color(0xFFFF2E97)
private val NEON_CYAN = Color(0xFF00E5FF)

@Composable
fun HomeRetro() {
    val keysStr by Prefs.homeTiles.collectAsState()
    val keys = androidx.compose.runtime.remember(keysStr) { homeKeys() }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            MapCard(Modifier.weight(1.35f).fillMaxHeight(), showSpeed = false)
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                RetroSpeed(Modifier.fillMaxWidth())
                CassettePlayer(Modifier.fillMaxWidth().weight(1f))
            }
        }
        if (keys.isNotEmpty()) CompositionLocalProvider(LocalMinimalTiles provides true) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                keys.take(4).forEach { k ->
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(CarColors.Card)
                            .border(1.5.dp, NEON_PINK, RoundedCornerShape(12.dp)).padding(horizontal = 6.dp),
                    ) { TileContent(k, Modifier.fillMaxWidth(), 30.sp) }
                }
            }
        }
    }
}

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
@Composable
private fun RetroSpeed(modifier: Modifier) {
    val d by Vehicle.data.collectAsState()
    val gps by LocationRepository.gpsSpeed.collectAsState()
    val lim by SpeedLimitRepository.limit.collectAsState()
    val over by SpeedLimitRepository.over.collectAsState()
    val now by rememberNow()
    val speed = d.speed ?: gps
    Row(
        modifier.clip(RoundedCornerShape(12.dp)).background(CarColors.Card).border(1.5.dp, NEON_PINK, RoundedCornerShape(12.dp))
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            speed?.toString()?.padStart(3, ' ') ?: " --",
            style = TextStyle(
                brush = Brush.verticalGradient(listOf(Color(0xFFFFE259), Color(0xFFFF7A3D), NEON_PINK)),
                fontSize = 92.sp, fontFamily = MONO, lineHeight = 96.sp,
            ),
        )
        Column(Modifier.weight(1f).padding(start = 12.dp), horizontalAlignment = Alignment.End) {
            Text("KM/H", fontSize = 20.sp, fontFamily = MONO, color = NEON_CYAN)
            Text(lim?.let { "LIMIT $it" } ?: "LIMIT --", fontSize = 18.sp, fontFamily = MONO, color = if (over) Color(0xFFFF3B6B) else NEON_CYAN.copy(alpha = 0.75f))
            Text(formatTime(now), fontSize = 26.sp, fontFamily = MONO, color = CarColors.Text)
        }
    }
}

/** Odtwarzacz jako kaseta: szpule kręcą się, gdy gra muzyka. */
@Composable
private fun CassettePlayer(modifier: Modifier) {
    val m = rememberNowPlaying()
    val anim by Prefs.animations.collectAsState()
    val t = rememberInfiniteTransition(label = "reels")
    val rot = t.animateFloat(0f, 360f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "rot")
    val playing = m.playing && anim
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(CarColors.Card).border(1.5.dp, NEON_PINK, RoundedCornerShape(12.dp))
            .clickable { UiState.go(Screen.MUSIC) }.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                drawRoundRect(Color(0xFF2A1050), cornerRadius = CornerRadius(14.dp.toPx()))
                drawRoundRect(NEON_PINK.copy(alpha = 0.6f), cornerRadius = CornerRadius(14.dp.toPx()), style = Stroke(1.5.dp.toPx()))
                // naklejka
                drawRoundRect(Color(0xFFF8F0FF), Offset(w * 0.05f, h * 0.07f), Size(w * 0.9f, h * 0.42f), CornerRadius(6.dp.toPx()))
                drawRect(NEON_PINK, Offset(w * 0.05f, h * 0.38f), Size(w * 0.9f, h * 0.04f))
                drawRect(Color(0xFFFF7A3D), Offset(w * 0.05f, h * 0.43f), Size(w * 0.9f, h * 0.03f))
                // okienko i szpule
                drawRoundRect(Color(0xFF0A0218), Offset(w * 0.22f, h * 0.55f), Size(w * 0.56f, h * 0.33f), CornerRadius(h * 0.16f))
                val reel = h * 0.12f
                val a = if (playing) rot.value else 0f
                listOf(w * 0.36f, w * 0.64f).forEach { cx ->
                    val c = Offset(cx, h * 0.715f)
                    drawCircle(Color(0xFFE6CCFF), reel, c)
                    drawCircle(Color(0xFF2A1050), reel * 0.45f, c)
                    rotate(a, c) {
                        for (k in 0 until 6) {
                            val ang = Math.toRadians(k * 60.0)
                            drawLine(
                                Color(0xFF2A1050), c,
                                Offset(c.x + cos(ang).toFloat() * reel * 0.85f, c.y + sin(ang).toFloat() * reel * 0.85f),
                                3.dp.toPx(),
                            )
                        }
                    }
                }
            }
            Column(Modifier.fillMaxWidth().fillMaxHeight(0.36f).padding(start = 28.dp, end = 28.dp, top = 14.dp)) {
                Text(m.title ?: "Nic nie gra", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1A0838), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(m.artist ?: m.appName ?: "", fontSize = 14.sp, color = Color(0xFF5A3A80), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(Icons.Rounded.SkipPrevious, "Poprzedni", 54.dp, { Player.previous(m) }, tint = NEON_CYAN)
            Box(
                Modifier.size(62.dp).clip(CircleShape).background(NEON_PINK).clickable { Player.playPause(m) },
                contentAlignment = Alignment.Center,
            ) { Icon(if (m.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (m.playing) "Pauza" else "Odtwórz", tint = Color.White, modifier = Modifier.size(34.dp)) }
            RoundIconButton(Icons.Rounded.SkipNext, "Następny", 54.dp, { Player.next(m) }, tint = NEON_CYAN)
        }
    }
}
