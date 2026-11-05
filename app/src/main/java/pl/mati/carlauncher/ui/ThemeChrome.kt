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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.mati.carlauncher.data.Prefs
import pl.mati.carlauncher.data.VoiceCommands

/** Wspólna lista ekranów do menu motywów. */
private data class NavItem(val icon: ImageVector, val label: String, val short: String, val screen: Screen)

private val APPS_GROUP = setOf(Screen.APPS, Screen.SETTINGS, Screen.TRIPS, Screen.SERVICE, Screen.WEATHER, Screen.FUEL)

private val NAV_ITEMS = listOf(
    NavItem(Icons.Rounded.GridView, "Ekran główny", "START", Screen.HOME),
    NavItem(Icons.Rounded.Navigation, "Nawigacja", "NAWIGACJA", Screen.NAV),
    NavItem(Icons.Rounded.MusicNote, "Muzyka", "MEDIA", Screen.MUSIC),
    NavItem(Icons.Rounded.Phone, "Telefon", "TELEFON", Screen.PHONE),
    NavItem(Icons.Rounded.Speed, "Pojazd", "AUTO", Screen.VEHICLE),
    NavItem(Icons.Rounded.Apps, "Aplikacje", "MENU", Screen.APPS),
)

private fun NavItem.active(current: Screen) = if (screen == Screen.APPS) current in APPS_GROUP else current == screen

// ---------------- VW Nocny: zakładki z napisami jak w radiu RNS ----------------

@Composable
fun VwTabs(current: Screen) {
    val accent = LocalAccent.current
    val voice by VoiceCommands.state.collectAsState()
    val startVoice = rememberVoiceStarter()
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(56.dp).background(CarColors.Dock), verticalAlignment = Alignment.CenterVertically) {
            NAV_ITEMS.forEachIndexed { i, it ->
                val on = it.active(current)
                Box(Modifier.weight(1f).fillMaxHeight().clickable(onClickLabel = it.label) { UiState.go(it.screen) }, contentAlignment = Alignment.Center) {
                    Text(
                        it.short, fontSize = 17.sp, letterSpacing = 2.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                        color = if (on) accent else CarColors.Text2,
                    )
                    if (on) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth(0.62f).height(3.dp).background(accent))
                }
                if (i < NAV_ITEMS.lastIndex) Box(Modifier.width(1.dp).height(26.dp).background(CarColors.Border))
            }
            Box(Modifier.width(1.dp).height(26.dp).background(CarColors.Border))
            Box(Modifier.width(84.dp).fillMaxHeight().clickable(onClickLabel = "Komenda głosowa") { startVoice() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Mic, "Komenda głosowa", tint = if (voice.phase == VoiceCommands.Phase.LISTENING) accent else CarColors.Text2, modifier = Modifier.size(28.dp))
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(CarColors.Border))
    }
}

// ---------------- Sport: shift-light, menu po prawej, karbon ----------------

/** Pasek diod obrotów: zielone → żółte → czerwone, miga przy odcięciu. */
@Composable
fun ShiftLights(modifier: Modifier) {
    val d by pl.mati.carlauncher.data.Vehicle.data.collectAsState()
    val accent = LocalAccent.current
    val rpm = d.rpm ?: 0
    val max = 4500f
    val n = 26
    val lit = ((rpm / max) * n).toInt().coerceIn(0, n)
    val flash = rpm >= 4300
    val t = rememberInfiniteTransition(label = "shift")
    val blink = t.animateFloat(1f, 0.15f, infiniteRepeatable(tween(110), RepeatMode.Reverse), label = "b")
    Canvas(modifier.background(Color.Black).padding(horizontal = 10.dp, vertical = 3.dp)) {
        val gap = 4.dp.toPx()
        val w = (size.width - gap * (n - 1)) / n
        for (i in 0 until n) {
            val base = when {
                i < n * 0.55f -> Color(0xFF39E75F)
                i < n * 0.82f -> accent
                else -> Color(0xFFFF1E1E)
            }
            val on = i < lit
            val c = when {
                flash -> Color(0xFFFF1E1E).copy(alpha = blink.value)
                on -> base
                else -> base.copy(alpha = 0.12f)
            }
            drawRoundRect(c, Offset(i * (w + gap), 0f), Size(w, size.height), CornerRadius(2.dp.toPx()))
        }
    }
}

private val Slant = GenericShape { size, _ ->
    moveTo(size.width * 0.2f, 0f)
    lineTo(size.width, 0f)
    lineTo(size.width * 0.8f, size.height)
    lineTo(0f, size.height)
    close()
}

