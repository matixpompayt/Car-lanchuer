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
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
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
        if (ClusterLayout.editing.value) { ClusterLayout.save(); ClusterLayout.editing.value = false; return@BackHandler }
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
            val editing by ClusterLayout.editing.collectAsState()
            if (editing) LayoutEditorBar()
        }
    }
}

/** Układ elementów na mapie pełnoekranowej (licznik): pozycja (ułamek ekranu) i wielkość – ustawiane palcem. */
object ClusterLayout {
    data class Item(val x: Float, val y: Float, val s: Float)
    val NAMES = linkedMapOf("banner" to "Manewr", "eta" to "Przyjazd", "speed" to "Ograniczenie", "buttons" to "Przyciski")
    private val DEFAULT = mapOf(
        "banner" to Item(0.07f, 0.12f, 1f),
        "eta" to Item(0.36f, 0.56f, 1f),
        "speed" to Item(0.07f, 0.42f, 1f),
        "buttons" to Item(0.88f, 0.26f, 1f),
    )
    val items = androidx.compose.runtime.mutableStateMapOf<String, Item>()
    val editing = kotlinx.coroutines.flow.MutableStateFlow(false)
    val selected = kotlinx.coroutines.flow.MutableStateFlow("banner")
    private var loaded = false

    fun load() {
        if (loaded) return
        loaded = true
        items.putAll(DEFAULT)
        try {
            val o = org.json.JSONObject(Prefs.store?.getString("cluster_layout", "{}") ?: "{}")
            o.keys().forEach { k ->
                val a = o.getJSONArray(k)
                items[k] = Item(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getDouble(2).toFloat())
            }
        } catch (_: Exception) {}
    }

    fun get(k: String): Item = items[k] ?: DEFAULT.getValue(k)

    fun move(k: String, dx: Float, dy: Float) {
        val i = get(k)
        items[k] = i.copy(x = (i.x + dx).coerceIn(-0.05f, 0.98f), y = (i.y + dy).coerceIn(-0.05f, 0.98f))
    }

    fun resize(k: String, d: Float) {
        val i = get(k)
        items[k] = i.copy(s = (Math.round((i.s + d).coerceIn(0.5f, 2.5f) * 10) / 10f))
    }

    fun reset() {
        items.clear()
        items.putAll(DEFAULT)
        save()
    }

    fun save() {
        val o = org.json.JSONObject()
        items.forEach { (k, v) -> o.put(k, org.json.JSONArray().put(v.x.toDouble()).put(v.y.toDouble()).put(v.s.toDouble())) }
        Prefs.store?.edit()?.putString("cluster_layout", o.toString())?.apply()
    }
}

