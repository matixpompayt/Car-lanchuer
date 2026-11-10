package pl.mati.carlauncher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import pl.mati.carlauncher.data.CallState
import pl.mati.carlauncher.data.PhoneBook
import pl.mati.carlauncher.data.Prefs

/**
 * Ekran rozmowy CAR OS. Okno MainUI (zawsze na wierzchu, środek u góry: ok. x 310–970, y 45–273)
 * zostaje nad nami – dlatego nasza treść jest poniżej tego pasa albo z jego boku.
 */
@Composable
fun CallOverlay() {
    val on by Prefs.callScreen.collectAsState()
    val st by CallState.state.collectAsState()
    if (!on) return
    var expanded by remember(st.seq) { mutableStateOf(true) }
    var last by remember { mutableStateOf(st) }
    if (st.inCall) last = st
    // Po odebraniu ekran sam się zwija do „pigułki” – mapa i muzyka wracają.
    LaunchedEffect(st.seq, st.phase) {
        if (st.phase == CallState.Phase.ACTIVE && expanded) {
            delay(8000)
            expanded = false
        }
    }
    val big = st.inCall && expanded
    BackHandler(enabled = big) { expanded = false }

    AnimatedVisibility(big, enter = fadeIn(tween(220)) + scaleIn(initialScale = 0.96f), exit = fadeOut(tween(200)) + scaleOut(targetScale = 0.97f)) {
        CallFull(last) { expanded = false }
    }
    Box(Modifier.fillMaxSize().padding(top = 58.dp, end = 16.dp), contentAlignment = Alignment.TopEnd) {
        AnimatedVisibility(st.inCall && !expanded, enter = fadeIn() + slideInVertically { -it }, exit = fadeOut() + slideOutVertically { -it }) {
            CallPill(last) { expanded = true }
        }
    }
}

private data class Who(val name: String, val sub: String, val photo: String?)

@Composable
private fun rememberWho(number: String): Who {
    val contacts by PhoneBook.contacts.collectAsState()
    return remember(number, contacts) {
        val c = if (number.isNotBlank()) PhoneBook.contactFor(number) else null
        val pretty = prettyNumber(number)
        when {
            c != null -> {
                val label = c.numbers.firstOrNull { PhoneBook.key(it.number) == PhoneBook.key(number) }?.label.orEmpty()
                Who(c.name, listOf(label, pretty).filter { it.isNotBlank() }.joinToString(" · "), c.photo)
            }
            number.isNotBlank() -> Who(pretty, "Nie ma w kontaktach", null)
            else -> Who("Nieznany numer", "Numer zastrzeżony lub jeszcze nieznany", null)
        }
    }
}

fun prettyNumber(n: String): String {
    val d = n.filter { it.isDigit() }
    return when {
        n.startsWith("+48") && d.length == 11 -> "+48 ${d.substring(2, 5)} ${d.substring(5, 8)} ${d.substring(8)}"
        !n.startsWith("+") && d.length == 9 -> "${d.substring(0, 3)} ${d.substring(3, 6)} ${d.substring(6)}"
        else -> n
    }
}

