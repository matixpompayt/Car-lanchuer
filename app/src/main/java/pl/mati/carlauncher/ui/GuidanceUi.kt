package pl.mati.carlauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.ForkLeft
import androidx.compose.material.icons.rounded.ForkRight
import androidx.compose.material.icons.rounded.Merge
import androidx.compose.material.icons.rounded.RampLeft
import androidx.compose.material.icons.rounded.RampRight
import androidx.compose.material.icons.rounded.RoundaboutRight
import androidx.compose.material.icons.rounded.Straight
import androidx.compose.material.icons.rounded.TurnLeft
import androidx.compose.material.icons.rounded.TurnRight
import androidx.compose.material.icons.rounded.TurnSharpLeft
import androidx.compose.material.icons.rounded.TurnSharpRight
import androidx.compose.material.icons.rounded.TurnSlightLeft
import androidx.compose.material.icons.rounded.TurnSlightRight
import androidx.compose.material.icons.rounded.UTurnLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.mati.carlauncher.data.Guidance

fun maneuverIcon(kind: String): ImageVector = when (kind) {
    "left" -> Icons.Rounded.TurnLeft
    "right" -> Icons.Rounded.TurnRight
    "slight left" -> Icons.Rounded.TurnSlightLeft
    "slight right" -> Icons.Rounded.TurnSlightRight
    "sharp left" -> Icons.Rounded.TurnSharpLeft
    "sharp right" -> Icons.Rounded.TurnSharpRight
    "uturn" -> Icons.Rounded.UTurnLeft
    "ramp left" -> Icons.Rounded.RampLeft
    "ramp right" -> Icons.Rounded.RampRight
    "fork left" -> Icons.Rounded.ForkLeft
    "fork right" -> Icons.Rounded.ForkRight
    "merge" -> Icons.Rounded.Merge
    "roundabout" -> Icons.Rounded.RoundaboutRight
    "arrive" -> Icons.Rounded.Flag
    else -> Icons.Rounded.Straight
}

/** Strzałka manewru; rondo rysowane z prawdziwym kierunkiem zjazdu. */
@Composable
fun ManeuverGlyph(kind: String, angle: Double?, tint: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    if (kind == "roundabout") RoundaboutGlyph(angle ?: 0.0, tint, modifier)
    else Icon(maneuverIcon(kind), null, tint = tint, modifier = modifier)
}

/**
 * Rondo (ruch przeciwnie do zegara, jak w PL/DE): wjazd od dołu, łuk do zjazdu
 * i strzałka w kierunku drogi wyjazdowej ([angle]: 0 = prosto, +90 = prawo, -90 = lewo).
 */
@Composable
fun RoundaboutGlyph(angle: Double, tint: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val w = size.minDimension
        val c = center
        val r = w * 0.2f
        val sw = w * 0.1f
        val faint = tint.copy(alpha = 0.35f)
        // cały okrąg ronda (blado)
        drawCircle(faint, r, c, style = androidx.compose.ui.graphics.drawscope.Stroke(sw * 0.7f))
        // zjazd: kąt na ekranie (0° = w prawo, rośnie zgodnie z zegarem); „prosto” = góra
        val aExit = angle.toFloat() - 90f
        var d = (90f - aExit) % 360f
        if (d < 0) d += 360f
        if (d < 12f) d = 348f
        // łuk od wjazdu (dół, 90°) przeciwnie do zegara do zjazdu
        drawArc(
            tint, 90f, -d, false,
            androidx.compose.ui.geometry.Offset(c.x - r, c.y - r), androidx.compose.ui.geometry.Size(r * 2, r * 2),
            style = androidx.compose.ui.graphics.drawscope.Stroke(sw, cap = androidx.compose.ui.graphics.StrokeCap.Round),
        )
        // wjazd od dołu
        drawLine(tint, androidx.compose.ui.geometry.Offset(c.x, w * 0.98f), androidx.compose.ui.geometry.Offset(c.x, c.y + r), sw, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        // wyjazd ze strzałką
        val rad = Math.toRadians(aExit.toDouble())
        val ux = kotlin.math.cos(rad).toFloat()
        val uy = kotlin.math.sin(rad).toFloat()
        val p1 = androidx.compose.ui.geometry.Offset(c.x + ux * r, c.y + uy * r)
        val p2 = androidx.compose.ui.geometry.Offset(c.x + ux * w * 0.43f, c.y + uy * w * 0.43f)
        drawLine(tint, p1, p2, sw, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        val head = w * 0.13f
        val px = -uy
        val py = ux
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(p2.x + ux * head * 0.9f, p2.y + uy * head * 0.9f)
            lineTo(p2.x + px * head * 0.8f - ux * head * 0.2f, p2.y + py * head * 0.8f - uy * head * 0.2f)
            lineTo(p2.x - px * head * 0.8f - ux * head * 0.2f, p2.y - py * head * 0.8f - uy * head * 0.2f)
            close()
        }
        drawPath(path, tint)
    }
}

/** Baner prowadzenia CAR OS: strzałka, odległość, czynność, ulica, następny manewr. */
@Composable
fun GuidanceBanner(g: Guidance.State, modifier: Modifier) {
    val accent = LocalAccent.current
    Row(
        modifier.clip(RoundedCornerShape(20.dp)).background(CarColors.Panel.copy(alpha = 0.96f)).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(84.dp).clip(RoundedCornerShape(16.dp)).background(accent), contentAlignment = Alignment.Center) {
            ManeuverGlyph(g.kind, g.angle, CarColors.Bg, Modifier.size(62.dp))
            if (g.kind == "roundabout" && g.exit != null) {
                Text(
                    "${g.exit}", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = accent,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).clip(RoundedCornerShape(8.dp)).background(CarColors.Bg).padding(horizontal = 6.dp),
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                if (g.arrived) g.action else Guidance.distanceShort(g.distanceM),
                fontSize = 38.sp, fontWeight = FontWeight.Bold, fontFamily = Condensed, maxLines = 1,
            )
            Text(
                if (g.arrived) g.street else listOf(g.action, g.street).filter { it.isNotBlank() }.joinToString(" · "),
                fontSize = 20.sp, color = CarColors.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        g.thenKind?.let { k ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("potem", fontSize = 14.sp, color = CarColors.Muted)
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(CarColors.Raised), contentAlignment = Alignment.Center) {
                    ManeuverGlyph(k, g.thenAngle, CarColors.Text, Modifier.size(34.dp))
                }
            }
        }
    }
}
