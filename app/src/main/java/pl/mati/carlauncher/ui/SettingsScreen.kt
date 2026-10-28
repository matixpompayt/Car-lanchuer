package pl.mati.carlauncher.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.WbSunny
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.mati.carlauncher.data.LocationRepository
import pl.mati.carlauncher.data.MediaRepository
import pl.mati.carlauncher.data.ObdManager
import pl.mati.carlauncher.data.Prefs
import pl.mati.carlauncher.data.SystemControls
import pl.mati.carlauncher.data.UpdateManager

private data class Cat(val id: String, val icon: ImageVector, val label: String)

private val CATS = listOf(
    Cat("look", Icons.Rounded.Palette, "Wygląd"),
    Cat("disp", Icons.Rounded.Brightness6, "Wyświetlacz"),
    Cat("sound", Icons.Rounded.VolumeUp, "Dźwięk"),
    Cat("conn", Icons.Rounded.Bluetooth, "Połączenia"),
    Cat("car", Icons.Rounded.DirectionsCar, "Pojazd i OBD2"),
    Cat("sys", Icons.Rounded.Info, "System"),
)

private data class BtDevice(val name: String, val address: String)

@SuppressLint("MissingPermission")
private fun bondedDevices(): List<BtDevice> = try {
    @Suppress("DEPRECATION")
    BluetoothAdapter.getDefaultAdapter()?.bondedDevices.orEmpty()
        .map { BtDevice(it.name ?: it.address, it.address) }
        .sortedWith(compareByDescending<BtDevice> { it.name.contains("OBD", true) || it.name.contains("ELM", true) }.thenBy { it.name })
} catch (_: Exception) {
    emptyList()
}

@Composable
fun SettingsScreen() {
    val page by UiState.settingsPage.collectAsState()
    val navKey = if (page == "update") "sys" else page
    val accent = LocalAccent.current

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.width(270.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Ustawienia", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 12.dp))
            CATS.forEach { c ->
                val on = c.id == navKey
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (on) accent.copy(alpha = 0.14f) else Color.Transparent)
                        .clickable { UiState.settingsPage.value = c.id }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Icon(c.icon, null, tint = if (on) accent else CarColors.Text2, modifier = Modifier.size(24.dp))
                    Text(c.label, fontSize = 19.sp, fontWeight = FontWeight.Medium, color = if (on) accent else CarColors.Text2)
                }
            }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (page) {
                "look" -> lookPage()
                "disp" -> displayPage()
                "sound" -> soundPage()
                "conn" -> connPage()
                "car" -> carPage()
                "update" -> updatePage()
                else -> systemPage()
            }
            item { Box(Modifier.height(16.dp)) }
        }
    }
}

private fun LazyListScope.lookPage() {
    item { SectionLabel("Kolor akcentu") }
    item {
        val accent by Prefs.accent.collectAsState()
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            CarColors.AccentOptions.forEach { c -> Swatch(Color(c), c == accent) { Prefs.setAccent(c) } }
        }
    }
    item { SectionLabel("Tapeta") }
    item {
        val wall by Prefs.wallpaper.collectAsState()
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Wallpapers.forEachIndexed { i, w -> WallpaperCard(w.first, w.second, wall == i, { Prefs.setWallpaper(i) }, Modifier.weight(1f)) }
        }
    }
    item { SectionLabel("Tryb") }
    item {
        val v by Prefs.autoNight.collectAsState()
        SettingRow("Tryb nocny automatycznie", "Ciemniejszy ekran od 19:00 do 6:00", onClick = { Prefs.setAutoNight(!v) }) { CarSwitch(v) }
    }
    item {
        val v by Prefs.nightNow.collectAsState()
        SettingRow("Tryb nocny teraz", "Przyciemnia cały interfejs", onClick = { Prefs.setNightNow(!v) }) { CarSwitch(v) }
    }
    item {
        val v by Prefs.bootAnim.collectAsState()
        SettingRow("Animacja uruchamiania", "Logo CAR OS przy starcie radia", onClick = { Prefs.setBootAnim(!v) }) { CarSwitch(v) }
    }
}

