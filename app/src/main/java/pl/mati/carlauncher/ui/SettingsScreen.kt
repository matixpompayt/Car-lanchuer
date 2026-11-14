package pl.mati.carlauncher.ui

import androidx.compose.material.icons.rounded.DarkMode
import pl.mati.carlauncher.data.HeadlightDetector
import pl.mati.carlauncher.data.ThemeManager
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
import androidx.compose.material.icons.rounded.AutoAwesome
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
        val anim by Prefs.animations.collectAsState()
        androidx.compose.animation.AnimatedContent(
            targetState = page, transitionSpec = { pageTransition(anim) }, label = "page",
            modifier = Modifier.weight(1f).fillMaxHeight(),
        ) { page ->
        androidx.compose.runtime.CompositionLocalProvider(LocalStagger provides remember { StaggerCounter() }) {
        LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (page) {
                "look" -> lookPage()
                "disp" -> displayPage()
                "sound" -> soundPage()
                "conn" -> connPage()
                "car" -> { carPage(); careSection(); speedSection(); watchSection(); alertsSection() }
                "auto" -> autostartPage()
                "update" -> updatePage()
                "connect" -> connectPage()
                else -> systemPage()
            }
            item { Box(Modifier.height(16.dp)) }
        }
        }
        }
    }
    AppPickerOverlay()
    HeadlightCalibrationOverlay()
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
        Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp)).background(CarColors.Bg.copy(alpha = 0.96f))
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
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).carSurface(RoundedCornerShape(16.dp), CarColors.Card)
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
    item { SectionLabel("Motyw systemu") }
    item {
        val t by Prefs.uiTheme.collectAsState()
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SysThemes.ALL.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    row.forEach { th -> ThemeCard(th, t == th.id, Modifier.weight(1f)) { Prefs.setUiTheme(th.id) } }
                    repeat(3 - row.size) { Box(Modifier.weight(1f)) }
                }
            }
        }
    }
    item {
        val g by Prefs.glassUi.collectAsState()
        SettingRow(
            "Interfejs Glass iOS 26 (wczesny dostęp)",
            "Szklane karty z rozmytym tłem, kropla w menu i przełączniki jak w iPhonie – działa z każdym motywem",
            leading = Icons.Rounded.AutoAwesome,
            onClick = { Prefs.setGlassUi(!g) },
        ) { CarSwitch(g) }
    }
    item {
        val g by Prefs.glassUi.collectAsState()
        val a by Prefs.glassAnim.collectAsState()
        if (g) SettingRow(
            "Animacja kropli w menu",
            if (a) "Kropla przelewa się między ikonami i powiększa je" else "Wyłączona – kropla od razu przeskakuje, wygląd Glass zostaje",
            onClick = { Prefs.setGlassAnim(!a) },
        ) { CarSwitch(a) }
    }
    item { SectionLabel("Dzień / noc") }
    item {
        val mode by Prefs.themeMode.collectAsState()
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ChoiceChip("Światła auta", mode == "lights", Modifier.weight(1f)) { Prefs.setThemeMode("lights") }
            ChoiceChip("Wschód / zachód", mode == "sun", Modifier.weight(1f)) { Prefs.setThemeMode("sun") }
            ChoiceChip("Zawsze jasny", mode == "light", Modifier.weight(1f)) { Prefs.setThemeMode("light") }
            ChoiceChip("Zawsze ciemny", mode == "dark", Modifier.weight(1f)) { Prefs.setThemeMode("dark") }
        }
    }
    item {
        val mode by Prefs.themeMode.collectAsState()
        val light by ThemeManager.light.collectAsState()
        val on by HeadlightDetector.on.collectAsState()
        val learned by HeadlightDetector.learned.collectAsState()
        Text(
            remember(mode, light, on, learned) { ThemeManager.describe() } +
                if (mode == "lights") (learned?.let { "\nSygnał: ${it.describe()}" } ?: "\nRozpoznaj światła poniżej – wtedy tryb zmieni się razem ze światłami") else "",
            fontSize = 16.sp, color = CarColors.Muted,
        )
    }
    item {
        val learned by HeadlightDetector.learned.collectAsState()
        SettingRow(
            "Rozpoznaj światła auta",
            if (learned == null) "Włączysz i wyłączysz światła – CAR OS nauczy się sygnału z radia" else "Rozpoznane: ${learned!!.describe()} – dotknij, aby powtórzyć",
            leading = Icons.Rounded.WbSunny,
            onClick = { Calibration.open.value = true },
        ) { Chevron() }
    }
    item {
        val variant by Prefs.lightVariant.collectAsState()
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Jasny wygląd", fontSize = 17.sp, color = CarColors.Text2)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Palettes.LIGHT.forEach { p -> PalettePreview(p, p.id == variant, Modifier.weight(1f)) { Prefs.setLightVariant(p.id) } }
            }
        }
    }
    item { SectionLabel("Mapa") }
    item {
        val engine by Prefs.mapEngine.collectAsState()
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ChoiceChip("Mapa 3D (OpenFreeMap)", engine == "vector", Modifier.weight(1f)) { Prefs.setMapEngine("vector") }
            ChoiceChip("Klasyczna (OSM)", engine == "osm", Modifier.weight(1f)) { Prefs.setMapEngine("osm") }
        }
    }
    item { CarIconPicker() }
    item {
        val v by Prefs.map3d.collectAsState()
        SettingRow("Budynki 3D", "Pochylony widok z budynkami (dane OpenStreetMap)", onClick = { Prefs.setMap3d(!v) }) { CarSwitch(v) }
    }
    item {
        val v by Prefs.mapHeadingUp.collectAsState()
        SettingRow("Mapa obraca się z kierunkiem jazdy", "Wyłączone = północ zawsze u góry", onClick = { Prefs.setMapHeadingUp(!v) }) { CarSwitch(v) }
    }
    item { OfflineMapsCard() }
    item { SectionLabel("Nawigacja") }
    item {
        val e by Prefs.navEngine.collectAsState()
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ChoiceChip("Prowadzi CAR OS (głos po polsku)", e == "caros", Modifier.weight(1f)) { Prefs.setNavEngine("caros") }
            ChoiceChip("Mapy Google w tle", e == "google", Modifier.weight(1f)) { Prefs.setNavEngine("google") }
        }
    }
    item {
        val ctx = LocalContext.current
        val v by Prefs.navVoice.collectAsState()
        val problem by pl.mati.carlauncher.data.NavVoice.problem.collectAsState()
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingRow("Głos nawigacji", problem ?: "Komunikaty manewrów po polsku – muzyka przycisza się na czas komunikatu", onClick = { Prefs.setNavVoice(!v) }) { CarSwitch(v) }
            SecondaryButton("Test głosu", {
                pl.mati.carlauncher.data.NavVoice.init(ctx)
                pl.mati.carlauncher.data.NavVoice.say("Za 300 metrów skręć w prawo w Jana Pawła II", force = true)
            }, height = 52.dp)
        }
    }
    trafficSection()
    item {
        val v by Prefs.mapSigns.collectAsState()
        SettingRow("Znaki na mapie", "Sygnalizacja świetlna, STOP, ustąp pierwszeństwa, przejścia, przejazdy kolejowe, fotoradary (OpenStreetMap)", onClick = { Prefs.setMapSigns(!v) }) { CarSwitch(v) }
    }
    item {
        val v by Prefs.mapSnap.collectAsState()
        SettingRow("Auto zawsze na drodze", "Wygładza pozycję GPS: przyciąga auto do najbliższej drogi i nie pływa na postoju", onClick = { Prefs.setMapSnap(!v) }) { CarSwitch(v) }
    }
    item {
        val v by Prefs.animations.collectAsState()
        SettingRow("Animacje", "Płynne przejścia, wejście kart, efekt wciśnięcia – wyłącz na wolnym radiu", onClick = { Prefs.setAnimations(!v) }) { CarSwitch(v) }
    }
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
        val v by Prefs.blockStatusBar.collectAsState()
        SettingRow(
            "Blokuj systemowy pasek",
            "Pasek i zasłona powiadomień Androida nie wysuwają się nad CAR OS (powiadomienia są w centrum sterowania CAR OS)",
            onClick = { Prefs.setBlockStatusBar(!v) },
        ) { CarSwitch(v) }
    }
    if (pl.mati.carlauncher.data.ShadeGuard.needsService) item {
        val v by Prefs.blockStatusBar.collectAsState()
        val on by pl.mati.carlauncher.data.ShadeGuard.connected.collectAsState()
        val ctx = androidx.compose.ui.platform.LocalContext.current
        androidx.compose.animation.AnimatedVisibility(v) {
            Column {
                SettingRow(
                    "Usługa dostępności CAR OS",
                    if (on) "Włączona – zasłona powiadomień zamyka się od razu"
                    else "Android ${android.os.Build.VERSION.RELEASE} pozwala zamknąć zasłonę tylko przez nią. " +
                        "Dotknij → Ułatwienia dostępu → „CAR OS – pasek systemowy” → włącz",
                    onClick = { pl.mati.carlauncher.data.ShadeGuard.openAccessibilitySettings(ctx) },
                ) { CarSwitch(on) }
                if (!on && android.os.Build.VERSION.SDK_INT >= 33) SettingRow(
                    "Przełącznik usługi jest wyszarzony?",
                    "Dotknij → w Informacjach o aplikacji ⋮ (prawy górny róg) → „Zezwól na ustawienia z ograniczeniami”, potem włącz usługę",
                    onClick = { pl.mati.carlauncher.data.ShadeGuard.openAppInfo(ctx) },
                )
            }
        }
    }
    item {
        val light by ThemeManager.light.collectAsState()
        SettingRow(
            "Tryb nocny",
            "Włączony = ciemny wygląd i przygaszony ekran, wyłączony = jasny. Automat: Wygląd → Dzień / noc",
            onClick = { ThemeManager.toggleNight() },
        ) { CarSwitch(!light) }
    }
    item {
        val ctx = LocalContext.current
        val bn by Prefs.briNight.collectAsState()
        FillSlider(bn / 100f, {
            val p = (it * 100).toInt()
            Prefs.setBriNight(p)
            if (!ThemeManager.light.value) SystemControls.setBrightness(ctx as? Activity, ctx, p, remember = false)
        }, Icons.Rounded.DarkMode, "Jasność ekranu w nocy", "$bn%", Modifier.fillMaxWidth())
    }
    item {
        val v by Prefs.bootAnim.collectAsState()
        SettingRow("Animacja uruchamiania", "Logo CAR OS przy starcie radia", onClick = { Prefs.setBootAnim(!v) }) { CarSwitch(v) }
    }
    item { SectionLabel("Kafle ekranu głównego (maks. 8) – albo przytrzymaj kafel na ekranie głównym") }
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
                        val next = if (enabled) on - key else if (on.size >= 8) { Toaster.show("Maksymalnie 8 kafli"); on } else on + key
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

