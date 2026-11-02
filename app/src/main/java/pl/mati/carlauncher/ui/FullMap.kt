package pl.mati.carlauncher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.mati.carlauncher.data.Prefs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Ściska zawartość w poziomie o [f]: licznik, który rozciąga obraz z radia na swój szeroki ekran,
 * pokaże ją wtedy w normalnych proporcjach (na samym radiu będzie węższa).
 */
fun Modifier.squeezeX(f: Float): Modifier = if (f <= 1.01f) this else this.layout { m, c ->
    val w = c.maxWidth
    val h = c.maxHeight
    val wide = (w * f).roundToInt()
    val p = m.measure(Constraints.fixed(wide, h))
    layout(w, h) { p.placeWithLayer((w - wide) / 2, 0) { scaleX = 1f / f } }
}

/** Mapa na pełnym ekranie (przycisk w rogu mapy), z dopasowaniem do wirtualnego licznika. */
@Composable
fun FullMapOverlay() {
    val full by UiState.mapFull.collectAsState()
    val test by UiState.clusterTest.collectAsState()
    val f by Prefs.clusterStretch.collectAsState()
    BackHandler(enabled = full) {
        UiState.mapFull.value = false
        UiState.clusterTest.value = false
    }
    AnimatedVisibility(full, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier.fillMaxSize().background(CarColors.MapBg)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        ) {
            Box(Modifier.fillMaxSize().squeezeX(f)) {
                MapCard(Modifier.fillMaxSize(), full = true)
                if (test) ClusterPattern()
            }
            if (test) ClusterControls(f)
        }
    }
}

/** Wzór: koła i kwadrat – na liczniku mają wyglądać okrągło i kwadratowo. */
@Composable
private fun ClusterPattern() {
    val accent = LocalAccent.current
    Canvas(Modifier.fillMaxSize()) {
        val r = min(size.width, size.height) * 0.32f
        val c = Offset(size.width / 2, size.height / 2)
        drawCircle(accent, r, c, style = Stroke(width = 8f))
        drawCircle(accent.copy(alpha = 0.5f), r * 0.55f, c, style = Stroke(width = 6f))
        drawRect(Color.Red, Offset(c.x - r * 0.38f, c.y - r * 0.38f), androidx.compose.ui.geometry.Size(r * 0.76f, r * 0.76f), style = Stroke(width = 6f))
        for (k in listOf(0.18f, 0.82f)) drawCircle(accent, size.height * 0.1f, Offset(size.width * k, c.y), style = Stroke(width = 6f))
    }
}

@Composable
private fun ClusterControls(f: Float) {
    Box(Modifier.fillMaxSize().padding(bottom = 26.dp), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.clip(RoundedCornerShape(26.dp)).background(CarColors.Panel).padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Patrz na licznik: koła mają być okrągłe, kwadrat kwadratowy", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                RoundIconButton(Icons.Rounded.Remove, "Mniej", 56.dp, { Prefs.setClusterStretch(f - 0.05f) })
                Text(if (f <= 1.01f) "bez dopasowania" else String.format(PL, "%.2f×", f), fontSize = 26.sp, fontWeight = FontWeight.Bold, fontFamily = Condensed)
                RoundIconButton(Icons.Rounded.Add, "Więcej", 56.dp, { Prefs.setClusterStretch(f + 0.05f) })
                PrimaryButton("Gotowe", { UiState.clusterTest.value = false })
            }
        }
    }
}
