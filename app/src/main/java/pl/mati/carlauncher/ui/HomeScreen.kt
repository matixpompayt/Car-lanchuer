package pl.mati.carlauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.lerp
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import pl.mati.carlauncher.data.LocationRepository
import pl.mati.carlauncher.data.MediaRepository
import pl.mati.carlauncher.data.ObdManager
import pl.mati.carlauncher.data.Prefs
import pl.mati.carlauncher.data.ServiceRepository
import pl.mati.carlauncher.data.TripRepository
import pl.mati.carlauncher.data.WeatherRepository

@Composable
fun HomeScreen() {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        MapCard(Modifier.weight(1.55f).fillMaxHeight())
        Column(
            Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            MediaCard(Modifier.weight(1f).fillMaxWidth())
            VehicleTiles(Modifier.fillMaxWidth())
        }
    }
}

/** Karta z prośbą o dostęp do powiadomień (bez niego brak muzyki i manewrów). */
@Composable
fun NoAccessCard(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    Column(
        modifier.padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text("Sterowanie muzyką", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Włącz dostęp do powiadomień dla CAR OS – wtedy zobaczysz tu Spotify, radio i manewry z Map Google.",
            fontSize = 17.sp, color = CarColors.Text2,
        )
        Box(
            Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(LocalAccent.current)
                .clickable { Launch.notificationAccess(ctx) }
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Text("Włącz dostęp", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = CarColors.Bg)
        }
    }
}