private fun LazyListScope.trafficSection() {
    item { SectionLabel("Korki (TomTom)") }
    item {
        val key by Prefs.tomtomKey.collectAsState()
        val err by pl.mati.carlauncher.data.TrafficRepository.error.collectAsState()
        val used by pl.mati.carlauncher.data.TrafficRepository.usedToday.collectAsState()
        val paired by pl.mati.carlauncher.data.ConnectRepository.paired.collectAsState()
        var edit by remember { mutableStateOf(false) }
        var text by remember { mutableStateOf("") }
        var result by remember { mutableStateOf<String?>(null) }
        CarCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 22.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Klucz TomTom", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (key.isBlank()) "Korki na mapie i czas z korkami wymagają darmowego klucza: developer.tomtom.com → załóż konto (bez karty) → " +
                        "„API Keys” → skopiuj klucz. Najwygodniej wkleić go w telefonie: CAR OS Connect → Ustawienia → Korki." +
                        (if (!paired) " (Telefon nie jest połączony – można też wpisać tutaj.)" else "")
                    else "Klucz: ${key.take(4)}…${key.takeLast(4)} · dziś zapytań: $used / 2500 (kafelki mapy liczone osobno)",
                    fontSize = 15.sp, color = CarColors.Muted,
                )
                (result ?: err)?.let { Text(it, fontSize = 15.sp, color = if (it.startsWith("Klucz działa")) CarColors.Green else CarColors.Orange) }
                if (edit) {
                    CarTextField(text, { text = it.filter { c -> c.isLetterOrDigit() }.take(64) }, "wklej lub wpisz klucz", Modifier.fillMaxWidth(),
                        imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                        onDone = { Prefs.setTomtomKey(text); edit = false; result = null; pl.mati.carlauncher.data.TrafficRepository.testKey { result = it } })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (edit) {
                        PrimaryButton("Zapisz", { Prefs.setTomtomKey(text); edit = false; result = null; pl.mati.carlauncher.data.TrafficRepository.testKey { result = it } })
                        SecondaryButton("Anuluj", { edit = false }, height = 56.dp)
                    } else {
                        SecondaryButton(if (key.isBlank()) "Wpisz klucz" else "Zmień klucz", { text = key; edit = true }, height = 56.dp)
                        if (key.isNotBlank()) {
                            SecondaryButton("Sprawdź", { result = "Sprawdzam…"; pl.mati.carlauncher.data.TrafficRepository.testKey { result = it } }, height = 56.dp)
                            SecondaryButton("Usuń", { Prefs.setTomtomKey(""); result = null }, height = 56.dp)
                        }
                    }
                }
            }
        }
    }
    item {
        val v by Prefs.trafficMap.collectAsState()
        SettingRow("Korki na mapie", "Odcinki wolniejsze niż zwykle: żółty, pomarańczowy, czerwony (odświeżane co 5 min, dane © TomTom)", onClick = { Prefs.setTrafficMap(!v) }) { CarSwitch(v) }
    }
    item {
        val v by Prefs.trafficEta.collectAsState()
        SettingRow("Czas przyjazdu z korkami", "Trasa sprawdzana w TomTom co 3 min: czas, opóźnienie i korki zaznaczone na trasie, także przy wyborze wariantu", onClick = { Prefs.setTrafficEta(!v) }) { CarSwitch(v) }
    }
    item {
        val v by Prefs.trafficVoice.collectAsState()
        SettingRow("Komunikat o korku", "Głosem: „Korek za 3 kilometry, około 6 minut opóźnienia” (tylko większe, raz na korek)", onClick = { Prefs.setTrafficVoice(!v) }) { CarSwitch(v) }
    }
}

