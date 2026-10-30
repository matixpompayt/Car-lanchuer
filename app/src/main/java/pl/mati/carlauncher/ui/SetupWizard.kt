package pl.mati.carlauncher.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.rounded.Restore
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.mati.carlauncher.data.BackupManager
import pl.mati.carlauncher.data.ObdManager
import pl.mati.carlauncher.data.Prefs
import pl.mati.carlauncher.data.Profile
import pl.mati.carlauncher.data.UpdateManager

private val STEPS = listOf("Powitanie", "Kierowca", "Samochód", "Adapter OBD2", "Ulubione miejsca", "Wygląd", "Gotowe")
private val BRANDS = listOf("Volkswagen", "Audi", "Škoda", "Seat", "Opel", "Ford", "BMW", "Inna")
private val FUELS = listOf("Diesel", "Benzyna", "LPG", "Hybryda")

@Composable
fun SetupWizard() {
    val startStep by UiState.setupStep.collectAsState()
    var step by remember { mutableIntStateOf(startStep) }
    var draft by remember { mutableStateOf(Prefs.profile.value) }
    val ctx = LocalContext.current

    fun valid() = when (step) {
        1 -> draft.name.isNotBlank()
        2 -> draft.model.isNotBlank()
        else -> true
    }

    fun finish() {
        Prefs.setProfile(draft.copy(setupDone = true))
        UiState.setupStep.value = 0
        UiState.screen.value = Screen.HOME
        UiState.phase.value = Phase.MAIN
        Toaster.show(if (draft.name.isNotBlank()) "Witaj, ${draft.name}! System gotowy." else "System gotowy.")
        if (Prefs.autoUpdate.value) UpdateManager.check(ctx, silent = true)
        BackupManager.autoBackup(ctx, force = true)
    }

    Row(Modifier.fillMaxSize().background(Color(0xFF07090C))) {
        // lewa kolumna z krokami
        Column(
            Modifier.width(340.dp).fillMaxHeight().background(Color(0xFF0B0E12)).padding(horizontal = 32.dp, vertical = 40.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 28.dp)) {
                CarOsLogo(44.dp)
                Text("CAR OS", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 5.sp)
            }
            STEPS.forEachIndexed { i, name ->
                StepRow(i, name, step)
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(CarColors.Border))
        Column(Modifier.weight(1f).fillMaxHeight().padding(start = 64.dp, end = 64.dp, top = 48.dp, bottom = 36.dp)) {
            AnimatedContent(
                targetState = step,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                label = "step",
            ) { s ->
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    when (s) {
                        0 -> WelcomeStep()
                        1 -> NameStep(draft) { draft = it }
                        2 -> CarStep(draft) { draft = it }
                        3 -> ObdStep()
                        4 -> PlacesStep(draft) { draft = it }
                        5 -> LookStep()
                        else -> DoneStep(draft)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (step > 0) SecondaryButton("Wstecz", { step-- })
                Spacer(Modifier.weight(1f))
                val obdOk = ObdManager.status.collectAsState().value == ObdManager.Status.CONNECTED
                val label = when {
                    step == 0 -> "Zaczynamy"
                    step == STEPS.lastIndex -> "Rozpocznij jazdę"
                    step == 3 && !obdOk -> "Pomiń"
                    step == 4 && draft.home.isBlank() && draft.work.isBlank() -> "Pomiń"
                    else -> "Dalej"
                }
                PrimaryButton(label, {
                    if (step < STEPS.lastIndex) step++ else finish()
                }, enabled = valid())
            }
        }
    }
}

@Composable
private fun StepRow(i: Int, name: String, current: Int) {
    val accent = LocalAccent.current
    val done = i < current
    val on = i == current
    Row(Modifier.height(52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(if (done) accent else Color.Transparent)
                .border(2.dp, if (done || on) accent else CarColors.Switch, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (done) Icon(Icons.Rounded.Check, null, tint = CarColors.Bg, modifier = Modifier.size(20.dp))
            else Text("${i + 1}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (on) accent else CarColors.Muted)
        }
        Text(name, fontSize = 19.sp, color = if (on) CarColors.Text else CarColors.Muted)
    }
}

@Composable
private fun Title(text: String) = Text(text, fontSize = 44.sp, fontWeight = FontWeight.Bold, lineHeight = 48.sp)

@Composable
private fun Lead(text: String) = Text(text, fontSize = 21.sp, color = CarColors.Text2, lineHeight = 28.sp, modifier = Modifier.widthIn(max = 700.dp))

@Composable
private fun RestoreCard() {
    val ctx = LocalContext.current
    var found by remember { mutableStateOf<BackupManager.Found?>(null) }
    var attempt by remember { mutableStateOf(0) }
    var asked by remember { mutableStateOf(false) }
    val needPerm = android.os.Build.VERSION.SDK_INT <= 32
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { attempt++ }
    LaunchedEffect(attempt) {
        val granted = !needPerm || ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            if (!asked) {
                asked = true
                perm.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            return@LaunchedEffect
        }
        found = withContext(Dispatchers.IO) { BackupManager.findAutoBackup(ctx) }
    }
    val f = found ?: return
    Row(
        Modifier.widthIn(max = 860.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(LocalAccent.current.copy(alpha = 0.14f))
            .border(1.dp, LocalAccent.current.copy(alpha = 0.5f), RoundedCornerShape(20.dp)).padding(horizontal = 22.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        IconBadge(Icons.Rounded.Restore, 52.dp)
        Column(Modifier.weight(1f)) {
            Text("Znaleziono kopię ustawień", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(
                listOf(f.name, f.car, if (f.created > 0) "z " + java.text.SimpleDateFormat("d.MM.yyyy HH:mm", PL).format(java.util.Date(f.created)) else "")
                    .filter { it.isNotBlank() }.joinToString(" · "),
                fontSize = 16.sp, color = CarColors.Text2,
            )
        }
        PrimaryButton("Przywróć", {
            if (BackupManager.import(ctx, f.uri)) {
                Toaster.show("Przywrócono – uruchamiam ponownie")
                val i = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
                if (i != null) {
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    ctx.startActivity(i)
                }
                Runtime.getRuntime().exit(0)
            } else Toaster.show("Nie udało się wczytać kopii")
        })
    }
}

@Composable
private fun WelcomeStep() {
    if (!Prefs.profile.value.setupDone) {
        RestoreCard()
        ConnectRestoreCard()
    }
    Title("Witaj w CAR OS")
    Lead("Twój samochód, Twój system. Skonfigurujemy wszystko w minutę – każdą rzecz możesz później zmienić w Ustawieniach.")
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 10.dp)) {
        Feature(Icons.Rounded.Navigation, "Nawigacja", "Manewry z Map Google na ekranie głównym", Modifier.weight(1f))
        Feature(Icons.Rounded.MusicNote, "Muzyka", "Spotify, radio i inne aplikacje w jednym miejscu", Modifier.weight(1f))
        Feature(Icons.Rounded.Speed, "Dane z auta", "Obroty, temperatura i doładowanie z OBD2", Modifier.weight(1f))
        Feature(Icons.Rounded.SystemUpdate, "Aktualizacje", "Nowe funkcje online, bez kabla", Modifier.weight(1f))
    }
}

@Composable
private fun Feature(icon: ImageVector, title: String, text: String, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(20.dp)).background(CarColors.Card).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        IconBadge(icon)
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text(text, fontSize = 16.sp, color = CarColors.Muted)
    }
}

@Composable
private fun NameStep(p: Profile, onChange: (Profile) -> Unit) {
    Title("Jak masz na imię?")
    Lead("Tak będziemy się do Ciebie zwracać przy uruchomieniu.")
    CarTextField(p.name, { onChange(p.copy(name = it.take(20))) }, "Twoje imię", Modifier.widthIn(max = 560.dp).fillMaxWidth(), imeAction = ImeAction.Done)
    Row(
        Modifier.widthIn(max = 560.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(CarColors.Card).padding(horizontal = 26.dp, vertical = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        IconBadge(Icons.Rounded.WbSunny)
        Text(greeting(p.name.trim()), fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CarStep(p: Profile, onChange: (Profile) -> Unit) {
    Title("Jaki masz samochód?")
    SectionLabel("Marka")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BRANDS.forEach { b -> CarChip(b, p.brand == b, { onChange(p.copy(brand = b)) }) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.weight(1.4f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Caption("Model", size = 15.sp)
            CarTextField(p.model, { onChange(p.copy(model = it.take(24))) }, "np. Touran", Modifier.fillMaxWidth())
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Caption("Silnik", size = 15.sp)
            CarTextField(p.engine, { onChange(p.copy(engine = it.take(16))) }, "np. 2.0 TDI", Modifier.fillMaxWidth())
        }
        Column(Modifier.weight(0.8f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Caption("Rocznik", size = 15.sp)
            CarTextField(
                p.year, { v -> onChange(p.copy(year = v.filter { it.isDigit() }.take(4))) }, "2005", Modifier.fillMaxWidth(),
                keyboardType = KeyboardType.Number, imeAction = ImeAction.Done,
            )
        }
    }
    SectionLabel("Paliwo")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FUELS.forEach { f -> CarChip(f, p.fuel == f, { onChange(p.copy(fuel = f)) }) }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Rounded.Info, null, tint = CarColors.Muted, modifier = Modifier.size(20.dp))
        Text("Obrotomierz dopasuje się do silnika: diesel do 5000, benzyna do 7000 obr/min.", fontSize = 17.sp, color = CarColors.Muted)
    }
}

private data class Bt(val name: String, val address: String)

@SuppressLint("MissingPermission")
private fun bonded(): List<Bt> = try {
    @Suppress("DEPRECATION")
    BluetoothAdapter.getDefaultAdapter()?.bondedDevices.orEmpty()
        .map { Bt(it.name ?: it.address, it.address) }
        .sortedByDescending { it.name.contains("OBD", true) || it.name.contains("ELM", true) || it.name.contains("V-LINK", true) }
} catch (_: Exception) {
    emptyList()
}

@Composable
private fun ObdStep() {
    val ctx = LocalContext.current
    val status by ObdManager.status.collectAsState()
    val msg by ObdManager.message.collectAsState()
    val mac by Prefs.obdMac.collectAsState()
    var devices by remember { mutableStateOf(bonded()) }
    LaunchedEffect(Unit) { devices = bonded() }

    Title("Podłącz adapter OBD2")
    Lead("Włóż ELM327 do gniazda OBD pod kierownicą, włącz zapłon i sparuj adapter w Bluetooth radia (PIN zwykle 1234 lub 0000). Bez adaptera prędkość weźmiemy z GPS.")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SecondaryButton("Odśwież listę", { devices = bonded() }, height = 56.dp, icon = Icons.Rounded.Refresh)
        SecondaryButton("Sparuj w Bluetooth", { Launch.start(ctx, Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }, height = 56.dp, icon = Icons.Rounded.Bluetooth)
    }
    if (devices.isEmpty()) {
        Text("Brak sparowanych urządzeń.", fontSize = 18.sp, color = CarColors.Muted)
    }
    devices.forEach { d ->
        val selected = d.address == mac
        SettingRow(
            d.name, d.address, Modifier.widthIn(max = 720.dp), leading = Icons.Rounded.DirectionsCar,
            onClick = {
                Prefs.setObdMac(d.address)
                ObdManager.start(d.address)
            },
        ) {
            if (selected) {
                when (status) {
                    ObdManager.Status.CONNECTED -> Icon(Icons.Rounded.CheckCircle, null, tint = CarColors.Green, modifier = Modifier.size(30.dp))
                    ObdManager.Status.CONNECTING -> Spinner()
                    else -> Text(msg ?: "", fontSize = 15.sp, color = CarColors.Red, maxLines = 2, modifier = Modifier.widthIn(max = 260.dp))
                }
            }
        }
    }
    if (status == ObdManager.Status.CONNECTED) {
        Text("Połączono – ECU odpowiada. Dane z auta są już na ekranie Pojazd.", fontSize = 18.sp, color = CarColors.Green)
    }
}

@Composable
private fun PlacesStep(p: Profile, onChange: (Profile) -> Unit) {
    Title("Ulubione miejsca")
    Lead("Pojawią się na ekranie nawigacji – jedno dotknięcie i Mapy Google prowadzą. Możesz pominąć.")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.widthIn(max = 720.dp)) {
        IconBadge(Icons.Rounded.Home, 60.dp)
        CarTextField(p.home, { onChange(p.copy(home = it.take(80))) }, "Dom – adres lub miejscowość", Modifier.weight(1f))
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.widthIn(max = 720.dp)) {
        IconBadge(Icons.Rounded.Work, 60.dp)
        CarTextField(p.work, { onChange(p.copy(work = it.take(80))) }, "Praca – adres lub miejscowość", Modifier.weight(1f), imeAction = ImeAction.Done)
    }
}

@Composable
private fun LookStep() {
    val accent by Prefs.accent.collectAsState()
    val wall by Prefs.wallpaper.collectAsState()
    val autoNight by Prefs.autoNight.collectAsState()
    Title("Wybierz wygląd")
    SectionLabel("Kolor akcentu")
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        CarColors.AccentOptions.forEach { c -> Swatch(Color(c), c == accent) { Prefs.setAccent(c) } }
    }
    SectionLabel("Tapeta")
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.widthIn(max = 820.dp)) {
        Wallpapers.forEachIndexed { i, w -> WallpaperCard(w.first, w.second, wall == i, { Prefs.setWallpaper(i) }, Modifier.weight(1f)) }
    }
    SettingRow("Tryb nocny automatycznie", "Ciemniejszy ekran od 19:00 do 6:00", Modifier.widthIn(max = 820.dp), onClick = { Prefs.setAutoNight(!autoNight) }) {
        CarSwitch(autoNight)
    }
}

@Composable
private fun DoneStep(p: Profile) {
    val obd by ObdManager.status.collectAsState()
    Title(if (p.name.isNotBlank()) "Wszystko gotowe, ${p.name.trim()}!" else "Wszystko gotowe!")
    Lead("Oto Twoja konfiguracja. Szerokiej drogi.")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.widthIn(max = 720.dp)) {
        SummaryRow(Icons.Rounded.Person, "Kierowca", p.name.ifBlank { "—" })
        SummaryRow(Icons.Rounded.DirectionsCar, "Samochód", listOf(p.carName, p.year, p.fuel).filter { it.isNotBlank() }.joinToString(" · "))
        SummaryRow(Icons.Rounded.Speed, "OBD2", if (obd == ObdManager.Status.CONNECTED) "ELM327 połączony" else if (Prefs.obdMac.value != null) "Adapter wybrany – połączy się po włączeniu zapłonu" else "Pominięto – prędkość z GPS")
        SummaryRow(Icons.Rounded.Home, "Dom", p.home.ifBlank { "—" })
        SummaryRow(Icons.Rounded.Work, "Praca", p.work.ifBlank { "—" })
    }
}

@Composable
private fun SummaryRow(icon: ImageVector, label: String, value: String) {
    val accent = LocalAccent.current
    Row(
        Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(18.dp)).background(CarColors.Card).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, null, tint = accent, modifier = Modifier.size(26.dp))
        Column {
            Text(label, fontSize = 14.sp, color = CarColors.Muted)
            Text(value, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}
