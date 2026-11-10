package pl.mati.carlauncher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.mati.carlauncher.data.AlertRepository
import pl.mati.carlauncher.data.RoadWatch
import java.util.Locale

private val Amber = Color(0xFFFFB020)
private val Danger = Color(0xFFE0262B)

private fun meters(m: Int): String =
    if (m < 1000) "${(m / 10) * 10} m" else String.format(Locale("pl"), "%.1f km", m / 1000.0)

/** Fotoradar / odcinkowy pomiar z OpenStreetMap – u góry na każdym ekranie, z odliczaniem metrów. */
@Composable
fun RoadWatchBanner() {
    val st by RoadWatch.state.collectAsState()
    val camSam by AlertRepository.current.collectAsState()
    // ostrzeżenie z CamSam ma pierwszeństwo – nie dublujemy banerów
    val show = camSam == null && (st.camera != null || st.section != null)
    var last by remember { mutableStateOf(st) }
    if (show) last = st
    Box(
        Modifier.fillMaxWidth().padding(start = CarColors.theme.insetStart, end = CarColors.theme.insetEnd, top = 58.dp + CarColors.theme.insetTop),
        contentAlignment = Alignment.TopCenter,
    ) {
        AnimatedVisibility(show, enter = fadeIn() + slideInVertically { -it }, exit = fadeOut() + slideOutVertically { -it }) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                last.section?.let { SectionCard(it) }
                last.camera?.let { CameraCard(it) }
            }
        }
    }
}

@Composable
private fun LimitSign(v: Int, size: Int = 58) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(Color.White).border((size * 0.12f).dp, Danger, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(v.toString(), color = Color(0xFF111111), fontWeight = FontWeight.Bold, fontSize = (if (v >= 100) size * 0.33f else size * 0.4f).sp)
    }
}

@Composable
private fun CameraCard(c: RoadWatch.Camera) {
    val shape = RoundedCornerShape(26.dp)
    val pulse = if (c.over) {
        val t = rememberInfiniteTransition(label = "cam")
        val f by t.animateFloat(1f, 0.55f, infiniteRepeatable(tween(420), RepeatMode.Reverse), label = "camA")
        f
    } else 1f
    val bg = if (c.over) Danger else Amber
    val fg = if (c.over) Color.White else Color(0xFF1A1205)
    Row(
        Modifier.clip(shape).background(bg.copy(alpha = 0.55f + 0.45f * pulse)).padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(54.dp).clip(CircleShape).background(fg.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.PhotoCamera, null, tint = fg, modifier = Modifier.size(32.dp))
        }
        Column {
            Text(if (c.over) "Fotoradar – zwolnij!" else "Fotoradar", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = fg)
            Text(meters(c.dist), fontSize = 34.sp, fontWeight = FontWeight.Bold, color = fg)
        }
        c.limit?.let { LimitSign(it) }
    }
}

@Composable
private fun SectionCard(s: RoadWatch.Section) {
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(26.dp)
    val lim = s.limit
    val bad = lim != null && s.avg != null && s.avg > lim
    Row(
        Modifier.widthIn(min = 460.dp, max = 760.dp).clip(shape)
            .carSurface(shape, CarColors.Panel, heavy = true)
            .border(2.dp, if (bad) Danger else accent.copy(alpha = 0.7f), shape)
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(54.dp).clip(CircleShape).background((if (bad) Danger else accent).copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Timer, null, tint = if (bad) Danger else accent, modifier = Modifier.size(32.dp))
        }
        Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            when (s.phase) {
                RoadWatch.SPhase.AHEAD -> {
                    Text("Odcinkowy pomiar prędkości", fontSize = 19.sp, color = CarColors.Text2, fontWeight = FontWeight.Medium)
                    Text("za ${meters(s.dist)}", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = CarColors.Text)
                    Text("Długość odcinka ${meters(s.length)}" + (if (s.name.isNotBlank()) " · ${s.name}" else ""), fontSize = 15.sp, color = CarColors.Muted, maxLines = 1)
                }
                RoadWatch.SPhase.IN -> {
                    Text("Odcinkowy pomiar · do końca ${meters(s.dist)}", fontSize = 19.sp, color = CarColors.Text2, fontWeight = FontWeight.Medium, maxLines = 1)
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(s.avg?.let { "Średnia $it km/h" } ?: "Mierzę średnią…", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = if (bad) Danger else CarColors.Text)
                    }
                    val hint = when {
                        s.avg == null || lim == null -> null
                        s.allowed != null -> "Do końca jedź najwyżej ${s.allowed} km/h"
                        else -> "Średnia w normie"
                    }
                    if (hint != null) Text(hint, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = if (bad || s.allowed != null) CarColors.Orange else CarColors.Green)
                    Box(Modifier.padding(top = 6.dp).width(380.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(CarColors.Track)) {
                        Box(Modifier.fillMaxWidth(s.progress).height(6.dp).clip(RoundedCornerShape(3.dp)).background(if (bad) Danger else accent))
                    }
                }
                RoadWatch.SPhase.DONE -> {
                    Text("Koniec odcinkowego pomiaru", fontSize = 19.sp, color = CarColors.Text2, fontWeight = FontWeight.Medium)
                    Text(s.avg?.let { "Średnia $it km/h" } ?: "", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = if (bad) Danger else CarColors.Green)
                }
            }
        }
        lim?.let { LimitSign(it) }
    }
}