@Composable
fun SportRail(current: Screen) {
    val accent = LocalAccent.current
    val voice by VoiceCommands.state.collectAsState()
    val startVoice = rememberVoiceStarter()
    Row(Modifier.fillMaxHeight()) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(accent))
        Column(
            Modifier.width(97.dp).fillMaxHeight().background(CarColors.Dock).padding(vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            NAV_ITEMS.forEach { it ->
                val on = it.active(current)
                Box(
                    Modifier.size(width = 80.dp, height = 62.dp).clip(Slant).background(if (on) accent else Color.Transparent)
                        .clickable(onClickLabel = it.label) { UiState.go(it.screen) },
                    contentAlignment = Alignment.Center,
                ) { Icon(it.icon, it.label, tint = if (on) Color.Black else CarColors.Text, modifier = Modifier.size(32.dp)) }
            }
            Spacer(Modifier.weight(1f))
            val listening = voice.phase == VoiceCommands.Phase.LISTENING
            Box(
                Modifier.size(width = 80.dp, height = 62.dp).clip(Slant).background(if (listening) accent else CarColors.Card)
                    .clickable(onClickLabel = "Komenda głosowa") { startVoice() },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Mic, "Komenda głosowa", tint = if (listening) Color.Black else CarColors.Text, modifier = Modifier.size(30.dp)) }
        }
    }
}

/** Delikatny wzór karbonu na tle. */
@Composable
fun CarbonPattern() {
    Canvas(Modifier.fillMaxSize()) {
        val step = 7.dp.toPx()
        val c1 = Color.White.copy(alpha = 0.025f)
        var x = -size.height
        while (x < size.width) {
            drawLine(c1, Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = 2.dp.toPx())
            x += step
        }
    }
}

// ---------------- Pełna mapa: menu w pływającej kapsule ----------------

@Composable
fun FloatingNav(current: Screen, modifier: Modifier) {
    val accent = LocalAccent.current
    val voice by VoiceCommands.state.collectAsState()
    val startVoice = rememberVoiceStarter()
    Row(
        modifier.height(76.dp).clip(RoundedCornerShape(38.dp)).background(CarColors.Overlay)
            .border(1.dp, CarColors.Text.copy(alpha = 0.08f), RoundedCornerShape(38.dp)).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        NAV_ITEMS.forEach { it ->
            val on = it.active(current)
            Box(
                Modifier.size(58.dp).clip(CircleShape).background(if (on) accent else Color.Transparent)
                    .clickable(onClickLabel = it.label) { UiState.go(it.screen) },
                contentAlignment = Alignment.Center,
            ) { Icon(it.icon, it.label, tint = if (on) CarColors.Bg else CarColors.Text, modifier = Modifier.size(30.dp)) }
        }
        Box(Modifier.width(1.dp).height(34.dp).background(CarColors.Text.copy(alpha = 0.15f)))
        val listening = voice.phase == VoiceCommands.Phase.LISTENING
        Box(
            Modifier.size(58.dp).clip(CircleShape).background(if (listening) accent else Color.Transparent)
                .clickable(onClickLabel = "Komenda głosowa") { startVoice() },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Mic, "Komenda głosowa", tint = if (listening) CarColors.Bg else CarColors.Text, modifier = Modifier.size(30.dp)) }
    }
}

// ---------------- Retro 80s: zachód słońca i neonowa siatka ----------------

@Composable
fun RetroBackground() {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val horizon = h * 0.6f
        // niebo
        drawRect(Brush.verticalGradient(listOf(Color(0xFF0A0218), Color(0xFF2A0846), Color(0xFF5A0F5C)), 0f, horizon), size = Size(w, horizon))
        // słońce w paski
        val r = h * 0.3f
        val sc = Offset(w * 0.5f, horizon)
        clipRect(bottom = horizon) {
            drawCircle(Brush.verticalGradient(listOf(Color(0xFFFFE259), Color(0xFFFF7A3D), Color(0xFFFF2E97)), sc.y - r, sc.y), r, sc)
            for (i in 0 until 6) {
                val y = sc.y - r * 0.5f + i * r * 0.1f
                val hh = 2f + i * 2.6f
                drawRect(Color(0xFF3A0A50), Offset(sc.x - r, y), Size(r * 2, hh))
            }
        }
        // ziemia
        drawRect(Brush.verticalGradient(listOf(Color(0xFF1C0536), Color(0xFF0A0218)), horizon, h), Offset(0f, horizon), Size(w, h - horizon))
        val grid = Color(0xFFFF2E97).copy(alpha = 0.55f)
        val sw = 1.5.dp.toPx()
        // linie zbiegające się w punkcie na horyzoncie
        for (k in -16..16) {
            drawLine(grid, Offset(w / 2 + k * w * 0.018f, horizon), Offset(w / 2 + k * w * 0.13f, h), sw)
        }
        // linie poziome coraz gęstsze przy horyzoncie
        for (j in 1..12) {
            val t = j / 12f
            val y = horizon + (h - horizon) * t * t
            drawLine(grid, Offset(0f, y), Offset(w, y), sw)
        }
        drawLine(Color(0xFFFF2E97), Offset(0f, horizon), Offset(w, horizon), 2.dp.toPx())
    }
}
