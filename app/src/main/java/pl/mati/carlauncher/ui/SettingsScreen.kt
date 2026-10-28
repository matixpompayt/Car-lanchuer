package pl.mati.carlauncher.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.mati.carlauncher.data.MediaRepository
import pl.mati.carlauncher.data.ObdManager
import pl.mati.carlauncher.data.Prefs

private data class BtDevice(val name: String, val address: String)

@SuppressLint("MissingPermission")
private fun bondedDevices(): List<BtDevice> = try {
    @Suppress("DEPRECATION")
    BluetoothAdapter.getDefaultAdapter()?.bondedDevices.orEmpty()
        .map { BtDevice(it.name ?: it.address, it.address) }
        .sortedWith(compareByDescending<BtDevice> { it.name.contains("OBD", true) || it.name.contains("ELM", true) }.thenBy { it.name })
} catch (_: SecurityException) {
    emptyList()
}

@Composable
fun SettingsScreen(onNavigate: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val access by MediaRepository.access.collectAsState()
    val mac by Prefs.obdMac.collectAsState()
    val status by ObdManager.status.collectAsState()
    val message by ObdManager.message.collectAsState()
    val accentInt by Prefs.accent.collectAsState()
    var devices by remember { mutableStateOf(emptyList<BtDevice>()) }
    LaunchedEffect(Unit) { devices = bondedDevices() }

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(
            Modifier.fillMaxWidth().height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            RoundIconButton(Icons.Rounded.ArrowBack, "Wstecz", 56.dp, { onNavigate(Screen.APPS) })
            Text("Ustawienia", fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
            item {
                SettingRow(
                    "Domyślny ekran główny",
                    "Ustaw Car Launcher jako launcher, żeby radio startowało od razu w nim.",
                    "Ustaw",
                ) { Launch.start(ctx, Intent(Settings.ACTION_HOME_SETTINGS)) }
            }
            item {
                SettingRow(
                    "Dostęp do powiadomień",
                    if (access) "Włączony – muzyka i manewry z Map Google działają."
                    else "Wymagany do sterowania muzyką i pokazywania manewrów.",
                    if (access) "Zmień" else "Włącz",
                    highlight = !access,
                ) { Launch.start(ctx, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            }
            item {
                SettingRow(
                    "Uprawnienia aplikacji",
                    "Lokalizacja (mapa, prędkość GPS, pogoda) i Bluetooth.",
                    "Otwórz",
                ) {
                    Launch.start(
                        ctx,
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)),
                    )
                }
            }

            item {
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("ELM327 (OBD2)", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Box(Modifier.size(10.dp).clip(CircleShape).background(obdColor(status)))
                    Text(
                        when (status) {
                            ObdManager.Status.CONNECTED -> "Połączono"
                            ObdManager.Status.CONNECTING -> "Łączenie…"
                            ObdManager.Status.ERROR -> message ?: "Błąd"
                            ObdManager.Status.OFF -> "Wyłączone"
                        },
                        fontSize = 17.sp, color = CarColors.Muted,
                    )
                }
            }
            if (devices.isEmpty()) {
                item {
                    SettingRow(
                        "Brak sparowanych urządzeń",
                        "Sparuj ELM327 w ustawieniach Bluetooth radia (PIN zwykle 1234 lub 0000), potem wróć tutaj.",
                        "Bluetooth",
                    ) { Launch.start(ctx, Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                }
            }
            items(devices, key = { it.address }) { dev ->
                DeviceRow(dev, selected = dev.address == mac) {
                    Prefs.setObdMac(dev.address)
                    ObdManager.start(dev.address)
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SmallButton("Odśwież listę") { devices = bondedDevices() }
                    if (mac != null) {
                        SmallButton("Wyłącz OBD") {
                            Prefs.setObdMac(null)
                            ObdManager.stop()
                        }
                    }
                }
            }

            item {
                Text(
                    "Kolor akcentu", fontSize = 24.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CarColors.AccentOptions.forEach { c ->
                        val selected = c == accentInt
                        Box(
                            Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(Color(c))
                                .border(4.dp, if (selected) CarColors.Text else Color.Transparent, CircleShape)
                                .clickable(onClickLabel = "Wybierz kolor") { Prefs.setAccent(c) },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) Icon(Icons.Rounded.Check, null, tint = CarColors.Bg, modifier = Modifier.size(30.dp))
                        }
                    }
                }
            }
            item { Box(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    action: String,
    highlight: Boolean = false,
    onClick: () -> Unit,
) {
    val accent = LocalAccent.current
    CarCard(Modifier.fillMaxWidth(), radius = 18.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, fontSize = 16.sp, color = CarColors.Muted)
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (highlight) accent else CarColors.Track)
                    .clickable(onClick = onClick)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Text(action, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = if (highlight) CarColors.Bg else CarColors.Text)
            }
        }
    }
}

@Composable
private fun DeviceRow(dev: BtDevice, selected: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current
    CarCard(Modifier.fillMaxWidth().clickable(onClick = onClick), radius = 18.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(Icons.Rounded.Bluetooth, null, tint = if (selected) accent else CarColors.Muted, modifier = Modifier.size(28.dp))
            Column(Modifier.weight(1f)) {
                Text(dev.name, fontSize = 19.sp, fontWeight = FontWeight.Medium)
                Text(dev.address, fontSize = 15.sp, color = CarColors.Muted)
            }
            if (selected) Icon(Icons.Rounded.Check, "Wybrane", tint = accent, modifier = Modifier.size(28.dp))
        }
    }
}

@Composable
private fun SmallButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(CarColors.Card)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Text(text, fontSize = 17.sp)
    }
}
