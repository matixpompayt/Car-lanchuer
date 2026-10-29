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
    val access by MediaRepository.access.collectAsState()
    val ctx = LocalContext.current
    var pos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(m) {
        while (true) {
            pos = MediaRepository.position()
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
                    AlbumArt(m.art, 104.dp, 16.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Caption(m.appName ?: "Muzyka", size = 14.sp)
                        Text(m.title ?: "Nic nie gra", fontSize = 25.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(m.artist ?: "", fontSize = 19.sp, color = CarColors.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val fraction = if (m.duration > 0) pos.toFloat() / m.duration else 0f
                    ProgressBar(fraction, LocalAccent.current, height = 5.dp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(if (m.duration > 0) formatDuration(pos) else "", fontSize = 15.sp, color = CarColors.Muted)
                        Text(if (m.duration > 0) formatDuration(m.duration) else "", fontSize = 15.sp, color = CarColors.Muted)
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoundIconButton(Icons.Rounded.SkipPrevious, "Poprzedni", 64.dp, MediaRepository::previous, iconSize = 36.dp)
                    RoundIconButton(
                        if (m.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (m.playing) "Pauza" else "Odtwórz",
                        78.dp, { if (m.pkg == null) Launch.music(ctx) else MediaRepository.playPause() }, filled = true, iconSize = 38.dp,
                    )
                    RoundIconButton(Icons.Rounded.SkipNext, "Następny", 64.dp, MediaRepository::next, iconSize = 36.dp)
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
                "service" -> {
                    // Kilka pozycji po terminie / wkrótce → zmieniają się co 15 s w kółko
                    val list = remember(odo.toInt() / 10, svcItems) {
                        val known = ServiceRepository.dueList().filter { it.known }
                        known.filter { it.overdue || it.soon }.ifEmpty { known.take(1) }
                    }
                    var idx by remember { mutableIntStateOf(0) }
                    LaunchedEffect(list.size) {
                        idx = 0
                        while (list.size > 1) {
                            delay(15_000)
                            idx = (idx + 1) % list.size
                        }
                    }
                    val next = list.getOrNull(idx % list.size.coerceAtLeast(1))
                    val name = next?.item?.name?.substringBefore(" ") ?: "Serwis"
                    val useKm = next?.kmLeft != null && (next.daysLeft == null || next.kmLeft < next.daysLeft * 40)
                    val left = if (useKm) next?.kmLeft else next?.daysLeft
                    val overdue = next?.overdue == true
                    val v: String
                    val u: String
                    when {
                        next == null || left == null -> { v = "--"; u = "" }
                        useKm -> {
                            val km = kotlin.math.abs(left)
                            if (km >= 1000) { v = (km / 1000f).pl1(); u = "tys. km" } else { v = km.toString(); u = "km" }
                        }
                        else -> { v = kotlin.math.abs(left).toString(); u = "dni" }
                    }
                    SmallTile(
                        (if (overdue) "$name · po terminie" else name) + if (list.size > 1) "  ${idx % list.size + 1}/${list.size}" else "", v, u, m, size,
                        valueColor = if (overdue) CarColors.Red else if (next?.soon == true) CarColors.Orange else CarColors.Text,
                        labelColor = if (overdue) CarColors.Red else CarColors.Muted,
                    ) { UiState.go(Screen.SERVICE) }
                }
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
    onClick: () -> Unit,
) {
    CarCard(modifier.clickable(onClick = onClick), radius = 20.dp) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Caption(label, color = labelColor)
            ValueText(value, unit, size, Modifier.fillMaxWidth(), color = valueColor)
        }
    }
}
