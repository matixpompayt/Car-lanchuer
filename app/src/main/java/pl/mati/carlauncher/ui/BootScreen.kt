package pl.mati.carlauncher.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import pl.mati.carlauncher.data.Prefs

/** Animacja uruchamiania CAR OS. */
@Composable
fun BootScreen() {
    val accent = LocalAccent.current
    val profile by Prefs.profile.collectAsState()
    val message by UiState.bootMessage.collectAsState()
    val arc = remember { Animatable(0f) }
    val needle = remember { Animatable(-120f) }
    val text = remember { Animatable(0f) }
    val progress = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        launch { arc.animateTo(1f, tween(1400, delayMillis = 200, easing = FastOutSlowInEasing)) }
        launch {
            delay(500)
            needle.animateTo(0f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessVeryLow))
        }
        launch { text.animateTo(1f, tween(800, delayMillis = 900)) }
        progress.animateTo(1f, tween(if (message != null) 3800 else 2500, delayMillis = 300))
        delay(250)
        UiState.bootFinished()
    }

    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(900.dp).background(
                Brush.radialGradient(listOf(accent.copy(alpha = 0.13f), Color.Transparent)),
            ),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(28.dp)) {
            CarOsLogo(168.dp, arc = arc.value, needleDeg = needle.value, accent = accent)
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.alpha(text.value)) {
                Text("CAR OS", fontSize = 44.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 10.sp)
                Text(message ?: greeting(profile.name), fontSize = 20.sp, color = CarColors.Muted, letterSpacing = 1.sp)
            }
            Box(Modifier.padding(top = 18.dp).width(260.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(CarColors.Raised)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(progress.value).background(accent))
            }
        }
        Text(
            listOf(profile.carName.takeIf { profile.setupDone }, "OBD2", "Android 10").filterNotNull().joinToString(" · "),
            fontSize = 16.sp, color = CarColors.Dim,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 30.dp),
        )
    }
}