private fun LazyListScope.displayPage() {
    item { SectionLabel("Wirtualny licznik") }
    item {
        val ctx = LocalContext.current
        val f by Prefs.clusterStretch.collectAsState()
        val displays = remember {
            try {
                ctx.getSystemService(android.hardware.display.DisplayManager::class.java)?.displays?.map { d ->
                    val m = android.util.DisplayMetrics()
                    @Suppress("DEPRECATION") d.getRealMetrics(m)
                    "${d.name} ${m.widthPixels}×${m.heightPixels}"
                }.orEmpty()
            } catch (_: Exception) { emptyList() }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingRow(
                "Dopasowanie obrazu do licznika",
                "Mapa na pełnym ekranie (przycisk w rogu mapy) ściśnięta tak, żeby na liczniku wyglądała normalnie – na radiu będzie węższa",
            ) {
                RoundIconButton(Icons.Rounded.Remove, "Mniej", 44.dp, { Prefs.setClusterStretch(f - 0.05f) })
                Text(if (f <= 1.01f) "wył." else String.format(PL, "%.2f×", f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                RoundIconButton(Icons.Rounded.Add, "Więcej", 44.dp, { Prefs.setClusterStretch(f + 0.05f) })
            }
            val ui by Prefs.clusterUiScale.collectAsState()
            SettingRow("Wielkość elementów na pełnym ekranie", "Manewr, przyjazd i przyciski na mapie pełnoekranowej (licznik)") {
                RoundIconButton(Icons.Rounded.Remove, "Mniejsze", 44.dp, { Prefs.setClusterUiScale(ui - 0.1f) })
                Text(String.format(PL, "%.1f×", ui), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                RoundIconButton(Icons.Rounded.Add, "Większe", 44.dp, { Prefs.setClusterUiScale(ui + 0.1f) })
            }
            SecondaryButton("Ustaw elementy na liczniku (przeciągnij palcem)", {
                ClusterLayout.editing.value = true
                UiState.mapFull.value = true
            }, height = 52.dp)
            SecondaryButton("Ustaw na wzorze (patrząc na licznik)", {
                if (f <= 1.01f) Prefs.setClusterStretch(1.5f)
                UiState.clusterTest.value = true
                UiState.mapFull.value = true
            }, height = 52.dp)
            Text(
                "Ekrany widziane przez radio: " + displays.joinToString(" · ").ifBlank { "?" } +
                    if (displays.size > 1) " – licznik może być osobnym ekranem, daj znać" else "",
                fontSize = 14.sp, color = CarColors.Muted,
            )
        }
    }

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
    item { SectionLabel("Licznik / drugi ekran") }
    item { DisplaysCard() }
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
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).carSurface(RoundedCornerShape(18.dp), CarColors.Card).padding(horizontal = 20.dp, vertical = 14.dp),
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
    item {
        val v by Prefs.callScreen.collectAsState()
        SettingRow(
            "Ekran rozmowy CAR OS",
            "Przy połączeniu: imię i zdjęcie z kontaktów, odbierz / odrzuć / wycisz. Małe okno MainUI zostaje na wierzchu – tak działa radio",
            onClick = { Prefs.setCallScreen(!v) },
        ) { CarSwitch(v) }
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
    item { SectionLabel("Dane z radia (CAN) – bez adaptera") }
    item {
        val maps by pl.mati.carlauncher.data.CanRepository.mappings.collectAsState()
        val vals by pl.mati.carlauncher.data.CanRepository.values.collectAsState()
        SettingRow(
            "Skaner danych z radia",
            if (maps.isEmpty()) "Odczyt obrotów, temperatur, paliwa itd. z dekodera CAN radia – znajdź i przypisz wartości"
            else maps.keys.joinToString(" · ") { f -> f.label + (vals[f]?.let { ": " + pl.mati.carlauncher.data.CanRepository.fmt(Math.round(it * 10) / 10.0) } ?: "") },
            onClick = { CanScanner.open.value = true },
        ) { Chevron() }
    }
    item {
        val st by pl.mati.carlauncher.data.TsCarBridge.status.collectAsState()
        SettingRow("Dane z MainApp (TopWay)", "$st") {}
    }
    item { SectionLabel("Dane na żywo") }
    item {
        val d by pl.mati.carlauncher.data.Vehicle.data.collectAsState()
        val gps by LocationRepository.gpsSpeed.collectAsState()
        val cells = listOf(
            "Prędkość" to ((d.speed ?: gps)?.let { "$it km/h" } ?: "--"),
            "Obroty" to (d.rpm?.toString() ?: "--"),
            "Płyn" to (d.coolant?.let { "$it °C" } ?: "--"),
            "MAP" to (d.mapKpa?.let { "$it kPa" } ?: "--"),
            "Obciążenie" to (d.load?.let { "$it %" } ?: "--"),
            "Napięcie" to (d.voltage?.let { "${it.pl1()} V" } ?: "--"),
        ) + listOfNotNull(
            d.outside?.let { "Na zewnątrz" to "$it °C" },
            d.fuelPct?.let { "Paliwo" to "$it %" },
            d.fuelL?.let { "Paliwo" to "$it l" },
            d.rangeKm?.let { "Zasięg" to "$it km" },
            d.odoKm?.let { "Przebieg" to "$it km" },
        )
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            cells.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { (k, v) ->
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).carSurface(RoundedCornerShape(16.dp), CarColors.Card).padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Caption(k)
                            Text(v, fontFamily = Condensed, fontSize = 30.sp)
                        }
                    }
                }
            }
        }
    }
}

