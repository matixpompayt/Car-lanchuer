package pl.mati.carlauncher.ui

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.border
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.mutableIntStateOf
import pl.mati.carlauncher.data.ConnectRepository
import pl.mati.carlauncher.data.WorkSchedule
import pl.mati.carlauncher.data.ParkingRepository
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.Download
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
import pl.mati.carlauncher.data.AlertRepository
import pl.mati.carlauncher.data.BackupManager
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
    Cat("auto", Icons.Rounded.PowerSettingsNew, "Autostart"),
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
    val navKey = when (page) { "update" -> "sys"; "connect" -> "conn"; else -> page }
    val accent = LocalAccent.current

    Box(Modifier.fillMaxSize()) {
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
                "car" -> { carPage(); speedSection(); alertsSection() }
                "auto" -> autostartPage()
                "update" -> updatePage()
                "connect" -> connectPage()
                else -> systemPage()
            }
            item { Box(Modifier.height(16.dp)) }
        }
    }
    AppPickerOverlay()
    }
}

object AppPicker {
    val kind = kotlinx.coroutines.flow.MutableStateFlow<SourceApps.Kind?>(null)
}

@Composable
private fun AppPickerOverlay() {
    val kind by AppPicker.kind.collectAsState()
    val k = kind ?: return
    val ctx = LocalContext.current
    val apps = remember { loadApps(ctx) }
    Box(
        Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp)).background(Color(0xF00A0C0F))
            .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {},
    ) {
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (k == SourceApps.Kind.RADIO) "Wybierz aplikację radia" else "Wybierz aplikację muzyki Bluetooth",
                    fontSize = 26.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                )
                SecondaryButton("Automatycznie", {
                    if (k == SourceApps.Kind.RADIO) Prefs.setRadioApp("") else Prefs.setBtApp("")
                    AppPicker.kind.value = null
                }, height = 52.dp)
                Box(Modifier.width(10.dp))
                SecondaryButton("Anuluj", { AppPicker.kind.value = null }, height = 52.dp)
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(apps, key = { it.pkg + "/" + it.activity }) { a ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CarColors.Card)
                            .clickable {
                                val comp = a.pkg + "/" + a.activity
                                if (k == SourceApps.Kind.RADIO) Prefs.setRadioApp(comp) else Prefs.setBtApp(comp)
                                AppPicker.kind.value = null
                                Toaster.show("Ustawiono: ${a.label}")
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        androidx.compose.foundation.Image(a.icon, null, modifier = Modifier.size(44.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.label, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                            Text(a.pkg, fontSize = 14.sp, color = CarColors.Muted)
                        }
                    }
                }
            }
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
    item { SectionLabel("Kafle ekranu głównego (maks. 4)") }
    item {
        val str by Prefs.homeTiles.collectAsState()
        val on = str.split(",").filter { k -> TILE_DEFS.any { it.first == k } }
        val ordered = on + TILE_DEFS.map { it.first }.filter { it !in on }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ordered.forEach { key ->
                val label = TILE_DEFS.first { it.first == key }.second
                val enabled = key in on
                val idx = on.indexOf(key)
                SettingRow(
                    label, if (enabled) "Pozycja ${idx + 1}" else null,
                    onClick = {
                        val next = if (enabled) on - key else if (on.size >= 4) { Toaster.show("Maksymalnie 4 kafle"); on } else on + key
                        Prefs.setHomeTiles(next.joinToString(","))
                    },
                ) {
                    if (enabled && idx > 0) RoundIconButton(Icons.Rounded.KeyboardArrowUp, "W górę", 44.dp, {
                        val l = on.toMutableList(); java.util.Collections.swap(l, idx, idx - 1); Prefs.setHomeTiles(l.joinToString(","))
                    })
                    if (enabled && idx < on.lastIndex) RoundIconButton(Icons.Rounded.KeyboardArrowDown, "W dół", 44.dp, {
                        val l = on.toMutableList(); java.util.Collections.swap(l, idx, idx + 1); Prefs.setHomeTiles(l.joinToString(","))
                    })
                    CarSwitch(enabled)
                }
            }
        }
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
    item { SectionLabel("Źródła w radiu") }
    item {
        val ctx = LocalContext.current
        val chosen by Prefs.radioApp.collectAsState()
        val comp = remember(chosen) { SourceApps.resolve(ctx, SourceApps.Kind.RADIO) }
        SettingRow(
            "Radio FM/AM",
            (SourceApps.label(ctx, comp) ?: "Nie znaleziono – wybierz aplikację") + if (chosen.isBlank() && comp != null) " (wykryte)" else "",
            onClick = { AppPicker.kind.value = SourceApps.Kind.RADIO },
        ) { Text("Zmień", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = LocalAccent.current) }
    }
    item {
        val ctx = LocalContext.current
        val chosen by Prefs.btApp.collectAsState()
        val comp = remember(chosen) { SourceApps.resolve(ctx, SourceApps.Kind.BT) }
        SettingRow(
            "Muzyka Bluetooth (z radia)",
            (SourceApps.label(ctx, comp) ?: "Nie znaleziono – wybierz aplikację") + if (chosen.isBlank() && comp != null) " (wykryte)" else "",
            onClick = { AppPicker.kind.value = SourceApps.Kind.BT },
        ) { Text("Zmień", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = LocalAccent.current) }
    }
    item {
        val ctx = LocalContext.current
        SettingRow("Korektor", "Systemowy korektor dźwięku", onClick = { Launch.equalizer(ctx) }) { Chevron() }
    }
    item { SectionLabel("Diagnostyka źródeł") }
    item {
        val ctx = LocalContext.current
        val sessions by MediaRepository.sessionsDebug.collectAsState()
        val infos by pl.mati.carlauncher.data.SourceInfoRepository.byKey.collectAsState()
        val probeActions by pl.mati.carlauncher.data.BroadcastProbe.actions.collectAsState()
        val probeLog by pl.mati.carlauncher.data.BroadcastProbe.log.collectAsState()
        val probeScan by pl.mati.carlauncher.data.BroadcastProbe.scanning.collectAsState()
        LaunchedEffect(Unit) { MediaRepository.refresh(); pl.mati.carlauncher.data.NotificationRepository.rescan(ctx) }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(CarColors.Card).padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Włącz radio albo muzykę BT, wróć tutaj i zrób zdjęcie tej listy.", fontSize = 15.sp, color = CarColors.Muted)
            Text("Radio: " + (SourceApps.resolve(ctx, SourceApps.Kind.RADIO) ?: "nie wykryto"), fontSize = 15.sp, color = CarColors.Text2)
            Text("Bluetooth: " + (SourceApps.resolve(ctx, SourceApps.Kind.BT) ?: "nie wykryto"), fontSize = 15.sp, color = CarColors.Text2)
            Text("Sesje multimediów:", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (sessions.isEmpty()) Text("brak", fontSize = 15.sp, color = CarColors.Muted)
            sessions.forEach { Text("• $it", fontSize = 15.sp, color = CarColors.Text2) }
            val btSt by pl.mati.carlauncher.data.BtAudioRepository.state.collectAsState()
            val btSvc by pl.mati.carlauncher.data.BtAudioRepository.services.collectAsState()
            Text("Muzyka BT (AVRCP): " + if (btSt.connected) "połączono z ${btSt.service} · ${btSt.title ?: "—"} / ${btSt.artist ?: "—"}" else "nie połączono", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text("Usługi multimediów w systemie: " + btSvc.joinToString(", ").ifBlank { "brak" }, fontSize = 14.sp, color = CarColors.Text2)
            Text("Odczytane dane radia/BT:", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (infos.isEmpty()) Text("brak", fontSize = 15.sp, color = CarColors.Muted)
            infos.values.forEach { Text("• ${it.key}: ${it.title} | ${it.text} | ${it.sub}", fontSize = 15.sp, color = CarColors.Text2) }
            Text(
                "Nasłuch komunikatów: " + if (probeScan) "skanuję aplikację radia…" else "${probeActions.size} nazw",
                fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            )
            if (probeLog.isEmpty()) Text("nic jeszcze nie przyszło – przełącz stację / utwór i odśwież ten ekran", fontSize = 15.sp, color = CarColors.Muted)
            probeLog.take(15).forEach { Text(it, fontSize = 14.sp, color = CarColors.Text2) }
        }
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
    item { SectionLabel("Telefon") }
    item {
        val paired by ConnectRepository.paired.collectAsState()
        SettingRow(
            "CAR OS Connect",
            if (paired) "Aplikacja na telefon połączona – pokaż kod, aby dodać kolejny telefon" else "Aplikacja na telefon: gdzie zaparkowałem – połącz kodem QR",
            leading = Icons.Rounded.PhoneAndroid,
            onClick = { UiState.settingsPage.value = "connect" },
        ) { Chevron() }
    }
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

private fun LazyListScope.speedSection() {
    item { SectionLabel("Ograniczenia prędkości") }
    item {
        val v by Prefs.speedLimits.collectAsState()
        SettingRow("Pokazuj ograniczenia", "Znak z limitem na mapie (dane OpenStreetMap, wymaga internetu)", onClick = { Prefs.setSpeedLimits(!v) }) { CarSwitch(v) }
    }
    item {
        val t by Prefs.speedTolerance.collectAsState()
        SettingRow("Tolerancja", "Ostrzegaj powyżej limitu o $t km/h") {
            RoundIconButton(Icons.Rounded.Remove, "Mniej", 44.dp, { Prefs.setSpeedTolerance(t - 5) })
            Text("+$t", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            RoundIconButton(Icons.Rounded.Add, "Więcej", 44.dp, { Prefs.setSpeedTolerance(t + 5) })
        }
    }
    item {
        val v by Prefs.speedBeep.collectAsState()
        SettingRow("Dźwięk przy przekroczeniu", "Krótki sygnał, gdy przekroczysz limit + tolerancję", onClick = { Prefs.setSpeedBeep(!v) }) { CarSwitch(v) }
    }
}

private fun LazyListScope.alertsSection() {
    item { SectionLabel("Ostrzeżenia drogowe (CamSam / Yanosik)") }
    item {
        val v by Prefs.roadAlerts.collectAsState()
        SettingRow("Pokazuj ostrzeżenia", "Fotoradary, kontrole i zdarzenia z powiadomień CamSam na każdym ekranie", onClick = { Prefs.setRoadAlerts(!v) }) { CarSwitch(v) }
    }
    item {
        val raw by AlertRepository.raw.collectAsState()
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(CarColors.Card).padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Ostatnie powiadomienia CamSam", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            if (raw.isEmpty()) {
                Text("Brak – uruchom CamSam w tle i włącz dostęp do powiadomień dla CAR OS.", fontSize = 15.sp, color = CarColors.Muted)
            }
            raw.forEach { r ->
                Text(
                    "${formatTime(r.time)} · ${r.app}${if (r.ongoing) " (stałe)" else ""}: ${r.title} | ${r.text}",
                    fontSize = 15.sp, color = CarColors.Text2,
                )
            }
        }
    }
}

private fun LazyListScope.autostartPage() {
    item { SectionLabel("Po włączeniu zapłonu") }
    item {
        Text("„Zapłon” = start radia albo włączenie ekranu po co najmniej 3 minutach przerwy. Adapter OBD2 łączy się zawsze.", fontSize = 16.sp, color = CarColors.Muted)
    }
    item {
        val v by Prefs.autoResumeMusic.collectAsState()
        SettingRow("Wznów muzykę", "Odtwarza ostatnie źródło (Spotify, radio…) po kilku sekundach", onClick = { Prefs.setAutoResumeMusic(!v) }) { CarSwitch(v) }
    }
    item {
        val v by Prefs.suggestHome.collectAsState()
        val p by Prefs.profile.collectAsState()
        SettingRow(
            "Jedziesz do pracy? / do domu?",
            if (p.home.isBlank() && p.work.isBlank()) "Najpierw dodaj adresy domu i pracy (System → Ulubione miejsca)"
            else "Propozycja trasy według grafiku: 2 h przed zmianą do pracy, po zmianie do domu",
            onClick = { Prefs.setSuggestHome(!v) },
        ) { CarSwitch(v) }
    }
    workScheduleSection()
}

private fun LazyListScope.workScheduleSection() {
    item { SectionLabel("Grafik pracy") }
    item {
        val mode by WorkSchedule.mode.collectAsState()
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ChoiceChip("Tesla (zmiana Silver)", mode == "tesla", Modifier.weight(1f)) { WorkSchedule.setMode("tesla") }
            ChoiceChip("Niestandardowy", mode == "custom", Modifier.weight(1f)) { WorkSchedule.setMode("custom") }
        }
    }
    item {
        val mode by WorkSchedule.mode.collectAsState()
        val custom by WorkSchedule.custom.collectAsState()
        val days by WorkSchedule.days.collectAsState()
        val monday = remember { WorkSchedule.mondayOf(java.time.LocalDate.now()) }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (mode == "tesla") {
                Text("Rotacja co tydzień: nocna 22–6 (nd–czw) → pierwsza 6–14 (pn–pt) → popołudniowa 14–22 (pn–pt)", fontSize = 16.sp, color = CarColors.Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf("Ten tydzień" to monday, "Następny" to monday.plusWeeks(1), "Za 2 tygodnie" to monday.plusWeeks(2)).forEach { (label, m) ->
                        WeekCard(label, m, WorkSchedule.shiftFor(m), Modifier.weight(1f))
                    }
                }
            } else {
                // odczyt custom, żeby karta odświeżała się po zmianie
                custom.size
                WeekPicker("Ten tydzień", monday)
                WeekPicker("Następny tydzień", monday.plusWeeks(1))
                Text("Nieustawiony tydzień = taka sama zmiana jak ostatnio ustawiona.", fontSize = 15.sp, color = CarColors.Muted)
            }
            if (mode == "custom") Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Dni pracy", fontSize = 17.sp, color = CarColors.Text2, modifier = Modifier.width(110.dp))
                listOf("Pn", "Wt", "Śr", "Cz", "Pt", "So", "Nd").forEachIndexed { i, d ->
                    ChoiceChip(d, (i + 1) in days, Modifier.weight(1f)) { WorkSchedule.toggleDay(i + 1) }
                }
            }
        }
    }
}

