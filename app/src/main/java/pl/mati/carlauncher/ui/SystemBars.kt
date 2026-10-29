package pl.mati.carlauncher.ui

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Build
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.alpha
import pl.mati.carlauncher.data.ServiceRepository
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import pl.mati.carlauncher.data.AlertRepository
import pl.mati.carlauncher.data.AutostartManager
import pl.mati.carlauncher.data.RouteRepository
import pl.mati.carlauncher.data.Prefs as P2
import pl.mati.carlauncher.data.NotificationRepository
import pl.mati.carlauncher.data.ObdManager
import pl.mati.carlauncher.data.Prefs
import pl.mati.carlauncher.data.SystemControls
import pl.mati.carlauncher.data.WeatherRepository

/** Górny pasek systemu. Dotknij lub przeciągnij w dół → centrum sterowania. */
@Composable
fun StatusBar() {
    val now by rememberNow()
    val temp by WeatherRepository.temperature.collectAsState()
    val obd by ObdManager.status.collectAsState()
    val bt by SystemControls.bluetoothOn.collectAsState()
    val wifi by SystemControls.wifiOn.collectAsState()
    val vol by SystemControls.volume.collectAsState()
    val ntf by NotificationRepository.items.collectAsState()
    var drag by remember { mutableFloatStateOf(0f) }

    Box(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .pointerInput(Unit) { detectTapGestures { UiState.controlCenter.value = true } }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { drag = 0f },
                    onDragEnd = { if (drag > 30f) UiState.controlCenter.value = true },
                ) { _, dy -> drag += dy }
            },
    ) {
        Box(
            Modifier.align(Alignment.TopCenter).padding(top = 6.dp).size(56.dp, 5.dp)
                .clip(RoundedCornerShape(3.dp)).background(CarColors.Switch),
        )
        Row(
            Modifier.fillMaxSize().padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(formatTime(now), fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(12.dp))
            Text(formatDate(now), fontSize = 18.sp, color = CarColors.Muted)
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                if (ntf.isNotEmpty()) BarIcon(Icons.Rounded.NotificationsNone, ntf.size.toString())
                if (obd != ObdManager.Status.OFF) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Box(Modifier.size(9.dp).clip(CircleShape).background(obdColor(obd)))
                        Text("OBD", fontSize = 18.sp, color = CarColors.Muted)
                    }
                }
                ServiceBarIcon()
                temp?.let { BarIcon(Icons.Rounded.Thermostat, "$it°C") }
                BarIcon(if (bt) Icons.Rounded.Bluetooth else Icons.Rounded.BluetoothDisabled, null)
                BarIcon(if (wifi) Icons.Rounded.Wifi else Icons.Rounded.WifiOff, null)
                PhoneSignal()
                BarIcon(if (vol == 0) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp, vol.toString())
            }
        }
    }
}

@Composable
private fun BarIcon(icon: ImageVector, text: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Icon(icon, null, tint = CarColors.Muted, modifier = Modifier.size(21.dp))
        if (text != null) Text(text, fontSize = 18.sp, color = CarColors.Muted)
    }
}

@Composable
fun ControlCenter() {
    val open by UiState.controlCenter.collectAsState()
    val ctx = LocalContext.current
    val activity = ctx as? Activity
    LaunchedEffect(open) { if (open) SystemControls.refresh(ctx) }

    AnimatedVisibility(open, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x9E050608))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    UiState.controlCenter.value = false
                },
        )
    }
    AnimatedVisibility(open, enter = slideInVertically { -it }, exit = slideOutVertically { -it }) {
        var drag by remember { mutableFloatStateOf(0f) }
        Box(Modifier.fillMaxWidth().padding(start = 124.dp, end = 24.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
                    .background(CarColors.Panel)
                    .border(1.dp, Color(0xFF232933), RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragStart = { drag = 0f },
                            onDragEnd = { if (drag < -40f) UiState.controlCenter.value = false },
                        ) { _, dy -> drag += dy }
                    }
                    .padding(start = 22.dp, end = 22.dp, top = 24.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                ControlsColumn(Modifier.weight(1f), activity)
                NotificationsColumn(Modifier.width(380.dp))
            }
        }
    }
}