private fun LazyListScope.careSection() {
    item { SectionLabel("Turbo i akumulator") }
    item {
        val v by Prefs.turboTimer.collectAsState()
        SettingRow("Timer schłodzenia turbo", "Po mocnej jeździe (autostrada, wysokie obroty) pokazuje, ile odczekać na wolnych obrotach przed zgaszeniem", onClick = { Prefs.setTurboTimer(!v) }) { CarSwitch(v) }
    }
    item {
        val b by pl.mati.carlauncher.data.CarCare.battery.collectAsState()
        val v by pl.mati.carlauncher.data.CarCare.voltage.collectAsState()
        val hist by pl.mati.carlauncher.data.CarCare.batteryHistory.collectAsState()
        val now = v?.let { "Teraz " + String.format(java.util.Locale("pl", "PL"), "%.1f V", it) } ?: "Brak odczytu napięcia – potrzebny adapter OBD albo napięcie przypisane w „Dane z radia (CAN)”"
        val last = b?.let {
            fun f(x: Float?) = x?.let { y -> String.format(java.util.Locale("pl", "PL"), "%.1f", y) } ?: "–"
            "\n" + it.msg + "\nOstatni pomiar " + java.text.SimpleDateFormat("d.MM HH:mm", java.util.Locale.ROOT).format(java.util.Date(it.time)) +
                ": spoczynkowe ${f(it.rest)} V · rozruch ${f(it.crank)} V · ładowanie ${f(it.charge)} V"
        } ?: "\nPomiar robi się sam przy każdym uruchomieniu auta"
        val trend = hist.mapNotNull { it.rest }.take(7).reversed().takeIf { it.size >= 2 }
            ?.joinToString(" → ") { String.format(java.util.Locale("pl", "PL"), "%.2f", it) }?.let { "\nSpoczynkowe (ostatnie): $it V" } ?: ""
        SettingRow("Akumulator", now + last + trend) {
            b?.let {
                val col = when (it.level) { 2 -> androidx.compose.ui.graphics.Color(0xFFE0262B); 1 -> androidx.compose.ui.graphics.Color(0xFFF5A623); else -> androidx.compose.ui.graphics.Color(0xFF34C759) }
                Box(Modifier.size(18.dp).clip(androidx.compose.foundation.shape.CircleShape).background(col))
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
        val inf by pl.mati.carlauncher.data.OfflineSpeed.info.collectAsState()
        val prog by pl.mati.carlauncher.data.OfflineSpeed.progress.collectAsState()
        val err by pl.mati.carlauncher.data.OfflineSpeed.error.collectAsState()
        val sub = when {
            prog != null -> "Pobieranie… ${(prog!! * 100).toInt()}%"
            inf != null -> inf!!.regions + "\nDane z " + java.text.SimpleDateFormat("d.MM.yyyy", java.util.Locale.ROOT).format(java.util.Date(inf!!.builtAt)) +
                " · " + String.format(java.util.Locale.ROOT, "%.1f MB", inf!!.bytes / 1_000_000.0) + " · aktualizacja sama co tydzień"
            else -> "Nie pobrano – limity działają bez internetu po pobraniu (OpenStreetMap)"
        } + (err?.let { "\n$it" } ?: "")
        SettingRow("Ograniczenia offline", sub) {
            SecondaryButton(if (inf == null) "Pobierz" else "Sprawdź", { pl.mati.carlauncher.data.OfflineSpeed.updateNow() }, height = 52.dp)
        }
    }
    item {
        val st by pl.mati.carlauncher.data.SpeedLimitRepository.status.collectAsState()
        val lim by pl.mati.carlauncher.data.SpeedLimitRepository.limit.collectAsState()
        val road by pl.mati.carlauncher.data.SpeedLimitRepository.roadName.collectAsState()
        SettingRow("Stan danych", st + "\nTeraz: " + (road ?: "nie dopasowano drogi") + " · " + (lim?.let { "$it km/h" } ?: "brak limitu")) {}
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

private fun LazyListScope.watchSection() {
    item { SectionLabel("Fotoradary i odcinkowe pomiary (OpenStreetMap)") }
    item {
        val v by Prefs.cameraAlerts.collectAsState()
        SettingRow("Ostrzegaj o fotoradarach", "Baner z odliczaniem metrów, gdy fotoradar jest przed autem (do 1 km)", onClick = { Prefs.setCameraAlerts(!v) }) { CarSwitch(v) }
    }
    item {
        val v by Prefs.sectionAlerts.collectAsState()
        val inf by pl.mati.carlauncher.data.RoadWatch.info.collectAsState()
        SettingRow(
            "Odcinkowy pomiar prędkości",
            "Średnia na odcinku, ile zostało i jak szybko można jechać do końca\n$inf",
            onClick = { Prefs.setSectionAlerts(!v) },
        ) { CarSwitch(v) }
    }
    item {
        val v by Prefs.cameraSound.collectAsState()
        SettingRow("Dźwięk i komunikat głosowy", "Sygnał i np. „Fotoradar za 500 metrów” (działa też przy wyłączonym głosie nawigacji)", onClick = { Prefs.setCameraSound(!v) }) { CarSwitch(v) }
    }
    item {
        val st by pl.mati.carlauncher.data.RoadWatch.state.collectAsState()
        Text(
            (st.blocked?.let { "$it.\n" } ?: "") +
                "W Niemczech ostrzeżenia wyłączają się same (tam to zabronione). Dane: współtwórcy OpenStreetMap – nie wszystkie urządzenia są na mapie, " +
                "a niektóre mogą być nieaktualne. Zawsze jedź zgodnie ze znakami.",
            fontSize = 15.sp, color = CarColors.Muted,
        )
    }
}

private fun LazyListScope.alertsSection() {
    item { SectionLabel("Fotoradary i ostrzeżenia (CamSam w radiu lub w telefonie)") }
    item {
        val v by Prefs.roadAlerts.collectAsState()
        SettingRow("Pokazuj ostrzeżenia", "Fotoradary i kontrole z CamSam / CamSam PLUS na każdym ekranie CAR OS", onClick = { Prefs.setRoadAlerts(!v) }) { CarSwitch(v) }
    }
    item {
        val v by Prefs.phoneAlerts.collectAsState()
        val paired by pl.mati.carlauncher.data.ConnectRepository.paired.collectAsState()
        val last by AlertRepository.lastPhone.collectAsState()
        SettingRow(
            "CamSam z telefonu",
            "Ostrzeżenia CamSam / Yanosika z telefonu przez CAR OS Connect (w telefonie: Ustawienia → Ostrzeżenia drogowe). Radio potrzebuje internetu.\n" +
                when {
                    !paired -> "Telefon nie jest połączony – Ustawienia → Połączenia → CAR OS Connect"
                    last == 0L -> "Z telefonu jeszcze nic nie przyszło"
                    else -> "Ostatnie ostrzeżenie z telefonu: " + formatTime(last)
                },
            onClick = { Prefs.setPhoneAlerts(!v) },
        ) { CarSwitch(v) }
    }
    item {
        val ctx = LocalContext.current
        val seen by AlertRepository.lastSeen.collectAsState()
        val cam = remember { AlertRepository.installed(ctx) }
        var notifOk by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            while (true) {
                notifOk = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
                kotlinx.coroutines.delay(2000)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingRow(
                "CamSam w radiu",
                cam?.let { "Zainstalowany ($it)" } ?: "Nie znaleziono – zainstaluj CamSam PLUS w radiu (Sklep Play)",
            ) { StatusText(cam != null) }
            SettingRow(
                "Dostęp do powiadomień",
                "CAR OS czyta ostrzeżenia z powiadomień CamSam (widżet CamSam może być wyłączony)",
                onClick = { Launch.start(ctx, Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) },
            ) { StatusText(notifOk) }
            Text(
                "W CamSam PLUS włącz pracę w tle i powiadomienia z ostrzeżeniami. Baner pokaże się tylko przy prawdziwym ostrzeżeniu " +
                    "(fotoradar, kontrola, pomiar odcinkowy, policja…). Ostatnie powiadomienie z CamSam: " +
                    (if (seen == 0L) "jeszcze nic" else formatTime(seen)),
                fontSize = 15.sp, color = CarColors.Muted,
            )
            SecondaryButton("Pokaż próbne ostrzeżenie", { AlertRepository.test() }, height = 52.dp)
        }
    }
    item {
        val raw by AlertRepository.raw.collectAsState()
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).carSurface(RoundedCornerShape(18.dp), CarColors.Card).padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Co ostatnio przyszło z CamSam", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            if (raw.isEmpty()) {
                Text("Nic – uruchom CamSam i podjedź w pobliże fotoradaru albo poczekaj na komunikat.", fontSize = 15.sp, color = CarColors.Muted)
            }
            raw.forEach { r ->
                Text(
                    "${formatTime(r.time)} · ${r.app} (${r.source}${if (r.ongoing) ", stałe" else ""}): ${r.title} | ${r.text}",
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
    item {
        val v by Prefs.destSuggest.collectAsState()
        SettingRow(
            "Podpowiedź celu z Dziennika tras",
            "Po zapłonie: dokąd zwykle jedziesz z tego miejsca o tej porze (np. rano do pracy, wieczorem do domu) – wystarczą 2 podobne jazdy",
            onClick = { Prefs.setDestSuggest(!v) },
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

/** Podgląd jasnego wariantu: tło, karta, napis, akcent. */
@Composable
private fun PalettePreview(p: Palette, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(p.bg)
            .border(3.dp, if (selected) accent else Color.Transparent, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1.4f).height(54.dp).clip(RoundedCornerShape(10.dp)).background(p.mapBg).border(1.dp, p.border, RoundedCornerShape(10.dp)))
            Column(
                Modifier.weight(1f).height(54.dp).clip(RoundedCornerShape(10.dp)).background(p.card).border(1.dp, p.border, RoundedCornerShape(10.dp)).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(Modifier.fillMaxWidth(0.8f).height(7.dp).clip(RoundedCornerShape(4.dp)).background(p.text))
                Box(Modifier.fillMaxWidth(0.55f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(p.muted))
                Box(Modifier.size(14.dp).clip(CircleShape).background(CarColors.accentFor(Color(Prefs.accent.value), true)))
            }
        }
        Text(p.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = p.text)
    }
}

/** Kreator rozpoznawania sygnału świateł. */
object Calibration {
    val open = kotlinx.coroutines.flow.MutableStateFlow(false)
}

@Composable
fun HeadlightCalibrationOverlay() {
    val open by Calibration.open.collectAsState()
    if (!open) return
    val ctx = LocalContext.current
    var step by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<String?>(null) }
    val steps = listOf(
        "Wyłącz światła (pozycyjne i mijania), potem dotknij „Dalej”.",
        "Teraz WŁĄCZ światła i odczekaj 2–3 sekundy, potem „Dalej”.",
        "WYŁĄCZ światła ponownie, odczekaj 2–3 sekundy i dotknij „Zakończ”.",
    )
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(enabled = true, onClick = {}),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(640.dp).clip(RoundedCornerShape(28.dp)).carSurface(RoundedCornerShape(28.dp), CarColors.Panel, heavy = true)
                .border(1.dp, CarColors.Border, RoundedCornerShape(28.dp)).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("Rozpoznaj światła auta", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            if (result == null) {
                Text("Krok ${step + 1} z 3", fontSize = 16.sp, color = LocalAccent.current, fontWeight = FontWeight.SemiBold)
                Text(steps[step], fontSize = 21.sp, color = CarColors.Text2)
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    SecondaryButton("Anuluj", { HeadlightDetector.cancelCalibration(); Calibration.open.value = false }, Modifier.weight(1f))
                    PrimaryButton(if (step < 2) "Dalej" else "Zakończ", {
                        val r = HeadlightDetector.calibrateStep(ctx, step)
                        if (step < 2) step++
                        else result = r?.let { "Gotowe – rozpoznano: ${it.describe()}.\nJasny wygląd przy wyłączonych światłach, ciemny przy włączonych." }
                            ?: "Nie znalazłem sygnału świateł w tym radiu. Tryb przełącza się wg wschodu i zachodu słońca."
                    }, Modifier.weight(1f))
                }
            } else {
                Text(result!!, fontSize = 20.sp, color = CarColors.Text2)
                PrimaryButton("OK", {
                    Calibration.open.value = false
                    step = 0
                    result = null
                    ThemeManager.update()
                }, Modifier.fillMaxWidth())
            }
        }
    }
}

/** Wybór ikony auta na mapie: typ nadwozia + kolor, z podglądem. */
@Composable
private fun CarIconPicker() {
    val body by Prefs.carBody.collectAsState()
    val paint by Prefs.carPaint.collectAsState()
    val preview = remember(body, paint) { Model3D.carIcon(body, paint, 35.0, 32.0, 360).asImageBitmap() }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).carSurface(RoundedCornerShape(18.dp), CarColors.Card).padding(18.dp),
        horizontalArrangement = Arrangement.spacedBy(22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(170.dp).clip(RoundedCornerShape(16.dp)).background(CarColors.MapBg),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.foundation.Image(preview, "Podgląd auta", modifier = Modifier.size(150.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Ikona auta na mapie", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            CarIcon.BODIES.chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { b -> ChoiceChip(b.label, b.id == body, Modifier.weight(1f)) { Prefs.setCarBody(b.id) } }
                }
            }
            CarIcon.COLORS.chunked(6).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    row.forEach { (_, c) -> Swatch(Color(c), c == paint) { Prefs.setCarPaint(c) } }
                }
            }
            Text(CarIcon.COLORS.firstOrNull { it.second == paint }?.first ?: "", fontSize = 15.sp, color = CarColors.Muted)
        }
    }
}