@Composable
private fun MediaCard(modifier: Modifier) {
    val m by MediaRepository.state.collectAsState()
    val np = rememberNowPlaying()
    val access by MediaRepository.access.collectAsState()
    val ctx = LocalContext.current
    var pos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(m, np.key, np.title) {
        while (true) {
            pos = Player.position(np)
            delay(500)
        }
    }

    CarCard(modifier) {
        if (!access) {
            NoAccessCard(Modifier.fillMaxSize())
        } else {
            Column(
                Modifier.fillMaxSize().padding(22.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { UiState.go(Screen.MUSIC) },
                ) {
                    AlbumArt(np.art, 104.dp, 16.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Caption(np.appName ?: "Muzyka", size = 14.sp)
                        Text(np.title ?: "Nic nie gra", fontSize = 25.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(np.artist ?: "", fontSize = 19.sp, color = CarColors.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val fraction = if (np.duration > 0) pos.toFloat() / np.duration else 0f
                    ProgressBar(fraction, LocalAccent.current, height = 5.dp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(if (np.duration > 0) formatDuration(pos) else "", fontSize = 15.sp, color = CarColors.Muted)
                        Text(if (np.duration > 0) formatDuration(np.duration) else "", fontSize = 15.sp, color = CarColors.Muted)
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoundIconButton(Icons.Rounded.SkipPrevious, "Poprzedni", 64.dp, { Player.previous(np) }, iconSize = 36.dp)
                    RoundIconButton(
                        if (np.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (np.playing) "Pauza" else "Odtwórz",
                        78.dp, { if (np.pkg == null && np.key == null) Launch.music(ctx) else Player.playPause(np) }, filled = true, iconSize = 38.dp,
                    )
                    RoundIconButton(Icons.Rounded.SkipNext, "Następny", 64.dp, { Player.next(np) }, iconSize = 36.dp)
                }
            }
        }
    }
}

/** Kafle do wyboru na ekranie głównym (Ustawienia → Wygląd). */
val TILE_DEFS = listOf(
    "speed" to "Prędkość",
    "coolant" to "Płyn chłodzący",
    "boost" to "Doładowanie",
    "voltage" to "Napięcie",
    "load" to "Obciążenie silnika",
    "rpm" to "Obroty",
    "weather" to "Pogoda",
    "service" to "Najbliższy serwis",
    "today" to "Dziś przejechane",
)

@Composable
private fun VehicleTiles(modifier: Modifier) {
    val d by ObdManager.data.collectAsState()
    val gps by LocationRepository.gpsSpeed.collectAsState()
    val keysStr by Prefs.homeTiles.collectAsState()
    val temp by WeatherRepository.temperature.collectAsState()
    val trips by TripRepository.trips.collectAsState()
    val cur by TripRepository.current.collectAsState()
    val odo by ServiceRepository.odometerKm.collectAsState()
    val svcItems by ServiceRepository.items.collectAsState()
    val keys = keysStr.split(",").filter { k -> TILE_DEFS.any { it.first == k } }.take(4)
    if (keys.isEmpty()) return
    val size = if (keys.size >= 4) 34.sp else 44.sp

    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        keys.forEach { k ->
            val m = Modifier.weight(1f)
            when (k) {
                "speed" -> SmallTile("Prędkość", (d.speed ?: gps)?.toString() ?: "--", "km/h", m, size) { UiState.go(Screen.VEHICLE) }
                "coolant" -> SmallTile("Płyn chł.", d.coolant?.toString() ?: "--", "°C", m, size) { UiState.go(Screen.VEHICLE) }
                "boost" -> SmallTile("Doład.", d.boostBar?.pl1() ?: "--", "bar", m, size) { UiState.go(Screen.VEHICLE) }
                "voltage" -> SmallTile("Napięcie", d.voltage?.pl1() ?: "--", "V", m, size) { UiState.go(Screen.VEHICLE) }
                "load" -> SmallTile("Obciąż.", d.load?.toString() ?: "--", "%", m, size) { UiState.go(Screen.VEHICLE) }
                "rpm" -> SmallTile("Obroty", d.rpm?.toString() ?: "--", "", m, size) { UiState.go(Screen.VEHICLE) }
                "weather" -> SmallTile("Pogoda", temp?.toString() ?: "--", "°C", m, size) { UiState.go(Screen.WEATHER) }
                "today" -> {
                    val km = remember(trips.size, cur?.distanceM?.toInt()) { TripRepository.summary(TripRepository.startOfDay()).distanceM / 1000 }
                    SmallTile("Dziś", km.toFloat().pl1(), "km", m, size) { UiState.go(Screen.TRIPS) }
                }
                "service" -> ServiceTile(m, size, odo, svcItems)
            }
        }
    }
}

@Composable
private fun SmallTile(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier,
    size: androidx.compose.ui.unit.TextUnit,
    valueColor: androidx.compose.ui.graphics.Color = CarColors.Text,
    labelColor: androidx.compose.ui.graphics.Color = CarColors.Muted,
    blink: Boolean = false,
    onClick: () -> Unit,
) {
    val bg = if (blink) {
        val t = rememberInfiniteTransition(label = "blink")
        val f by t.animateFloat(0f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "f")
        lerp(CarColors.Card, androidx.compose.ui.graphics.Color(0xFF5A1418), f)
    } else CarColors.Card
    CarCard(modifier.clickable(onClick = onClick), color = bg, radius = 20.dp) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Caption(label, color = labelColor)
            ValueText(value, unit, size, Modifier.fillMaxWidth(), color = valueColor)
        }
    }
}

/** Kafel serwisu: rotacja co 15 s z animacją, miga na czerwono, gdy pozycja jest po terminie. */
@Composable
private fun ServiceTile(
    m: Modifier,
    size: androidx.compose.ui.unit.TextUnit,
    odo: Double,
    svcItems: List<ServiceRepository.Item>,
) {
    val pool = remember(odo.toInt() / 10, svcItems) {
        val known = ServiceRepository.dueList().filter { it.known }
        known.filter { it.overdue || it.soon }.ifEmpty { known }
    }
    var idx by remember { mutableIntStateOf(0) }
    LaunchedEffect(pool.size) {
        idx = 0
        if (pool.size > 1) while (true) {
            delay(15_000)
            idx = (idx + 1) % pool.size
        }
    }
    val cur = pool.getOrNull(idx % maxOf(1, pool.size))
    val overdue = cur?.overdue == true

    val t = rememberInfiniteTransition(label = "blink")
    val f by t.animateFloat(0f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "f")
    val bg = if (overdue) lerp(CarColors.Card, androidx.compose.ui.graphics.Color(0xFF5A1418), f) else CarColors.Card

    CarCard(m.clickable { UiState.go(Screen.SERVICE) }, color = bg, radius = 20.dp) {
        AnimatedContent(
            targetState = cur,
            transitionSpec = {
                (slideInVertically(tween(450)) { it / 2 } + fadeIn(tween(450))) togetherWith
                    (slideOutVertically(tween(350)) { -it / 2 } + fadeOut(tween(300)))
            },
            label = "service",
        ) { d ->
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                if (d == null) {
                    Caption("Serwis")
                    ValueText("--", "", size, Modifier.fillMaxWidth())
                    return@Column
                }
                val name = d.item.name.substringBefore(" ")
                // km zawsze, gdy pozycja je ma; dni tylko gdy nie ma km albo to termin czasowy jest pilny
                val kmUrgent = d.kmLeft != null && d.kmLeft <= 1000
                val daysUrgent = d.daysLeft != null && d.daysLeft <= 30
                val useDays = d.kmLeft == null || (daysUrgent && !kmUrgent)
                val left = if (useDays) d.daysLeft else d.kmLeft
                val v: String
                val u: String
                if (left == null) { v = "--"; u = "" }
                else if (!useDays) {
                    val km = kotlin.math.abs(left)
                    if (km >= 1000) { v = (km / 1000f).pl1(); u = "tys. km" } else { v = km.toString(); u = "km" }
                } else { v = kotlin.math.abs(left).toString(); u = "dni" }
                val counter = if (pool.size > 1) " ${pool.indexOf(d) + 1}/${pool.size}" else ""
                Caption(
                    (if (d.overdue) "$name · po terminie" else name) + counter,
                    color = if (d.overdue) androidx.compose.ui.graphics.Color(0xFFFF8A8E) else CarColors.Muted,
                )
                ValueText(
                    v, u, size, Modifier.fillMaxWidth(),
                    color = if (d.overdue) CarColors.Red else if (d.soon) CarColors.Orange else CarColors.Text,
                )
            }
        }
    }
}
