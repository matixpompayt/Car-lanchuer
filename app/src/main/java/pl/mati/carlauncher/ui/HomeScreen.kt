package pl.mati.carlauncher.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import pl.mati.carlauncher.data.WeatherRepository

@Composable
fun HomeScreen(onNavigate: (Screen) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TopBar()
        Row(
            Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            MapCard(Modifier.weight(1.55f).fillMaxHeight())
            Column(
                Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                MediaCard(Modifier.weight(1f).fillMaxWidth())
                VehicleTiles(Modifier.fillMaxWidth()) { onNavigate(Screen.VEHICLE) }
            }
        }
    }
}

@Composable
private fun TopBar() {
    val now by rememberNow()
    val temp by WeatherRepository.temperature.collectAsState()
    val obd by ObdManager.status.collectAsState()
    Row(
        Modifier.fillMaxWidth().height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(formatTime(now), fontSize = 38.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(16.dp))
        Text(formatDate(now), fontSize = 19.sp, color = CarColors.Muted)
        Spacer(Modifier.weight(1f))
        Row(
            horizontalArrangement = Arrangement.spacedBy(22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (obd != ObdManager.Status.OFF) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(obdColor(obd)))
                    Text("OBD", fontSize = 19.sp, color = CarColors.Muted)
                }
            }
            temp?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Thermostat, null, tint = CarColors.Muted, modifier = Modifier.size(22.dp))
                    Text("$it°C", fontSize = 19.sp, color = CarColors.Muted)
                }
            }
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
            Column(
                Modifier.fillMaxSize().padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            ) {
                Text("Sterowanie muzyką", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Włącz dostęp do powiadomień dla Car Launcher – wtedy zobaczysz tu Spotify, radio i manewry z Map Google.",
                    fontSize = 17.sp, color = CarColors.Text2,
                )
                Box(
                    Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(LocalAccent.current)
                        .clickable { Launch.start(ctx, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Text("Włącz dostęp", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = CarColors.Bg)
                }
            }
        } else {
            MediaContent(m, pos)
        }
    }
}

@Composable
private fun MediaContent(m: MediaRepository.MediaState, pos: Long) {
    val ctx = LocalContext.current
    run {
        Column(
            Modifier.fillMaxSize().padding(22.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { Launch.music(ctx) },
            ) {
                AlbumArt(m.art, 104.dp, 16.dp)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Caption(m.appName ?: "Muzyka", size = 14.sp)
                    Text(
                        m.title ?: "Nic nie gra", fontSize = 25.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        m.artist ?: "", fontSize = 19.sp, color = CarColors.Text2,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
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
                    78.dp, MediaRepository::playPause, filled = true, iconSize = 38.dp,
                )
                RoundIconButton(Icons.Rounded.SkipNext, "Następny", 64.dp, MediaRepository::next, iconSize = 36.dp)
            }
        }
    }
}

@Composable
private fun VehicleTiles(modifier: Modifier, onClick: () -> Unit) {
    val d by ObdManager.data.collectAsState()
    val gps by LocationRepository.gpsSpeed.collectAsState()
    Row(
        modifier.clickable(onClickLabel = "Otwórz ekran Pojazd", onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SmallTile("Prędkość", (d.speed ?: gps)?.toString() ?: "--", "km/h", Modifier.weight(1f))
        SmallTile("Płyn chł.", d.coolant?.toString() ?: "--", "°C", Modifier.weight(1f))
        SmallTile("Doład.", d.boostBar?.pl1() ?: "--", "bar", Modifier.weight(1f))
    }
}

@Composable
private fun SmallTile(label: String, value: String, unit: String, modifier: Modifier) {
    CarCard(modifier, radius = 20.dp) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Caption(label)
            ValueText(value, unit, 44.sp)
        }
    }
}