@Composable
private fun WeekCard(label: String, monday: java.time.LocalDate, s: WorkSchedule.Shift?, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).carSurface(RoundedCornerShape(16.dp), CarColors.Card).padding(horizontal = 16.dp, vertical = 12.dp)) {
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
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).carSurface(RoundedCornerShape(18.dp), CarColors.Card).padding(horizontal = 18.dp, vertical = 14.dp),
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
        SettingRow("Aktualizacja i lista zmian", sub, onClick = { UiState.settingsPage.value = "update" }) {
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
        val v by Prefs.shareLive.collectAsState()
        val fb by Prefs.fbUrl.collectAsState()
        val iv by Prefs.liveInterval.collectAsState()
        val fbErr by ConnectRepository.fbError.collectAsState()
        SettingRow(
            "Auto na żywo w telefonie",
            if (fb.isBlank()) "W czasie jazdy pozycja auta co minutę w CAR OS Connect (np. gdy ktoś inny jedzie autem). Szybciej z Firebase – ustawia się w telefonie: Ustawienia → Pozycja auta na żywo"
            else "W czasie jazdy pozycja auta ${if (iv <= 0) "na bieżąco" else "co $iv s"} przez Firebase (${fb.removePrefix("https://").substringBefore('.')})" + (fbErr?.let { " · $it" } ?: ""),
            onClick = { Prefs.setShareLive(!v) },
        ) { CarSwitch(v) }
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
        Box(Modifier.size(30.dp).clip(CircleShape).carSurface(CircleShape, CarColors.Raised), contentAlignment = Alignment.Center) {
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
    item { SectionLabel("Lista zmian") }
    item {
        val list by pl.mati.carlauncher.data.Changelog.entries.collectAsState()
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (list.isEmpty()) Text("Brak listy zmian w tej wersji.", fontSize = 17.sp, color = CarColors.Muted)
            list.forEach { ChangelogCard(it) }
        }
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
            val parsed = remember(r.notes) { pl.mati.carlauncher.data.Changelog.fromBody(r.name, r.notes) }
            if (parsed != null) ChangelogPoints(parsed)
            else if (r.notes.isNotBlank()) Text(r.notes.take(600), fontSize = 18.sp, color = CarColors.Text2)
            actions()
        }
    }
}

