package pl.mati.carlauncher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backspace
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.mati.carlauncher.data.Prefs

/**
 * Panele CAR OS (jak w CAR OS Connect): wjeżdżają od dołu nad całym systemem (także nad dockiem),
 * tło się przyciemnia, zamyka: krzyżyk, dotknięcie obok, przeciągnięcie w dół albo „wstecz”.
 *
 * Panel wywołuje się z ekranu jak zwykły element (`if (open) CarPanel(...)`), a rysuje go
 * [PanelHost] na samej górze systemu.
 */
object Panels {
    internal class Slot { var content by mutableStateOf<(@Composable () -> Unit)?>(null) }

    internal val slots = mutableStateListOf<Slot>()
    internal var hosted by mutableStateOf(false)
}

/** Miejsce na panele – raz, na samej górze ekranu systemu. */
@Composable
fun PanelHost() {
    DisposableEffect(Unit) {
        Panels.hosted = true
        onDispose { Panels.hosted = false }
    }
    Panels.slots.forEach { s -> key(s) { s.content?.invoke() } }
}

/** Sterowanie panelem z jego treści: [dismiss] zamyka z animacją. */
class PanelCtl internal constructor(private val close: () -> Unit) {
    fun dismiss() = close()
}

@Composable
fun CarPanel(
    onDismiss: () -> Unit,
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    width: Dp = 940.dp,
    /** Na całą wysokość (np. mapa). */
    tall: Boolean = false,
    /** Treść przewijana, gdy się nie mieści. */
    scroll: Boolean = false,
    /** Duże przyciski na dole panelu. */
    actions: (@Composable RowScope.(PanelCtl) -> Unit)? = null,
    content: @Composable ColumnScope.(PanelCtl) -> Unit,
) {
    val vis = remember { MutableTransitionState(false).apply { targetState = true } }
    val dismiss by rememberUpdatedState(onDismiss)
    val ctl = remember { PanelCtl { vis.targetState = false } }
    LaunchedEffect(vis.currentState, vis.isIdle) {
        if (vis.isIdle && !vis.currentState && !vis.targetState) dismiss()
    }
    val frame: @Composable () -> Unit = { PanelFrame(vis, ctl, title, subtitle, icon, width, tall, scroll, actions, content) }
    if (!Panels.hosted) {
        frame()
        return
    }
    val slot = remember { Panels.Slot() }
    SideEffect { slot.content = frame }
    DisposableEffect(slot) {
        Panels.slots.add(slot)
        onDispose { Panels.slots.remove(slot) }
    }
}

private fun seg(p: Float, from: Float, to: Float) = ((p - from) / (to - from)).coerceIn(0f, 1f)

@Composable
private fun PanelFrame(
    vis: MutableTransitionState<Boolean>,
    ctl: PanelCtl,
    title: String,
    subtitle: String?,
    icon: ImageVector?,
    width: Dp,
    tall: Boolean,
    scroll: Boolean,
    actions: (@Composable RowScope.(PanelCtl) -> Unit)?,
    content: @Composable ColumnScope.(PanelCtl) -> Unit,
) {
    val anim by Prefs.animations.collectAsState()
    BackHandler { ctl.dismiss() }
    val t = updateTransition(vis, label = "panel")
    val p by t.animateFloat(
        transitionSpec = {
            when {
                !anim -> snap()
                targetState -> tween(380, easing = FastOutSlowInEasing)
                else -> tween(240, easing = FastOutLinearInEasing)
            }
        },
        label = "p",
    ) { if (it) 1f else 0f }
    var drag by remember { mutableFloatStateOf(0f) }
    val dens = LocalDensity.current
    val shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().graphicsLayer { alpha = p }.background(Color.Black.copy(alpha = 0.55f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { ctl.dismiss() },
        )
        val w = if (width > maxWidth - 40.dp) maxWidth - 40.dp else width
        Column(
            Modifier.align(Alignment.BottomCenter).width(w)
                .then(if (tall) Modifier.fillMaxHeight(0.95f) else Modifier.heightIn(max = maxHeight - 28.dp))
                .graphicsLayer { translationY = (1f - p) * (size.height + 40.dp.toPx()) + drag }
                .clip(shape).carSurface(shape, CarColors.Panel, heavy = true)
                .border(1.dp, CarColors.Border, shape)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        ) {
            // uchwyt i nagłówek – przeciągnij w dół, żeby zamknąć
            Column(
                Modifier.fillMaxWidth().draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { d -> drag = (drag + d).coerceAtLeast(0f) },
                    onDragStopped = { v ->
                        if (drag > with(dens) { 120.dp.toPx() } || v > 1800f) ctl.dismiss()
                        else animate(drag, 0f, animationSpec = tween(200)) { x, _ -> drag = x }
                    },
                ),
            ) {
                Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(64.dp, 6.dp).clip(CircleShape).background(CarColors.Text.copy(alpha = 0.22f)))
                }
                Row(
                    Modifier.fillMaxWidth().padding(start = 28.dp, end = 18.dp, top = 8.dp, bottom = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    if (icon != null) IconBadge(icon, 56.dp)
                    Column(Modifier.weight(1f)) {
                        Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = CarColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (subtitle != null) Text(subtitle, fontSize = 17.sp, color = CarColors.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    RoundIconButton(Icons.Rounded.Close, "Zamknij", 58.dp, { ctl.dismiss() })
                }
            }
            // treść wchodzi chwilę po panelu
            Column(
                Modifier.fillMaxWidth().weight(1f, fill = tall)
                    .graphicsLayer {
                        val c = seg(p, 0.35f, 1f)
                        alpha = c
                        translationY = (1f - c) * 18.dp.toPx()
                    }
                    .then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                    .padding(horizontal = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) { content(ctl) }
            if (actions != null) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, top = 18.dp, bottom = 24.dp)
                        .graphicsLayer { alpha = seg(p, 0.45f, 1f) },
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) { actions(ctl) }
            } else Spacer(Modifier.height(24.dp))
        }
    }
}