private fun LazyListScope.displayPage() {
    item { SectionLabel("Jasność") }
    item {
        val ctx = LocalContext.current
        val bri by SystemControls.brightness.collectAsState()
        LaunchedEffect(Unit) { SystemControls.refresh(ctx) }
        FillSlider(bri / 100f, { SystemControls.setBrightness(ctx as? Activity, ctx, (it * 100).toInt()) }, Icons.Rounded.WbSunny, "Jasność ekranu", "$bri%", Modifier.fillMaxWidth())
    }
    item {
        val ctx = LocalContext.current
        val can = SystemControls.canWriteSettings(ctx)
        SettingRow(
            "Zmiana ustawień systemu",
            if (can) "Dozwolona – suwak zmienia jasność całego radia" else "Wymagana do sterowania jasnością całego radia",
            onClick = { SystemControls.requestWriteSettings(ctx) },
        ) { StatusText(can) }
    }
    item { SectionLabel("Launcher") }
    item {
        val ctx = LocalContext.current
        SettingRow("Domyślny ekran główny", "Ustaw CAR OS jako launcher radia", onClick = { Launch.start(ctx, Intent(Settings.ACTION_HOME_SETTINGS)) }) { Chevron() }
    }
    item {
        val ctx = LocalContext.current
        SettingRow("Uprawnienia aplikacji", "Lokalizacja (mapa, GPS, pogoda), Bluetooth, telefon", onClick = {
            Launch.start(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)))
        }) { Chevron() }
    }
}

private fun LazyListScope.soundPage() {
    item { SectionLabel("Głośność") }
    item {
        val ctx = LocalContext.current
        val vol by SystemControls.volume.collectAsState()
        val max by SystemControls.volumeMax.collectAsState()
        LaunchedEffect(Unit) { SystemControls.refresh(ctx) }
        FillSlider(if (max > 0) vol.toFloat() / max else 0f, { SystemControls.setVolume(ctx, Math.round(it * max)) }, Icons.Rounded.VolumeUp, "Multimedia", "$vol / $max", Modifier.fillMaxWidth())
    }
    item {
        val ctx = LocalContext.current
        SettingRow("Korektor", "Systemowy korektor dźwięku", onClick = { Launch.equalizer(ctx) }) { Chevron() }
    }
    item {
        val ctx = LocalContext.current
        val access by MediaRepository.access.collectAsState()
        SettingRow(
            "Dostęp do powiadomień",
            if (access) "Włączony – sterowanie muzyką i manewry działają" else "Wymagany do sterowania muzyką i manewrów",
            onClick = { Launch.notificationAccess(ctx) },
        ) { StatusText(access) }
    }
}

private fun LazyListScope.connPage() {
    item { SectionLabel("Sieci") }
    item {
        val ctx = LocalContext.current
        val wifi by SystemControls.wifiOn.collectAsState()
        LaunchedEffect(Unit) { SystemControls.refresh(ctx) }
        SettingRow("Wi-Fi", if (wifi) "Włączone" else "Wyłączone", onClick = { SystemControls.openWifiPanel(ctx) }) { Chevron() }
    }
    item {
        val ctx = LocalContext.current
        val bt by SystemControls.bluetoothOn.collectAsState()
        SettingRow("Bluetooth", if (bt) "Włączony" else "Wyłączony", onClick = {
            if (!SystemControls.toggleBluetooth(ctx)) Launch.start(ctx, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }) { CarSwitch(bt) }
    }
    item {
        val ctx = LocalContext.current
        SettingRow("Urządzenia Bluetooth", "Parowanie telefonu i adaptera OBD2", onClick = { Launch.start(ctx, Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }) { Chevron() }
    }
}

private fun LazyListScope.carPage() {
    item { SectionLabel("Samochód") }
    item {
        val p by Prefs.profile.collectAsState()
        SettingRow(p.carName, listOf(p.year, p.fuel).filter { it.isNotBlank() }.joinToString(" · "), leading = Icons.Rounded.DirectionsCar, onClick = { UiState.openSetup(2) }) { Chevron() }
    }
    item {
        val p by Prefs.profile.collectAsState()
        SettingRow("Obrotomierz", "0–${p.rpmMax} obr/min, czerwone pole od ${p.rpmRed}") {
            Text(if (p.isDiesel) "Diesel" else "Benzyna", fontSize = 18.sp, color = CarColors.Text2)
        }
    }
    item {
        val status by ObdManager.status.collectAsState()
        val msg by ObdManager.message.collectAsState()
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Adapter ELM327", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(obdColor(status)))
            Text(
                when (status) {
                    ObdManager.Status.CONNECTED -> "Połączono"
                    ObdManager.Status.CONNECTING -> "Łączenie…"
                    ObdManager.Status.ERROR -> msg ?: "Błąd"
                    ObdManager.Status.OFF -> "Wyłączone"
                },
                fontSize = 17.sp, color = CarColors.Muted, maxLines = 1,
            )
        }
    }
    item {
        var devices by remember { mutableStateOf(bondedDevices()) }
        val mac by Prefs.obdMac.collectAsState()
        val ctx = LocalContext.current
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (devices.isEmpty()) {
                SettingRow("Brak sparowanych urządzeń", "Sparuj ELM327 w Bluetooth radia (PIN 1234 lub 0000)", onClick = {
                    Launch.start(ctx, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                }) { Chevron() }
            }
            devices.forEach { d ->
                SettingRow(d.name, d.address, leading = Icons.Rounded.Bluetooth, onClick = {
                    Prefs.setObdMac(d.address)
                    ObdManager.start(d.address)
                }) {
                    if (d.address == mac) Icon(Icons.Rounded.CheckCircle, "Wybrane", tint = LocalAccent.current, modifier = Modifier.size(28.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton("Odśwież listę", { devices = bondedDevices() }, height = 52.dp, icon = Icons.Rounded.Refresh)
                if (mac != null) SecondaryButton("Wyłącz OBD", { Prefs.setObdMac(null); ObdManager.stop() }, height = 52.dp)
            }
        }
    }
    item { SectionLabel("Dane na żywo") }
    item {
        val d by ObdManager.data.collectAsState()
        val gps by LocationRepository.gpsSpeed.collectAsState()
        val cells = listOf(
            "Prędkość" to ((d.speed ?: gps)?.let { "$it km/h" } ?: "--"),
            "Obroty" to (d.rpm?.toString() ?: "--"),
            "Płyn" to (d.coolant?.let { "$it °C" } ?: "--"),
            "MAP" to (d.mapKpa?.let { "$it kPa" } ?: "--"),
            "Obciążenie" to (d.load?.let { "$it %" } ?: "--"),
            "Napięcie" to (d.voltage?.let { "${it.pl1()} V" } ?: "--"),
        )
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            cells.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { (k, v) ->
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(CarColors.Card).padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Caption(k)
                            Text(v, fontFamily = Condensed, fontSize = 30.sp)
                        }
                    }
                }
            }
        }
    }
}

