package pl.mati.carlauncher.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

enum class Screen { HOME, VEHICLE, APPS, SETTINGS }

@Composable
fun CarApp(screen: Screen, onNavigate: (Screen) -> Unit) {
    // Launcher: wstecz zawsze wraca do ekranu głównego, nigdy nie zamyka aplikacji.
    BackHandler(enabled = true) {
        onNavigate(if (screen == Screen.SETTINGS) Screen.APPS else Screen.HOME)
    }
    Row(
        Modifier
            .fillMaxSize()
            .background(if (screen == Screen.VEHICLE) CarColors.BgDeep else CarColors.Bg),
    ) {
        Dock(screen, onNavigate)
        Box(Modifier.width(1.dp).fillMaxHeight().background(CarColors.Border))
        Box(Modifier.weight(1f).fillMaxHeight()) {
            when (screen) {
                Screen.HOME -> HomeScreen(onNavigate)
                Screen.VEHICLE -> VehicleScreen()
                Screen.APPS -> AppsScreen(onNavigate)
                Screen.SETTINGS -> SettingsScreen(onNavigate)
            }
        }
    }
}

@Composable
private fun Dock(current: Screen, onNavigate: (Screen) -> Unit) {
    val ctx = LocalContext.current
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
            DockButton(Icons.Rounded.GridView, "Ekran główny", current == Screen.HOME) { onNavigate(Screen.HOME) }
            DockButton(Icons.Rounded.Navigation, "Nawigacja", false) { Launch.navigation(ctx) }
            DockButton(Icons.Rounded.MusicNote, "Muzyka", false) { Launch.music(ctx) }
            DockButton(Icons.Rounded.Phone, "Telefon", false) { Launch.phone(ctx) }
            DockButton(Icons.Rounded.Speed, "Pojazd", current == Screen.VEHICLE) { onNavigate(Screen.VEHICLE) }
        }
        DockButton(
            Icons.Rounded.Apps, "Aplikacje",
            current == Screen.APPS || current == Screen.SETTINGS,
        ) { onNavigate(Screen.APPS) }
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
        Icon(
            icon,
            contentDescription = label,
            tint = if (active) accent else CarColors.Muted,
            modifier = Modifier.size(34.dp),
        )
    }
}