@Composable
private fun ControlsColumn(modifier: Modifier, activity: Activity?) {
    val ctx = LocalContext.current
    val wifi by SystemControls.wifiOn.collectAsState()
    val bt by SystemControls.bluetoothOn.collectAsState()
    val night by Prefs.nightNow.collectAsState()
    val autoNight by Prefs.autoNight.collectAsState()
    val obd by ObdManager.status.collectAsState()
    val mac by Prefs.obdMac.collectAsState()
    val bri by SystemControls.brightness.collectAsState()
    val vol by SystemControls.volume.collectAsState()
    val volMax by SystemControls.volumeMax.collectAsState()
    var askedWrite by remember { mutableStateOf(false) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            QuickTile(Icons.Rounded.Wifi, "Wi-Fi", if (wifi) "Włączone" else "Wyłączone", wifi, Modifier.weight(1f)) {
                SystemControls.openWifiPanel(ctx)
            }
            QuickTile(Icons.Rounded.Bluetooth, "Bluetooth", if (bt) "Włączony" else "Wyłączony", bt, Modifier.weight(1f)) {
                if (!SystemControls.toggleBluetooth(ctx)) {
                    Launch.start(ctx, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            QuickTile(
                Icons.Rounded.DarkMode, "Tryb nocny",
                if (night) "Włączony" else if (autoNight) "Auto o zmroku" else "Wyłączony",
                night, Modifier.weight(1f),
            ) { Prefs.setNightNow(!night) }
            QuickTile(
                Icons.Rounded.Speed, "OBD2",
                when (obd) {
                    ObdManager.Status.CONNECTED -> "Połączony"
                    ObdManager.Status.CONNECTING -> "Łączenie…"
                    ObdManager.Status.ERROR -> "Błąd – ponawiam"
                    ObdManager.Status.OFF -> if (mac == null) "Nie skonfigurowano" else "Wyłączony"
                },
                obd == ObdManager.Status.CONNECTED || obd == ObdManager.Status.CONNECTING, Modifier.weight(1f),
            ) {
                val m = mac
                when {
                    m == null -> UiState.openSettings("car")
                    obd == ObdManager.Status.OFF -> ObdManager.start(m)
                    else -> ObdManager.stop()
                }
            }
        }
        FillSlider(
            bri / 100f,
            {
                if (!SystemControls.canWriteSettings(ctx) && !askedWrite) {
                    askedWrite = true
                    Toaster.show("Zezwól CAR OS na zmianę ustawień systemu, żeby sterować jasnością")
                    SystemControls.requestWriteSettings(ctx)
                }
                SystemControls.setBrightness(activity, ctx, (it * 100).toInt())
            },
            Icons.Rounded.WbSunny, "Jasność", "$bri%",
        )
        FillSlider(
            if (volMax > 0) vol.toFloat() / volMax else 0f,
            { SystemControls.setVolume(ctx, Math.round(it * volMax)) },
            Icons.Rounded.VolumeUp, "Głośność", "$vol",
        )
    }
}

@Composable
private fun QuickTile(icon: ImageVector, title: String, sub: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Row(
        modifier
            .height(96.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(if (on) accent else CarColors.Raised)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val fg = if (on) CarColors.Bg else CarColors.Text
        Icon(icon, null, tint = fg, modifier = Modifier.size(30.dp))
        Column {
            Text(title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = fg)
            Text(sub, fontSize = 15.sp, color = if (on) CarColors.Bg.copy(alpha = 0.75f) else CarColors.Muted, maxLines = 1)
        }
    }
}

@Composable
private fun NotificationsColumn(modifier: Modifier) {
    val items by NotificationRepository.items.collectAsState()
    val accent = LocalAccent.current
    val now by rememberNow()
    Column(modifier.height(392.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Powiadomienia", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (items.isNotEmpty()) {
                Text(
                    "Wyczyść", fontSize = 16.sp, color = CarColors.Muted,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { NotificationRepository.clearAll() }.padding(10.dp),
                )
            }
        }
        if (items.isEmpty()) {
            Text("Brak powiadomień", fontSize = 17.sp, color = CarColors.Muted, modifier = Modifier.padding(vertical = 20.dp, horizontal = 4.dp))
        }
        items.take(4).forEach { n ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(CarColors.Raised)
                    .clickable {
                        UiState.controlCenter.value = false
                        when (n.action) {
                            "update" -> UiState.openSettings("update")
                            "obd" -> UiState.go(Screen.VEHICLE)
                            "service" -> UiState.go(Screen.SERVICE)
                            else -> NotificationRepository.open(n)
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(CarColors.Track),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (n.action == "update") Icons.Rounded.SystemUpdate else Icons.Rounded.NotificationsNone, null, tint = accent, modifier = Modifier.size(24.dp))
                }
                Column(Modifier.weight(1f)) {
                    val mins = ((now - n.time) / 60000).coerceAtLeast(0)
                    Text("${n.app} · ${if (mins < 1) "teraz" else "$mins min"}", fontSize = 14.sp, color = CarColors.Muted, maxLines = 1)
                    Text(
                        listOf(n.title, n.text).filter { it.isNotBlank() }.joinToString(" – "),
                        fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.align(Alignment.CenterHorizontally).size(56.dp, 5.dp).clip(RoundedCornerShape(3.dp)).background(CarColors.Switch)
                .clickable { UiState.controlCenter.value = false },
        )
    }
}

/** Pionowy wskaźnik głośności po zmianie suwakiem. */
@Composable
fun VolumeHud() {
    val at by SystemControls.volumeHudAt.collectAsState()
    val vol by SystemControls.volume.collectAsState()
    val max by SystemControls.volumeMax.collectAsState()
    var visible by remember { mutableStateOf(false) }
    val accent = LocalAccent.current
    LaunchedEffect(at) {
        if (at == 0L) return@LaunchedEffect
        visible = true
        delay(1300)
        visible = false
    }
    Box(Modifier.fillMaxSize().padding(end = 28.dp), contentAlignment = Alignment.CenterEnd) {
        AnimatedVisibility(visible && !UiState.controlCenter.value, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier.size(64.dp, 300.dp).clip(RoundedCornerShape(32.dp)).background(Color(0xF212161B))
                    .border(1.dp, CarColors.Switch, RoundedCornerShape(32.dp)),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(Modifier.fillMaxWidth().fillMaxHeight(if (max > 0) vol.toFloat() / max else 0f).background(accent.copy(alpha = 0.33f)))
                Column(Modifier.padding(bottom = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.VolumeUp, null, tint = CarColors.Text, modifier = Modifier.size(26.dp))
                    Text("$vol", fontSize = 16.sp)
                }
            }
        }
    }
}


/** Pomarańczowy pasek ostrzeżenia z CamSam / Yanosik – na każdym ekranie. */
@Composable
fun RoadAlertBanner() {
    val alert by AlertRepository.current.collectAsState()
    val ctx = LocalContext.current
    LaunchedEffect(alert?.time) {
        val a = alert ?: return@LaunchedEffect
        delay(60_000)
        if (AlertRepository.current.value?.time == a.time) AlertRepository.dismiss()
    }
    Box(Modifier.fillMaxWidth().padding(start = 124.dp, end = 24.dp, top = 58.dp), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(alert != null, enter = fadeIn() + slideInVertically { -it }, exit = fadeOut() + slideOutVertically { -it }) {
            val a = alert
            if (a != null) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color(0xFFFF8A1F))
                        .clickable { Launch.pkg(ctx, a.pkg) }
                        .padding(start = 18.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Icon(Icons.Rounded.Warning, null, tint = Color(0xFF1A1205), modifier = Modifier.size(34.dp))
                    Column(Modifier.padding(end = 6.dp)) {
                        Text(a.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1A1205), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (a.text.isNotBlank()) Text(a.text, fontSize = 17.sp, color = Color(0xFF3A2608), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(a.app, fontSize = 14.sp, color = Color(0xFF3A2608))
                    Box(
                        Modifier.size(44.dp).clip(CircleShape).clickable { AlertRepository.dismiss() },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.Close, "Ukryj", tint = Color(0xFF1A1205), modifier = Modifier.size(24.dp)) }
                }
            }
        }
    }
}


/** „Jedziesz do domu?” – po zapłonie w ustawionych godzinach. */
@Composable
fun HomeSuggestionBanner() {
    val show by AutostartManager.homeSuggestion.collectAsState()
    val ctx = LocalContext.current
    val accent = LocalAccent.current
    Box(Modifier.fillMaxSize().padding(start = 124.dp, end = 24.dp, bottom = 28.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(show, enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it }) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(CarColors.Panel)
                    .border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(24.dp))
                    .padding(horizontal = 22.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                IconBadge(Icons.Rounded.Home, 56.dp)
                Column {
                    Text("Jedziesz do domu?", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(P2.profile.value.home, fontSize = 16.sp, color = CarColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                PrimaryButton("Jedź", {
                    AutostartManager.homeSuggestion.value = false
                    RouteRepository.quickStart(ctx, P2.profile.value.home, "Dom")
                    Toaster.show("Wyznaczam trasę do domu…")
                })
                SecondaryButton("Nie", { AutostartManager.homeSuggestion.value = false })
            }
        }
    }
}


/** Ikona serwisu na pasku: czerwona mrugająca = po terminie, pomarańczowa = wkrótce. */
@Composable
private fun ServiceBarIcon() {
    val items by ServiceRepository.items.collectAsState()
    val odo by ServiceRepository.odometerKm.collectAsState()
    val now by rememberNow()
    val state = remember(items, odo.toInt(), now / 60_000) {
        val list = ServiceRepository.dueList()
        when {
            list.any { it.overdue } -> 2
            list.any { it.soon } -> 1
            else -> 0
        }
    }
    if (state == 0) return
    val t = rememberInfiniteTransition(label = "svc")
    val a by t.animateFloat(1f, 0.15f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "a")
    Box(
        Modifier.size(40.dp).clip(CircleShape)
            .background(if (state == 2) CarColors.Red.copy(alpha = 0.18f) else Color.Transparent)
            .clickable { UiState.go(Screen.SERVICE) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.Build, "Serwis",
            tint = if (state == 2) CarColors.Red else CarColors.Orange,
            modifier = Modifier.size(22.dp).alpha(if (state == 2) a else 1f),
        )
    }
}

/** Prawie pełnoekranowy alert serwisowy – znika dopiero po X. */
@Composable
fun ServiceAlertOverlay() {
    val show by AutostartManager.serviceAlert.collectAsState()
    AnimatedVisibility(show, enter = fadeIn(), exit = fadeOut()) {
        val list = remember(show) { ServiceRepository.overdue() }
        val t = rememberInfiniteTransition(label = "alert")
        val a by t.animateFloat(1f, 0.35f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a")
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xE6050608))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .padding(horizontal = 70.dp, vertical = 40.dp)
                    .fillMaxSize()
                    .clip(RoundedCornerShape(32.dp))
                    .background(Color(0xFF1A0B0C))
                    .border(3.dp, CarColors.Red.copy(alpha = a), RoundedCornerShape(32.dp))
                    .padding(horizontal = 44.dp, vertical = 30.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Box(Modifier.size(84.dp).clip(CircleShape).background(CarColors.Red.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Warning, null, tint = CarColors.Red, modifier = Modifier.size(52.dp).alpha(a))
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Przekroczony termin serwisu", fontSize = 40.sp, fontWeight = FontWeight.Bold)
                        Text("Zaplanuj wizytę w warsztacie albo zaktualizuj dane w Serwisie.", fontSize = 20.sp, color = CarColors.Text2)
                    }
                    Box(
                        Modifier.size(72.dp).clip(CircleShape).background(CarColors.Raised).clickable { AutostartManager.serviceAlert.value = false },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.Close, "Zamknij alert", tint = CarColors.Text, modifier = Modifier.size(40.dp)) }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    list.take(5).forEach { d ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xFF2A1214)).padding(horizontal = 22.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Icon(Icons.Rounded.Build, null, tint = CarColors.Red, modifier = Modifier.size(28.dp))
                            Text(d.item.name, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text(ServiceRepository.describe(d), fontSize = 20.sp, color = Color(0xFFFF8A8E))
                        }
                    }
                    if (list.size > 5) Text("i jeszcze ${list.size - 5}…", fontSize = 18.sp, color = CarColors.Muted)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    PrimaryButton("Przejdź do serwisu", {
                        AutostartManager.serviceAlert.value = false
                        UiState.go(Screen.SERVICE)
                    })
                    SecondaryButton("Zamknij", { AutostartManager.serviceAlert.value = false })
                }
            }
        }
    }
}


/** Operator, zasięg i bateria telefonu połączonego przez Bluetooth (HFP). */
@Composable
private fun PhoneSignal() {
    val st by pl.mati.carlauncher.data.PhoneStatusRepository.status.collectAsState()
    val sig = st.signal
    if (sig == null) {
        BarIcon(Icons.Rounded.SignalCellularAlt, null)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        st.operator?.let { Text(it, fontSize = 18.sp, color = CarColors.Muted, maxLines = 1) }
        androidx.compose.foundation.Canvas(Modifier.size(22.dp, 18.dp)) {
            val bars = 5
            val w = size.width / (bars * 1.6f)
            for (i in 0 until bars) {
                val h = size.height * (i + 1) / bars
                drawRect(
                    color = if (i < sig) CarColors.Muted else CarColors.Muted.copy(alpha = 0.25f),
                    topLeft = androidx.compose.ui.geometry.Offset(i * w * 1.6f, size.height - h),
                    size = androidx.compose.ui.geometry.Size(w, h),
                )
            }
        }
        st.battery?.let { b ->
            Text("${(b.coerceIn(0, 5) * 20)}%", fontSize = 16.sp, color = if (b <= 1) CarColors.Red else CarColors.Muted)
        }
    }
}