/** Mapy bez internetu: okolica auta i trasa dom ↔ praca. */
@Composable
private fun OfflineMapsCard() {
    val areas by pl.mati.carlauncher.data.OfflineMaps.areas.collectAsState()
    val busy by pl.mati.carlauncher.data.OfflineMaps.busy.collectAsState()
    val accent = LocalAccent.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).carSurface(RoundedCornerShape(18.dp), CarColors.Card).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Mapy offline", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Mapa działa bez internetu tam, gdzie jest pobrana. Okolice, przez które jeździsz, CAR OS zapamiętuje sam (do 800 MB). " +
                "Pobieranie może zająć kilkaset MB – najlepiej przez Wi-Fi.",
            fontSize = 15.sp, color = CarColors.Muted,
        )
        for ((group, title, sub) in listOf(
            Triple("okolica", "Okolica auta", "Ok. 40 km wokół obecnej pozycji"),
            Triple("dojazd", "Trasa dom ↔ praca", "Pas ok. 7 km wzdłuż trasy"),
        )) {
            val a = areas[group]
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    val state = when {
                        busy == group -> "Wyznaczam trasę…"
                        a == null -> sub
                        a.error != null -> a.error
                        a.complete -> "Pobrano · ${(a.bytes / 1048576f).pl1()} MB"
                        a.required > 0 -> "Pobieranie ${(a.done * 100 / a.required)}% · ${(a.bytes / 1048576f).pl1()} MB"
                        else -> "Przygotowuję…"
                    }
                    Text(state, fontSize = 15.sp, color = if (a?.complete == true) CarColors.Green else CarColors.Muted)
                    if (a != null && !a.complete && a.required > 0) {
                        ProgressBar(a.done.toFloat() / a.required, accent, Modifier.fillMaxWidth().padding(top = 6.dp))
                    }
                }
                if (a == null) {
                    SecondaryButton("Pobierz", {
                        val err = if (group == "okolica") pl.mati.carlauncher.data.OfflineMaps.downloadAround()
                        else pl.mati.carlauncher.data.OfflineMaps.downloadCommute()
                        err?.let { Toaster.show(it) }
                    }, height = 52.dp)
                } else {
                    SecondaryButton(if (a.complete) "Odśwież" else "Od nowa", {
                        if (group == "okolica") pl.mati.carlauncher.data.OfflineMaps.downloadAround()?.let { Toaster.show(it) }
                        else pl.mati.carlauncher.data.OfflineMaps.downloadCommute()?.let { Toaster.show(it) }
                    }, height = 52.dp)
                    SecondaryButton("Usuń", { pl.mati.carlauncher.data.OfflineMaps.delete(group) }, height = 52.dp)
                }
            }
        }
    }
}

@Composable
private fun Chevron() = Icon(Icons.Rounded.ChevronRight, null, tint = CarColors.Muted, modifier = Modifier.size(24.dp))

@Composable
private fun StatusText(ok: Boolean) =
    Text(if (ok) "Włączone" else "Włącz", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = if (ok) CarColors.Green else LocalAccent.current)