/**
 * Mapa na pełnym ekranie pod wirtualny licznik. Każdy element (manewr, przyjazd, ograniczenie, przyciski)
 * można przesunąć palcem i powiększyć/zmniejszyć w trybie „Ustaw elementy”.
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
    val editing by ClusterLayout.editing.collectAsState()
    val selected by ClusterLayout.selected.collectAsState()
    remember { ClusterLayout.load(); 0 }
    Box(Modifier.fillMaxSize()) {
        VectorMap(Modifier.fillMaxSize(), full = true)
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            val wPx = constraints.maxWidth.toFloat()
            val hPx = constraints.maxHeight.toFloat()
            val bannerW = maxWidth * 0.58f / scale
            val d = androidx.compose.ui.platform.LocalDensity.current

            /** Element w swojej pozycji; w trybie ustawiania – ramka i przeciąganie. */
            @Composable
            fun Placed(key: String, content: @Composable () -> Unit) {
                val it = ClusterLayout.get(key)
                val sel = editing && selected == key
                Box(
                    Modifier
                        .offset { androidx.compose.ui.unit.IntOffset((it.x * wPx).roundToInt(), (it.y * hPx).roundToInt()) }
                        .graphicsLayer {
                            scaleX = it.s; scaleY = it.s
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                        }
                        .then(
                            if (editing) Modifier
                                .border(3.dp, if (sel) accent else Color.White.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                                .pointerInput(key) {
                                    detectDragGestures(onDragStart = { ClusterLayout.selected.value = key }) { ch, drag ->
                                        ch.consume()
                                        val cur = ClusterLayout.get(key)
                                        ClusterLayout.move(key, drag.x * cur.s / wPx, drag.y * cur.s / hPx)
                                    }
                                }
                                .clickable { ClusterLayout.selected.value = key }
                            else Modifier,
                        ),
                ) { content() }
            }

            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density * scale, d.fontScale),
            ) {
                val g = guide ?: if (editing) pl.mati.carlauncher.data.Guidance.State(0, "right", "Skręć w prawo", "Jana Pawła II", 300.0, null, null) else null
                if (g != null) Placed("banner") { GuidanceBanner(g, Modifier.width(bannerW)) }

                Placed("speed") {
                    if (editing) Box(
                        Modifier.size(74.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color.White)
                            .border(9.dp, Color(0xFFE0262B), androidx.compose.foundation.shape.CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Text("50", color = Color(0xFF111111), fontWeight = FontWeight.Bold, fontSize = 30.sp) }
                    else SpeedLimitBadge(Modifier)
                }

                Placed("buttons") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        MapButton(Icons.Rounded.FullscreenExit, "Zamknij pełny ekran") {
                            if (!editing) { UiState.mapFull.value = false; UiState.clusterTest.value = false }
                        }
                        MapButton(Icons.Rounded.MyLocation, "Wróć do auta") { if (!editing) UiState.recenter.value++ }
                        if (route != null && guide != null) {
                            MapButton(if (voice) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeOff, if (voice) "Wycisz głos" else "Włącz głos") {
                                if (!editing) Prefs.setNavVoice(!voice)
                            }
                        }
                        MapButton(Icons.Rounded.Edit, "Ustaw elementy") { ClusterLayout.editing.value = true }
                    }
                }

                val r = route
                if (r != null || editing) {
                    Placed("eta") {
                        val p = prog
                        val remM = p?.remainingM ?: r?.distanceM ?: 8400.0
                        val remS = p?.remainingS ?: r?.durationS ?: 720.0
                        Row(
                            Modifier.clip(RoundedCornerShape(18.dp)).background(CarColors.Panel.copy(alpha = 0.94f))
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
                                if (!editing) pl.mati.carlauncher.data.RouteRepository.cancel(ctx)
                            })
                        }
                    }
                }
            }
        }
    }
}

/** Pasek trybu ustawiania elementów (na radiu, nie ściskany). */
@Composable
private fun LayoutEditorBar() {
    val sel by ClusterLayout.selected.collectAsState()
    val it = ClusterLayout.get(sel)
    Box(Modifier.fillMaxSize().padding(top = 18.dp), contentAlignment = Alignment.TopCenter) {
        Row(
            Modifier.clip(RoundedCornerShape(24.dp)).carSurface(RoundedCornerShape(24.dp), CarColors.Panel, heavy = true).padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Przeciągnij elementy palcem", fontSize = 17.sp, color = CarColors.Muted)
            ClusterLayout.NAMES.forEach { (k, n) -> CarChip(n, sel == k, { ClusterLayout.selected.value = k }) }
            RoundIconButton(Icons.Rounded.Remove, "Mniejszy", 48.dp, { ClusterLayout.resize(sel, -0.1f) })
            Text(String.format(PL, "%.1f×", it.s), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            RoundIconButton(Icons.Rounded.Add, "Większy", 48.dp, { ClusterLayout.resize(sel, 0.1f) })
            SecondaryButton("Domyślne", { ClusterLayout.reset() }, height = 48.dp)
            PrimaryButton("Gotowe", { ClusterLayout.save(); ClusterLayout.editing.value = false })
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
            Modifier.clip(RoundedCornerShape(26.dp)).carSurface(RoundedCornerShape(26.dp), CarColors.Panel, heavy = true).padding(horizontal = 24.dp, vertical = 16.dp),
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