@Composable
private fun WeekCard(label: String, monday: java.time.LocalDate, s: WorkSchedule.Shift?, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(CarColors.Card).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Caption("$label · ${WorkSchedule.startDays(monday, s)}")
        Text(s?.name ?: "—", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Text(s?.hours ?: "", fontSize = 16.sp, color = CarColors.Muted)
    }
}

@Composable
private fun WeekPicker(label: String, monday: java.time.LocalDate) {
    val custom by WorkSchedule.custom.collectAsState()
    val cur = custom[monday.toEpochDay()]
    val shown = cur ?: WorkSchedule.shiftFor(monday)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(CarColors.Card).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$label · ${WorkSchedule.weekRange(monday)}", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(
                if (cur == null) (shown?.let { "jak ostatnio: ${it.label}" } ?: "nieustawiony") else cur.label,
                fontSize = 16.sp, color = if (cur == null) CarColors.Muted else LocalAccent.current,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            WorkSchedule.PRESETS.forEach { p ->
                ChoiceChip(if (p.off) "Wolne" else "${p.name} ${p.hours}", cur?.kind == p.kind, Modifier.weight(1f)) { WorkSchedule.setCustom(monday, p) }
            }
            ChoiceChip("Własna", cur?.kind == WorkSchedule.CUSTOM, Modifier.weight(0.7f)) {
                WorkSchedule.setCustom(monday, WorkSchedule.Shift(WorkSchedule.CUSTOM, cur?.start ?: 7 * 60, cur?.end ?: 15 * 60))
            }
        }
        if (cur?.kind == WorkSchedule.CUSTOM) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                HourStepper("od", cur.start / 60, { h -> WorkSchedule.setCustom(monday, cur.copy(start = ((h + 24) % 24) * 60)) })
                HourStepper("do", cur.end / 60, { h -> WorkSchedule.setCustom(monday, cur.copy(end = ((h + 24) % 24) * 60)) })
            }
        }
    }
}

