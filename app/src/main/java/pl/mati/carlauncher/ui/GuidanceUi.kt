package pl.mati.carlauncher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

/** Baner prowadzenia CAR OS: strzałka, odległość, czynność, ulica, następny manewr, pasy ruchu. */
@Composable
fun GuidanceBanner(g: Guidance.State, modifier: Modifier) {
    val accent = LocalAccent.current
    // ostatnie pasy – żeby pasek mógł się płynnie schować
    var lastLanes by remember { mutableStateOf<List<Guidance.Lane>?>(null) }
    if (g.lanes != null) lastLanes = g.lanes
    Column(modifier.clip(RoundedCornerShape(20.dp)).background(CarColors.Panel.copy(alpha = 0.96f))) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
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
                    Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).carSurface(RoundedCornerShape(12.dp), CarColors.Raised), contentAlignment = Alignment.Center) {
                        ManeuverGlyph(k, g.thenAngle, CarColors.Text, Modifier.size(34.dp))
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = g.lanes != null && !g.arrived,
            enter = expandVertically(tween(260)) + fadeIn(tween(260)),
            exit = shrinkVertically(tween(220)) + fadeOut(tween(160)),
        ) {
            lastLanes?.let { LaneStrip(it, Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) }
        }
    }
}

/** Wysokość paska pasów ruchu pod banerem (do odsunięcia elementów pod nim). */
val LaneStripHeight = 62.dp

/** Pasy ruchu jak na jezdni: przerywane linie między pasami, podświetlone pasy do jazdy. */
@Composable
fun LaneStrip(lanes: List<Guidance.Lane>, modifier: Modifier) {
    val accent = LocalAccent.current
    val n = lanes.size
    val cellW = when { n <= 5 -> 52.dp; n <= 7 -> 44.dp; else -> 36.dp }
    Box(
        modifier.height(LaneStripHeight - 12.dp).clip(RoundedCornerShape(14.dp)).background(CarColors.Bg.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            lanes.forEachIndexed { i, l ->
                if (i > 0) LaneDivider()
                Box(
                    Modifier.width(cellW).height(44.dp).padding(horizontal = 3.dp).clip(RoundedCornerShape(10.dp))
                        .background(if (l.valid) accent.copy(alpha = 0.26f) else androidx.compose.ui.graphics.Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) {
                    LaneGlyph(l, CarColors.Text, Modifier.size(34.dp))
                }
            }
        }
    }
}

@Composable
private fun LaneDivider() {
    val c = CarColors.Text.copy(alpha = 0.35f)
    androidx.compose.foundation.Canvas(Modifier.width(2.dp).height(38.dp)) {
        drawLine(
            c, androidx.compose.ui.geometry.Offset(size.width / 2, 0f), androidx.compose.ui.geometry.Offset(size.width / 2, size.height),
            size.width, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(size.height * 0.16f, size.height * 0.12f)),
        )
    }
}

/** Strzałki jednego pasa: podświetlona ta, którą jechać, pozostałe blado. */
@Composable
fun LaneGlyph(l: Guidance.Lane, tint: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    // „none” = pas bez strzałek: prosto (albo kierunek manewru, gdy to pas do jazdy)
    val dirs = l.ind.map { if (it == "none") (if (l.valid && l.active != null) l.active else "straight") else it }.distinct()
    val dim = tint.copy(alpha = if (l.valid) 0.3f else 0.26f)
    androidx.compose.foundation.Canvas(modifier) {
        dirs.filter { it != l.active }.forEach { laneArrow(it, dim) }
        if (l.active != null && l.valid) laneArrow(l.active, tint)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.laneArrow(ind: String, color: androidx.compose.ui.graphics.Color) {
    val w = size.width
    val h = size.height
    val s = minOf(w, h)
    val sw = s * 0.13f
    val cx = w / 2
    val stroke = androidx.compose.ui.graphics.drawscope.Stroke(sw, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
    val head = s * 0.2f
    fun arrowHead(ex: Float, ey: Float, ux: Float, uy: Float) {
        val px = -uy
        val py = ux
        drawPath(
            androidx.compose.ui.graphics.Path().apply {
                moveTo(ex + ux * head * 0.95f, ey + uy * head * 0.95f)
                lineTo(ex + px * head * 0.8f - ux * head * 0.25f, ey + py * head * 0.8f - uy * head * 0.25f)
                lineTo(ex - px * head * 0.8f - ux * head * 0.25f, ey - py * head * 0.8f - uy * head * 0.25f)
                close()
            },
            color,
        )
    }
    if (ind == "uturn") {
        // zawracanie w lewo: w górę, łuk, w dół ze strzałką
        val r = s * 0.17f
        val xr = cx + r
        val xl = cx - r
        val top = h * 0.36f
        drawPath(
            androidx.compose.ui.graphics.Path().apply {
                moveTo(xr, h * 0.95f)
                lineTo(xr, top)
                arcTo(androidx.compose.ui.geometry.Rect(xl, top - r, xr, top + r), 0f, -180f, false)
                lineTo(xl, h * 0.56f)
            },
            color, style = stroke,
        )
        arrowHead(xl, h * 0.56f, 0f, 1f)
        return
    }
    val a = Math.toRadians(
        when (ind) {
            "slight right" -> 45.0; "right" -> 90.0; "sharp right" -> 135.0
            "slight left" -> -45.0; "left" -> -90.0; "sharp left" -> -135.0
            else -> 0.0
        },
    )
    val ux = kotlin.math.sin(a).toFloat()
    val uy = -kotlin.math.cos(a).toFloat()
    // pień od dołu, łagodny łuk i koniec w kierunku pasa
    val py = h * 0.6f
    val k = s * 0.13f
    val len = s * 0.3f
    val cyy = py - k
    val ex = cx + ux * len
    val ey = cyy + uy * len
    drawPath(
        androidx.compose.ui.graphics.Path().apply {
            moveTo(cx, h * 0.95f)
            lineTo(cx, py)
            quadraticBezierTo(cx, cyy, ex, ey)
        },
        color, style = stroke,
    )
    arrowHead(ex, ey, ux, uy)
}
