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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
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
                ClusterMap()
                if (test) ClusterPattern()
            }
            if (test) ClusterControls(f)
        }
    }
}

/**
 * Mapa na pełnym ekranie ułożona pod wirtualny licznik: licznik zasłania środek góry (zegar, temperatura)
 * i dół (wskaźniki), boki są zaokrąglone – wszystko ważne jest w polu, które widać.
 * Elementy powiększone (Wyświetlacz → Wirtualny licznik → Wielkość elementów).
 */
@Composable
private fun ClusterMap() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val accent = LocalAccent.current
    val guide by pl.mati.carlauncher.data.Guidance.state.collectAsState()
    val route by pl.mati.carlauncher.data.RouteRepository.route.collectAsState()
    val prog by pl.mati.carlauncher.data.RouteRepository.progress.collectAsState()
    val voice by Prefs.navVoice.collectAsState()
    val scale by Prefs.clusterUiScale.collectAsState()
    Box(Modifier.fillMaxSize()) {
        VectorMap(Modifier.fillMaxSize(), full = true)
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            // ułamki ekranu liczone w jednostkach PO powiększeniu (inaczej odstępy też by rosły)
            val w = maxWidth / scale
            val h = maxHeight / scale
            val d = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density * scale, d.fontScale),
            ) {
                // manewr: na górze, pod wycięciem licznika
                guide?.let { g ->
                    GuidanceBanner(
                        g,
                        Modifier.align(Alignment.TopStart).padding(start = w * 0.07f, top = h * 0.12f)
                            .width(w * 0.58f),
                    )
                }
                // ograniczenie prędkości: lewa strona, środek wysokości
                SpeedLimitBadge(Modifier.align(Alignment.CenterStart).padding(start = w * 0.07f, top = h * 0.1f))
                // przyciski: prawa strona
                Column(
                    Modifier.align(Alignment.CenterEnd).padding(end = w * 0.06f, bottom = h * 0.08f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MapButton(Icons.Rounded.FullscreenExit, "Zamknij pełny ekran") {
                        UiState.mapFull.value = false
                        UiState.clusterTest.value = false
                    }
                    MapButton(Icons.Rounded.MyLocation, "Wróć do auta") { UiState.recenter.value++ }
                    if (route != null && guide != null) {
                        MapButton(if (voice) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeOff, if (voice) "Wycisz głos" else "Włącz głos") {
                            Prefs.setNavVoice(!voice)
                        }
                    }
                }
                // przyjazd: dół środka, nad wskaźnikami licznika
                val r = route
                if (r != null) {
                    val p = prog
                    val remM = p?.remainingM ?: r.distanceM
                    val remS = p?.remainingS ?: r.durationS
                    Row(
                        Modifier.align(Alignment.BottomCenter).padding(bottom = h * 0.33f)
                            .clip(RoundedCornerShape(18.dp)).background(CarColors.Panel.copy(alpha = 0.94f))
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        if (p?.arrived == true) {
                            Text("Jesteś na miejscu", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        } else {
                            Text(formatEta(remS), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = accent)
                            Text("${(remS / 60).roundToInt()} min · ${formatDistance(remM)}", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        }
                        RoundIconButton(Icons.Rounded.Close, "Zakończ trasę", 38.dp, {
                            pl.mati.carlauncher.data.RouteRepository.cancel(ctx)
                        })
                    }
                }
            }
        }
    }
}

/** Okrągły przycisk z tłem – widoczny na jasnej i ciemnej mapie. */
@Composable
private fun MapButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(54.dp).clip(androidx.compose.foundation.shape.CircleShape).background(CarColors.Panel.copy(alpha = 0.92f))
            .clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { androidx.compose.material3.Icon(icon, label, tint = CarColors.Text, modifier = Modifier.size(30.dp)) }
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
            val ui by Prefs.clusterUiScale.collectAsState()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Proporcje", fontSize = 17.sp, color = CarColors.Muted)
                RoundIconButton(Icons.Rounded.Remove, "Mniej", 52.dp, { Prefs.setClusterStretch(f - 0.05f) })
                Text(if (f <= 1.01f) "wył." else String.format(PL, "%.2f×", f), fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = Condensed)
                RoundIconButton(Icons.Rounded.Add, "Więcej", 52.dp, { Prefs.setClusterStretch(f + 0.05f) })
                Text("Wielkość", fontSize = 17.sp, color = CarColors.Muted)
                RoundIconButton(Icons.Rounded.Remove, "Mniejsze", 52.dp, { Prefs.setClusterUiScale(ui - 0.1f) })
                Text(String.format(PL, "%.1f×", ui), fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = Condensed)
                RoundIconButton(Icons.Rounded.Add, "Większe", 52.dp, { Prefs.setClusterUiScale(ui + 0.1f) })
                PrimaryButton("Gotowe", { UiState.clusterTest.value = false })
            }
        }
    }
}