@Composable
private fun ChoiceChip(text: String, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Box(
        modifier
            .height(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (on) accent.copy(alpha = 0.18f) else CarColors.Card)
            .border(2.dp, if (on) accent else Color.Transparent, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = if (on) accent else CarColors.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun HourStepper(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 15.sp, color = CarColors.Muted)
        RoundIconButton(Icons.Rounded.Remove, "Mniej", 44.dp, { onChange(value - 1) })
        Text("%02d".format(value), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        RoundIconButton(Icons.Rounded.Add, "Więcej", 44.dp, { onChange(value + 1) })
    }
}

private fun LazyListScope.backupSection() {
    item { SectionLabel("Kopia zapasowa") }
    item {
        val ctx = LocalContext.current
        val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) Toaster.show(if (BackupManager.export(ctx, uri)) "Kopia zapisana" else "Nie udało się zapisać kopii")
        }
        SettingRow("Zapisz kopię ustawień", "Profil, wygląd, serwis, dziennik tras → plik (np. na pendrive)", leading = Icons.Rounded.Download, onClick = {
            val d = java.text.SimpleDateFormat("yyyy-MM-dd", PL).format(java.util.Date())
            exporter.launch("CarOS-kopia-$d.json")
        }) { Chevron() }
    }
    item {
        val ctx = LocalContext.current
        val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            if (BackupManager.import(ctx, uri)) {
                Toaster.show("Kopia wczytana – uruchamiam ponownie")
                val i = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
                if (i != null) {
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    ctx.startActivity(i)
                }
                Runtime.getRuntime().exit(0)
            } else {
                Toaster.show("To nie jest kopia CAR OS albo plik jest uszkodzony")
            }
        }
        SettingRow("Wczytaj kopię ustawień", "Przywraca wszystko z pliku – obecne ustawienia zostaną zastąpione", leading = Icons.Rounded.Upload, onClick = {
            importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"))
        }) { Chevron() }
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
    backupSection()
    item { SettingRow("Wersja", "CAR OS ${UpdateManager.currentVersion} · Android 10") {} }
}