@Composable
private fun callTimer(since: Long): String {
    val now by produceState(android.os.SystemClock.elapsedRealtime(), since) {
        while (true) {
            value = android.os.SystemClock.elapsedRealtime()
            delay(1000)
        }
    }
    if (since <= 0) return ""
    val s = ((now - since) / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun CallFull(st: CallState.State, collapse: () -> Unit) {
    val who = rememberWho(st.number)
    val timer = callTimer(st.activeSince)
    val accent = LocalAccent.current
    val ringing = st.phase == CallState.Phase.INCOMING
    Box(
        Modifier
            .fillMaxSize()
            .carSurface(RectangleShape, CarColors.Bg.copy(alpha = 0.93f), heavy = true)
            .background(Brush.verticalGradient(listOf(Color.Transparent, (if (ringing) CarColors.CallGreen else accent).copy(alpha = 0.10f))))
            // tło łapie dotyk – nic nie „przeklika się” pod spód
            .clickable(remember { MutableInteractionSource() }, indication = null) {},
    ) {
        // lewy górny róg – obok okna MainUI
        Row(Modifier.padding(start = 28.dp, top = 26.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Rounded.Phone, null, tint = CarColors.Muted, modifier = Modifier.size(24.dp))
            Text("Telefon", fontSize = 20.sp, color = CarColors.Muted, fontWeight = FontWeight.Medium)
        }
        Box(Modifier.align(Alignment.TopEnd).padding(top = 18.dp, end = 22.dp)) {
            RoundAction(Icons.Rounded.ExpandLess, "Zwiń", CarColors.Raised, CarColors.Text, size = 64, onClick = collapse)
        }
        Column(
            Modifier.fillMaxSize().padding(top = 292.dp, bottom = 30.dp, start = 60.dp, end = 60.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.widthIn(max = 1100.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(30.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    if (ringing) {
                        val t = rememberInfiniteTransition(label = "ring")
                        val k by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Restart), label = "ringK")
                        Box(
                            Modifier.size(150.dp).graphicsLayer { scaleX = 1f + k * 0.35f; scaleY = 1f + k * 0.35f; alpha = (1f - k) * 0.5f }
                                .clip(CircleShape).background(CarColors.CallGreen),
                        )
                    }
                    Avatar(who.name, who.photo, 150.dp)
                }
                Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(who.name, fontSize = 46.sp, fontWeight = FontWeight.Bold, color = CarColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (who.sub.isNotBlank()) Text(who.sub, fontSize = 22.sp, color = CarColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    val status = when (st.phase) {
                        CallState.Phase.INCOMING -> "Połączenie przychodzące"
                        CallState.Phase.DIALING -> "Łączenie…"
                        CallState.Phase.HELD -> "Rozmowa zawieszona"
                        CallState.Phase.ACTIVE -> timer + if (st.muted) "  ·  mikrofon wyciszony" else ""
                        CallState.Phase.IDLE -> "Rozmowa zakończona"
                    }
                    Text(status, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = if (ringing) CarColors.CallGreen else accent)
                    if (st.waiting) Text("Druga rozmowa czeka – odbierz w oknie MainUI", fontSize = 18.sp, color = CarColors.Orange)
                }
            }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(64.dp), verticalAlignment = Alignment.Top) {
                if (ringing) {
                    LabeledAction(Icons.Rounded.CallEnd, "Odrzuć", CarColors.Red, Color.White) { CallState.hangup() }
                    LabeledAction(Icons.Rounded.Call, "Odbierz", CarColors.CallGreen, Color.White) { CallState.answer() }
                } else {
                    LabeledAction(
                        if (st.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                        if (st.muted) "Włącz mikrofon" else "Wycisz",
                        if (st.muted) CarColors.Text else CarColors.Raised,
                        if (st.muted) CarColors.Bg else CarColors.Text,
                    ) { CallState.toggleMute() }
                    LabeledAction(Icons.Rounded.CallEnd, "Rozłącz", CarColors.Red, Color.White) { CallState.hangup() }
                    LabeledAction(Icons.Rounded.ExpandLess, "Ukryj", CarColors.Raised, CarColors.Text, onClick = collapse)
                }
            }
        }
    }
}

@Composable
private fun LabeledAction(icon: ImageVector, label: String, bg: Color, fg: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        RoundAction(icon, label, bg, fg, size = 100, onClick = onClick)
        Text(label, fontSize = 20.sp, color = CarColors.Text2, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun RoundAction(icon: ImageVector, label: String, bg: Color, fg: Color, size: Int, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    Box(
        Modifier.pressScale(src, 0.9f).size(size.dp).clip(CircleShape).background(bg)
            .clickable(interactionSource = src, indication = LocalIndication.current, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = fg, modifier = Modifier.size((size * 0.44f).dp))
    }
}

/** Zwinięta rozmowa – prawy górny róg, poza oknem MainUI. */
@Composable
private fun CallPill(st: CallState.State, expand: () -> Unit) {
    val who = rememberWho(st.number)
    val timer = callTimer(st.activeSince)
    val ringing = st.phase == CallState.Phase.INCOMING
    val shape = RoundedCornerShape(40.dp)
    Row(
        Modifier.widthIn(max = 288.dp).clip(shape)
            .background(if (ringing) CarColors.CallGreen else CarColors.CallGreen.copy(alpha = 0.92f))
            .clickable(onClickLabel = "Pokaż rozmowę", onClick = expand)
            .padding(start = 8.dp, end = if (ringing) 8.dp else 20.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Avatar(who.name, who.photo, 44.dp)
        Column(Modifier.weight(1f, fill = false)) {
            Text(who.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when (st.phase) {
                    CallState.Phase.INCOMING -> "Dzwoni…"
                    CallState.Phase.DIALING -> "Łączenie…"
                    CallState.Phase.HELD -> "Zawieszona"
                    else -> timer + if (st.muted) " · wyciszony" else ""
                },
                fontSize = 15.sp, color = Color.White.copy(alpha = 0.85f), maxLines = 1,
            )
        }
        if (ringing) {
            RoundAction(Icons.Rounded.CallEnd, "Odrzuć", CarColors.Red, Color.White, size = 48) { CallState.hangup() }
            RoundAction(Icons.Rounded.Call, "Odbierz", Color.White, CarColors.CallGreen, size = 48) { CallState.answer() }
        }
    }
}