private fun LazyListScope.systemPage() {
    item { SectionLabel("Oprogramowanie") }
    item {
        val st by UpdateManager.state.collectAsState()
        val sub = when (val s = st) {
            is UpdateManager.State.Available -> "CAR OS ${UpdateManager.currentVersion} · dostępna ${s.r.name}"
            is UpdateManager.State.Ready -> "Pobrano ${s.r.name} – gotowa do instalacji"
            is UpdateManager.State.Downloading -> "Pobieranie ${s.r.name}…"
            else -> "CAR OS ${UpdateManager.currentVersion}"
        }
        SettingRow("Aktualizacja systemu", sub, onClick = { UiState.settingsPage.value = "update" }) {
            if (st is UpdateManager.State.Available || st is UpdateManager.State.Ready) {
                Box(Modifier.clip(RoundedCornerShape(10.dp)).background(LocalAccent.current).padding(horizontal = 10.dp, vertical = 4.dp)) {
                    Text("NOWA", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = CarColors.Bg)
                }
            }
            Chevron()
        }
    }
    item { SectionLabel("Profil") }
    item {
        val p by Prefs.profile.collectAsState()
        SettingRow("Kierowca", p.name.ifBlank { "—" }, onClick = { UiState.openSetup(1) }) { Chevron() }
    }
    item {
        val p by Prefs.profile.collectAsState()
        SettingRow("Samochód", p.carName, onClick = { UiState.openSetup(2) }) { Chevron() }
    }
    item {
        val p by Prefs.profile.collectAsState()
        SettingRow("Ulubione miejsca", listOf(p.home, p.work).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Dom i praca" }, onClick = { UiState.openSetup(4) }) { Chevron() }
    }
    item { SettingRow("Kreator pierwszego uruchomienia", "Przejdź konfigurację od nowa", onClick = { UiState.openSetup(0) }) { Chevron() } }
    item { SectionLabel("System") }
    item { SettingRow("Uruchom ponownie", "Pokaż animację startu", onClick = { UiState.reboot() }) { Chevron() } }
    item {
        SettingRow("Przywróć domyślny wygląd", "Kolor, tapeta, tryb nocny, animacja", onClick = {
            Prefs.resetLook(); Toaster.show("Przywrócono domyślny wygląd")
        }) { Chevron() }
    }
    item { SettingRow("Wersja", "CAR OS ${UpdateManager.currentVersion} · Android 10") {} }
}

private fun LazyListScope.updatePage() {
    item {
        Row(
            Modifier.clip(RoundedCornerShape(14.dp)).clickable { UiState.settingsPage.value = "sys" }.padding(end = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.ChevronLeft, null, tint = CarColors.Text2, modifier = Modifier.size(30.dp))
            Text("System", fontSize = 19.sp, color = CarColors.Text2)
        }
    }
    item {
        CarCard(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                CarOsLogo(72.dp)
                Column {
                    Text("CAR OS ${UpdateManager.currentVersion}", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text("Kanał stabilny · GitHub Releases", fontSize = 17.sp, color = CarColors.Muted)
                }
            }
        }
    }
    item {
        val ctx = LocalContext.current
        val st by UpdateManager.state.collectAsState()
        val accent = LocalAccent.current
        when (val s = st) {
            is UpdateManager.State.Idle -> Row(verticalAlignment = Alignment.CenterVertically) {
                PrimaryButton("Sprawdź aktualizacje", { UpdateManager.check(ctx) })
            }
            is UpdateManager.State.Checking -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(vertical = 14.dp)) {
                Spinner(); Text("Łączenie z serwerem aktualizacji…", fontSize = 20.sp, color = CarColors.Text2)
            }
            is UpdateManager.State.UpToDate -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                Icon(Icons.Rounded.CheckCircle, null, tint = CarColors.Green, modifier = Modifier.size(32.dp))
                Text("Masz najnowszą wersję systemu", fontSize = 20.sp, modifier = Modifier.weight(1f))
                SecondaryButton("Sprawdź ponownie", { UpdateManager.check(ctx) }, height = 52.dp)
            }
            is UpdateManager.State.Error -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(s.message, fontSize = 18.sp, color = CarColors.Red)
                PrimaryButton("Spróbuj ponownie", { UpdateManager.check(ctx) })
            }
            is UpdateManager.State.Available -> ReleaseCard(s.r) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PrimaryButton("Pobierz i zainstaluj", { UpdateManager.download(ctx) })
                }
            }
            is UpdateManager.State.Downloading -> ReleaseCard(s.r) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProgressBar(s.progress, accent, height = 10.dp)
                    Row(Modifier.fillMaxWidth()) {
                        val mb = s.r.sizeBytes / 1048576f
                        Text("Pobieranie… ${(mb * s.progress).pl1()} / ${mb.pl1()} MB", fontSize = 17.sp, color = CarColors.Muted, modifier = Modifier.weight(1f))
                        Text("${(s.progress * 100).toInt()}%", fontSize = 17.sp, color = CarColors.Muted)
                    }
                    SecondaryButton("Anuluj", { UpdateManager.cancel() }, height = 52.dp)
                }
            }
            is UpdateManager.State.Ready -> ReleaseCard(s.r) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    PrimaryButton("Zainstaluj", {
                        if (!UpdateManager.install(ctx)) Toaster.show("Zezwól CAR OS na instalowanie aplikacji i dotknij ponownie")
                    })
                    Text("Potwierdź „Zainstaluj” w oknie systemu. Najlepiej na postoju.", fontSize = 16.sp, color = CarColors.Muted)
                }
            }
        }
    }
    item { SectionLabel("Opcje") }
    item {
        val v by Prefs.autoUpdate.collectAsState()
        SettingRow("Sprawdzaj automatycznie", "Po każdym uruchomieniu radia", onClick = { Prefs.setAutoUpdate(!v) }) { CarSwitch(v) }
    }
    item {
        val v by Prefs.updateWifiOnly.collectAsState()
        SettingRow("Pobieraj tylko przez Wi-Fi", "Oszczędza transfer z karty SIM", onClick = { Prefs.setUpdateWifiOnly(!v) }) { CarSwitch(v) }
    }
}

@Composable
private fun ReleaseCard(r: UpdateManager.Release, actions: @Composable () -> Unit) {
    val accent = LocalAccent.current
    CarCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 26.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.clip(RoundedCornerShape(10.dp)).background(accent).padding(horizontal = 10.dp, vertical = 4.dp)) {
                    Text("NOWA", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = CarColors.Bg)
                }
                Text("CAR OS ${r.name}", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (r.sizeBytes > 0) Text("${(r.sizeBytes / 1048576f).pl1()} MB", fontSize = 17.sp, color = CarColors.Muted)
            }
            if (r.notes.isNotBlank()) Text(r.notes.take(600), fontSize = 18.sp, color = CarColors.Text2)
            actions()
        }
    }
}

@Composable
private fun Chevron() = Icon(Icons.Rounded.ChevronRight, null, tint = CarColors.Muted, modifier = Modifier.size(24.dp))

@Composable
private fun StatusText(ok: Boolean) =
    Text(if (ok) "Włączone" else "Włącz", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = if (ok) CarColors.Green else LocalAccent.current)