/** Duży przycisk panelu (wygodny w czasie jazdy). [primary] – w kolorze akcentu, [danger] – czerwony. */
@Composable
fun PanelButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
) {
    val accent = LocalAccent.current
    val src = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(if (CarColors.theme.pill || GlassUi.on) 36.dp else CarColors.theme.radius(20.dp))
    val fg = when {
        primary -> CarColors.Bg
        danger -> CarColors.Red
        else -> CarColors.Text
    }
    Row(
        modifier.pressScale(src).height(72.dp).clip(shape)
            .then(
                when {
                    primary && GlassUi.on -> Modifier.carSurface(shape, CarColors.Raised).background(accent.copy(alpha = if (enabled) 0.82f else 0.3f))
                    primary -> Modifier.background(if (enabled) accent else accent.copy(alpha = 0.3f))
                    danger -> Modifier.carSurface(shape, CarColors.Card).background(CarColors.Red.copy(alpha = 0.12f))
                    else -> Modifier.carSurface(shape, if (CarColors.theme.pill) CarColors.Card2 else CarColors.Card)
                },
            )
            .clickable(interactionSource = src, indication = LocalIndication.current, enabled = enabled, onClick = onClick)
            .padding(horizontal = 26.dp)
            .graphicsLayer { alpha = if (enabled || primary) 1f else 0.45f },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(28.dp))
        Text(text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---------- pola liczbowe z klawiaturą w panelu (bez klawiatury systemowej, która zasłania pół radia) ----------

/** Pole liczbowe: dotknij, żeby wpisywać z klawiatury obok. [hint] – szara podpowiedź, gdy puste. */
@Composable
fun PadField(
    label: String,
    value: String,
    unit: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "",
    sub: String? = null,
) {
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier.height(76.dp).clip(shape)
            .background(if (selected) accent.copy(alpha = 0.12f) else CarColors.Card)
            .border(2.dp, if (selected) accent else CarColors.Border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = if (selected) accent else CarColors.Text2, maxLines = 1)
            if (sub != null) Text(sub, fontSize = 14.sp, color = CarColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            value.ifEmpty { hint },
            fontSize = 34.sp, fontWeight = FontWeight.Bold, fontFamily = Condensed,
            color = if (value.isEmpty()) CarColors.Dim else CarColors.Text, maxLines = 1,
        )
        // kursor przy wybranym polu
        if (selected) Box(Modifier.size(3.dp, 34.dp).clip(RoundedCornerShape(2.dp)).background(accent))
        if (unit.isNotEmpty()) Text(unit, fontSize = 20.sp, color = CarColors.Muted)
    }
}

/** Klawiatura numeryczna panelu: 1–9, przecinek, 0, kasowanie (przytrzymaj = wyczyść). */
@Composable
fun NumPad(onKey: (Char) -> Unit, onBackspace: () -> Unit, onClear: () -> Unit, modifier: Modifier = Modifier, decimal: Boolean = true) {
    val rows = listOf("123", "456", "789")
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { r ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                r.forEach { c -> PadKey(Modifier.weight(1f), { onKey(c) }) { Text("$c", fontSize = 32.sp, fontWeight = FontWeight.SemiBold, color = CarColors.Text) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PadKey(Modifier.weight(1f), { if (decimal) onKey(',') }, enabled = decimal) {
                Text(",", fontSize = 32.sp, fontWeight = FontWeight.SemiBold, color = if (decimal) CarColors.Text else CarColors.Dim)
            }
            PadKey(Modifier.weight(1f), { onKey('0') }) { Text("0", fontSize = 32.sp, fontWeight = FontWeight.SemiBold, color = CarColors.Text) }
            PadKey(Modifier.weight(1f), onBackspace, onLong = onClear) {
                Icon(Icons.Rounded.Backspace, "Usuń", tint = CarColors.Text, modifier = Modifier.size(30.dp))
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PadKey(modifier: Modifier, onClick: () -> Unit, enabled: Boolean = true, onLong: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier.pressScale(src, 0.92f).height(70.dp).clip(shape).carSurface(shape, CarColors.Card)
            .combinedClickable(
                interactionSource = src, indication = LocalIndication.current, enabled = enabled,
                onClick = onClick, onLongClick = onLong,
            ),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * Edycja tekstu liczby z klawiatury panelu: [maxInt] cyfr przed przecinkiem, [maxDec] po
 * (0 = bez przecinka). Zwraca nowy tekst.
 */
fun padType(cur: String, c: Char, maxInt: Int, maxDec: Int): String {
    if (c == ',') {
        if (maxDec == 0 || cur.contains(',')) return cur
        return if (cur.isEmpty()) "0," else "$cur,"
    }
    if (!c.isDigit()) return cur
    val comma = cur.indexOf(',')
    if (comma >= 0) {
        if (cur.length - comma - 1 >= maxDec) return cur
        return cur + c
    }
    if (cur == "0") return "$c"
    if (cur.length >= maxInt) return cur
    return cur + c
}

fun padNum(t: String): Double? = t.replace(',', '.').trimEnd('.').toDoubleOrNull()
