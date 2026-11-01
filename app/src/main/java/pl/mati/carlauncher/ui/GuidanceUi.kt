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
            Icon(maneuverIcon(g.kind), null, tint = CarColors.Bg, modifier = Modifier.size(62.dp))
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
                    Icon(maneuverIcon(k), null, tint = CarColors.Text, modifier = Modifier.size(34.dp))
                }
            }
        }
    }
}
