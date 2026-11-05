package pl.mati.carlauncher.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.VolumeDown
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.mati.carlauncher.data.SystemControls
import pl.mati.carlauncher.data.WeatherRepository

/** Wygląd 2.0: pasek na dole (jak w Tesli) – auto, aplikacje, mikrofon, głośność. */
@Composable
fun BottomBar2(current: Screen) {
    val ctx = LocalContext.current
    val accent = LocalAccent.current
    val d by pl.mati.carlauncher.data.Vehicle.data.collectAsState()
    val temp by WeatherRepository.temperature.collectAsState()
    val vol by SystemControls.volume.collectAsState()
    val voice by pl.mati.carlauncher.data.VoiceCommands.state.collectAsState()
    val startVoice = rememberVoiceStarter()
    Row(
        Modifier.fillMaxWidth().height(84.dp).background(CarColors.Dock).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // auto + temperatura na zewnątrz
        Row(
            Modifier.width(230.dp).clip(RoundedCornerShape(18.dp)).clickable { UiState.go(Screen.VEHICLE) }.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Rounded.DirectionsCar, "Pojazd", tint = if (current == Screen.VEHICLE) accent else CarColors.Text, modifier = Modifier.size(34.dp))
            Column {
                Text((d.outside ?: temp)?.let { "$it°C" } ?: "--", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("na zewnątrz", fontSize = 13.sp, color = CarColors.Muted)
            }
        }
        // aplikacje
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            BarButton(Icons.Rounded.GridView, "Ekran główny", current == Screen.HOME) { UiState.go(Screen.HOME) }
            BarButton(Icons.Rounded.Navigation, "Nawigacja", current == Screen.NAV) { UiState.go(Screen.NAV) }
            BarButton(Icons.Rounded.MusicNote, "Muzyka", current == Screen.MUSIC) { UiState.go(Screen.MUSIC) }
            BarButton(Icons.Rounded.Phone, "Telefon", current == Screen.PHONE) { UiState.go(Screen.PHONE) }
            BarButton(Icons.Rounded.Apps, "Aplikacje", current in setOf(Screen.APPS, Screen.SETTINGS, Screen.TRIPS, Screen.SERVICE, Screen.WEATHER, Screen.FUEL)) { UiState.go(Screen.APPS) }
        }
        // mikrofon i głośność
        Row(Modifier.width(230.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            BarButton(Icons.Rounded.Mic, "Komenda głosowa", voice.phase == pl.mati.carlauncher.data.VoiceCommands.Phase.LISTENING) { startVoice() }
            BarButton(Icons.Rounded.VolumeDown, "Ciszej", false) { SystemControls.setVolume(ctx, vol - 1) }
            Text(vol.toString(), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = CarColors.Muted, modifier = Modifier.width(28.dp))
            BarButton(Icons.Rounded.VolumeUp, "Głośniej", false) { SystemControls.setVolume(ctx, vol + 1) }
        }
    }
}

@Composable
private fun BarButton(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current
    val tint by animateColorAsState(if (active) accent else CarColors.Text, label = "bar")
    Column(
        Modifier.width(76.dp).fillMaxHeight().clip(RoundedCornerShape(16.dp)).clickable(onClickLabel = label, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(32.dp))
        Box(Modifier.padding(top = 6.dp).size(width = 18.dp, height = 3.dp).clip(CircleShape).background(if (active) accent else Color.Transparent))
    }
}
