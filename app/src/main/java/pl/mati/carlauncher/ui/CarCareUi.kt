package pl.mati.carlauncher.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import pl.mati.carlauncher.data.CarCare

/** Pasek u góry: „Turbo – odczekaj 45 s przed zgaszeniem” / „Turbo schłodzone”. */
@Composable
fun TurboBanner() {
    val t by CarCare.turbo.collectAsState()
    val accent = LocalAccent.current
    var last by remember { mutableStateOf<CarCare.Turbo?>(null) }
    var start by remember { mutableIntStateOf(0) }
    t?.let { if (last == null || last!!.done || it.secondsLeft > start) start = maxOf(it.secondsLeft, 1); last = it }
    Box(Modifier.fillMaxSize().padding(start = CarColors.theme.insetStart, end = CarColors.theme.insetEnd, top = 84.dp + CarColors.theme.insetTop), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(t != null, enter = fadeIn() + slideInVertically { -it }, exit = fadeOut() + slideOutVertically { -it }) {
            val s = last ?: return@AnimatedVisibility
            val green = Color(0xFF34C759)
            Row(
                Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .carSurface(RoundedCornerShape(24.dp), CarColors.Panel, heavy = true)
                    .border(1.dp, (if (s.done) green else accent).copy(alpha = 0.55f), RoundedCornerShape(24.dp))
                    .padding(horizontal = 22.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                    if (s.done) {
                        Icon(Icons.Rounded.CheckCircle, null, tint = green, modifier = Modifier.size(44.dp))
                    } else {
                        CircularProgressIndicator(
                            progress = { (s.secondsLeft.toFloat() / start).coerceIn(0f, 1f) },
                            modifier = Modifier.size(56.dp), color = accent, strokeWidth = 5.dp, trackColor = CarColors.Border,
                        )
                        Icon(Icons.Rounded.Air, null, tint = accent, modifier = Modifier.size(26.dp))
                    }
                }
                Column {
                    Text(
                        if (s.done) "Turbo schłodzone" else "Turbo – odczekaj ${s.secondsLeft} s",
                        fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    )
                    Text(
                        if (s.done) "Możesz zgasić silnik" else "Po mocnej jeździe zostaw silnik na wolnych obrotach przed zgaszeniem",
                        fontSize = 16.sp, color = CarColors.Muted,
                    )
                }
            }
        }
    }
}