private fun LazyListScope.connectPage() {
    item {
        Row(
            Modifier.clip(RoundedCornerShape(14.dp)).clickable { UiState.settingsPage.value = "conn" }.padding(end = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.ChevronLeft, null, tint = CarColors.Text2, modifier = Modifier.size(30.dp))
            Text("Połączenia", fontSize = 19.sp, color = CarColors.Text2)
        }
    }
    item {
        var gen by remember { mutableIntStateOf(0) }
        val sent by ConnectRepository.lastSent.collectAsState()
        val qr = remember(gen) { ParkingRepository.qr(ConnectRepository.payload(), 520)?.asImageBitmap() }
        CarCard(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(24.dp), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(300.dp).clip(RoundedCornerShape(20.dp)).background(Color.White).padding(14.dp)) {
                    if (qr != null) androidx.compose.foundation.Image(qr, "Kod QR CAR OS Connect", modifier = Modifier.fillMaxSize())
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("CAR OS Connect", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text("Aplikacja na telefon: gdzie zaparkowałeś, kompas i prowadzenie do auta.", fontSize = 17.sp, color = CarColors.Text2)
                    ConnectStep(1, "Nie masz aplikacji? Zeskanuj kod zwykłym aparatem i pobierz plik CarOS-Connect…apk")
                    ConnectStep(2, "W aplikacji dotknij „Skanuj kod z CAR OS” i zeskanuj ten kod")
                    ConnectStep(3, "Telefon musi być sparowany z radiem przez Bluetooth")
                    Text(
                        if (sent > 0) "Ostatnio wysłano do telefonu: ${formatTime(sent)}" else "Miejsce parkowania wyślę do telefonu po pierwszym postoju",
                        fontSize = 15.sp, color = CarColors.Muted,
                    )
                }
            }
        }
    }
    item {
        var ask by remember { mutableStateOf(false) }
        SettingRow(
            if (ask) "Na pewno? Telefony trzeba będzie połączyć od nowa" else "Nowy kod parowania",
            if (ask) "Dotknij ponownie, aby potwierdzić" else "Odłącza wszystkie telefony połączone starym kodem",
            leading = Icons.Rounded.Refresh,
            onClick = {
                if (ask) { ConnectRepository.resetKey(); UiState.settingsPage.value = "conn"; Toaster.show("Utworzono nowy kod – połącz telefon ponownie") }
                else ask = true
            },
        ) {}
    }
}

@Composable
private fun ConnectStep(n: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(CarColors.Raised), contentAlignment = Alignment.Center) {
            Text("$n", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = LocalAccent.current)
        }
        Text(text, fontSize = 17.sp, color = CarColors.Text2, modifier = Modifier.weight(1f).padding(top = 3.dp))
    }
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
