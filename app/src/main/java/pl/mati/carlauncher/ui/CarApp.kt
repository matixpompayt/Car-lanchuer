package pl.mati.carlauncher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import pl.mati.carlauncher.data.Prefs

@Composable
fun CarApp() {
    val phase by UiState.phase.collectAsState()
    Box(Modifier.fillMaxSize().background(CarColors.BgDeep)) {
        Crossfade(targetState = phase, animationSpec = tween(500), label = "phase") { p ->
            when (p) {
                Phase.BOOT -> BootScreen()
                Phase.SETUP -> SetupWizard()
                Phase.MAIN -> Shell()
            }
        }
        NightDim()
        ToastHost()
    }
}

@Composable
private fun NightDim() {
    val auto by Prefs.autoNight.collectAsState()
    val now by Prefs.nightNow.collectAsState()
    val time by rememberNow()
    val night = now || (auto && isNightHour(time))
    if (night) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f)))
}

@Composable
private fun Shell() {
    val screen by UiState.screen.collectAsState()
    val cc by UiState.controlCenter.collectAsState()
    val wall by Prefs.wallpaper.collectAsState()
    val settingsPage by UiState.settingsPage.collectAsState()

    // Launcher: wstecz nigdy nie zamyka aplikacji.
    BackHandler(enabled = true) {
        when {
            cc -> UiState.controlCenter.value = false
            screen == Screen.SETTINGS && settingsPage == "update" -> UiState.settingsPage.value = "sys"
            screen == Screen.SETTINGS -> UiState.go(Screen.APPS)
            else -> UiState.go(Screen.HOME)
        }
    }

    Box(Modifier.fillMaxSize().background(Wallpapers[wall.coerceIn(0, Wallpapers.lastIndex)].second)) {
        Row(Modifier.fillMaxSize()) {
            Dock(screen)
            Box(Modifier.width(1.dp).fillMaxHeight().background(CarColors.Border))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                StatusBar()
                Box(Modifier.weight(1f).padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
                    Crossfade(targetState = screen, animationSpec = tween(220), label = "screen") { s ->
                        when (s) {
                            Screen.HOME -> HomeScreen()
                            Screen.NAV -> NavScreen()
                            Screen.MUSIC -> MusicScreen()
                            Screen.PHONE -> PhoneScreen()
                            Screen.VEHICLE -> VehicleScreen()
                            Screen.APPS -> AppsScreen()
                            Screen.SETTINGS -> SettingsScreen()
                        }
                    }
                }
            }
        }
        RoadAlertBanner()
        ControlCenter()
        VolumeHud()
    }
}

@Composable
private fun Dock(current: Screen) {
    Column(
        Modifier
            .width(99.dp)
            .fillMaxHeight()
            .background(CarColors.Dock)
            .padding(vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DockButton(Icons.Rounded.GridView, "Ekran główny", current == Screen.HOME) { UiState.go(Screen.HOME) }
            DockButton(Icons.Rounded.Navigation, "Nawigacja", current == Screen.NAV) { UiState.go(Screen.NAV) }
            DockButton(Icons.Rounded.MusicNote, "Muzyka", current == Screen.MUSIC) { UiState.go(Screen.MUSIC) }
            DockButton(Icons.Rounded.Phone, "Telefon", current == Screen.PHONE) { UiState.go(Screen.PHONE) }
            DockButton(Icons.Rounded.Speed, "Pojazd", current == Screen.VEHICLE) { UiState.go(Screen.VEHICLE) }
        }
        DockButton(Icons.Rounded.Apps, "Aplikacje", current == Screen.APPS || current == Screen.SETTINGS) {
            UiState.go(Screen.APPS)
        }
    }
}

@Composable
private fun DockButton(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Box(
        Modifier
            .size(72.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (active) accent.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = if (active) accent else CarColors.Muted, modifier = Modifier.size(34.dp))
    }
}
