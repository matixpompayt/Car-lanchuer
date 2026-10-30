package pl.mati.carosparking

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.TilesOverlay
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

object C {
    val Bg = Color(0xFF0A0C0F)
    val Card = Color(0xFF14181E)
    val Raised = Color(0xFF1A1F26)
    val Border = Color(0xFF1E232B)
    val Text = Color(0xFFEEF1F4)
    val Text2 = Color(0xFFC3C9D0)
    val Muted = Color(0xFF9AA3AE)
    val Accent = Color(0xFF2FD3F0)
    val Green = Color(0xFF7CF06B)
    val Orange = Color(0xFFFFB03A)
}

private val TIME = SimpleDateFormat("HH:mm", Locale("pl"))
private val DAY = SimpleDateFormat("d.MM, HH:mm", Locale("pl"))

fun agoText(t: Long): String {
    val min = ((System.currentTimeMillis() - t) / 60_000).toInt()
    return when {
        min < 1 -> "przed chwilą"
        min < 60 -> "$min min temu"
        min < 24 * 60 -> "${min / 60} h ${min % 60} min temu"
        else -> "${min / (24 * 60)} dni temu"
    }
}

fun whenText(t: Long): String {
    val today = SimpleDateFormat("yyyyMMdd", Locale.US)
    return if (today.format(Date(t)) == today.format(Date())) "dziś, " + TIME.format(Date(t)) else DAY.format(Date(t))
}

fun distText(m: Float): String = if (m >= 1000) String.format(Locale("pl"), "%.1f km", m / 1000) else "${(m / 5).roundToInt() * 5} m"

@Composable
fun ParkApp(perms: Perms, actions: Actions) {
    MaterialTheme(colorScheme = darkColorScheme(primary = C.Accent, background = C.Bg, surface = C.Card)) {
        val ctx = LocalContext.current
        val spot by Store.spot.collectAsState()
        val driving by Store.driving.collectAsState()
        val car by Store.carName.collectAsState()
        val history by Store.history.collectAsState()
        val scope = rememberCoroutineScope()
        var picker by remember { mutableStateOf(false) }
        var tick by remember { mutableLongStateOf(0L) }
        LaunchedEffect(Unit) { while (true) { delay(30_000); tick++ } }

        val me = rememberPhoneLocation(perms.loc)
        val azimuth = rememberAzimuth(me)

        Column(
            Modifier.fillMaxSize().background(C.Bg).statusBarsPadding().navigationBarsPadding()
                .verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // nagłówek
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("CAR OS", fontSize = 13.sp, color = C.Accent, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                    Text("Moje auto", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = C.Text)
                }
                StatusPill(driving, car)
            }

            if (!perms.allOk || car == null) SetupCard(perms, actions, car) { picker = true }

            val s = spot
            if (s == null) {
                Card {
                    Text("Brak zapisanego miejsca", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = C.Text)
                    Text(
                        "Gdy zgasisz auto i telefon rozłączy się z radiem, aplikacja sama zapisze, gdzie stoi auto. Możesz też zapisać miejsce ręcznie.",
                        fontSize = 15.sp, color = C.Muted,
                    )
                }
            } else {
                ParkMap(s, Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(24.dp)).border(1.dp, C.Border, RoundedCornerShape(24.dp)))
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(44.dp).clip(CircleShape).background(C.Accent), contentAlignment = Alignment.Center) {
                            Text("P", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = C.Bg)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(s.address ?: s.coords, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = C.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val accTxt = if (s.accuracy > 0) " · ±${s.accuracy.roundToInt()} m" else ""
                            Text("${whenText(s.time)} · ${agoText(s.time + 0 * tick)}$accTxt", fontSize = 14.sp, color = C.Muted)
                        }
                    }
                    if (driving) Text("Jesteś teraz w aucie – to poprzednie miejsce parkowania.", fontSize = 13.sp, color = C.Orange)
                }
                FinderCard(s, me, azimuth)
                PrimaryButton("Prowadź do auta", Icons.Rounded.DirectionsWalk) {
                    try { ctx.startActivity(Notifs.walkIntent(s)) } catch (_: Exception) {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.mapsUrl)))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Zapisz tutaj", Icons.Rounded.LocationOn, Modifier.weight(1f)) {
                    if (!perms.loc) { actions.requestLoc(); return@SecondaryButton }
                    Toast.makeText(ctx, "Ustalam pozycję…", Toast.LENGTH_SHORT).show()
                    scope.launch {
                        val l = withContext(Dispatchers.IO) { Locator.freshFix(ctx, 15) }
                        if (l == null) {
                            Toast.makeText(ctx, "Brak sygnału GPS – spróbuj na zewnątrz", Toast.LENGTH_LONG).show()
                        } else {
                            Store.saveSpot(ctx, Store.Spot(l.latitude, l.longitude, System.currentTimeMillis(), l.accuracy, manual = true))
                            Toast.makeText(ctx, "Zapisano miejsce", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                if (s != null) SecondaryButton("Udostępnij", Icons.Rounded.Share, Modifier.weight(1f)) {
                    val txt = "Moje auto stoi tu: ${s.address ?: s.coords}\n${s.mapsUrl}"
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, txt), "Udostępnij miejsce"))
                }
            }

            // historia
            val older = history.filter { it.time != s?.time }
            if (older.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label("Poprzednie miejsca", Modifier.weight(1f))
                    Text("Wyczyść", fontSize = 14.sp, color = C.Muted, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { Store.clearHistory() }.padding(8.dp))
                }
                older.take(8).forEach { h ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.Card)
                            .clickable { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(h.mapsUrl))) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Rounded.History, null, tint = C.Muted, modifier = Modifier.size(22.dp))
                        Column(Modifier.weight(1f)) {
                            Text(h.address ?: h.coords, fontSize = 16.sp, color = C.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(whenText(h.time), fontSize = 13.sp, color = C.Muted)
                        }
                        Icon(Icons.Rounded.ChevronRight, null, tint = C.Muted, modifier = Modifier.size(22.dp))
                    }
                }
            }

            // ustawienia
            Label("Ustawienia", Modifier.padding(top = 8.dp))
            SettingItem(Icons.Rounded.DirectionsCar, "Auto (Bluetooth radia)", car ?: "Nie wybrano") {
                if (!perms.bt) actions.requestBt() else picker = true
            }
            SettingItem(Icons.Rounded.PlayCircle, "Autostart (Xiaomi)", "Włącz, żeby aplikacja budziła się po połączeniu z autem") { actions.openAutostart() }
            UpdateItem()
            Text("CAR OS Parking ${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = C.Muted, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
        }

        if (picker) DevicePicker(actions, onDismiss = { picker = false })
    }
}

@Composable
private fun StatusPill(driving: Boolean, car: String?) {
    val (txt, col) = when {
        car == null -> "Nie skonfigurowano" to C.Orange
        driving -> "W trasie" to C.Green
        else -> "Zaparkowane" to C.Accent
    }
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(col.copy(alpha = 0.15f)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(col))
        Text(txt, fontSize = 13.sp, color = col, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SetupCard(perms: Perms, actions: Actions, car: String?, onPickCar: () -> Unit) {
    Card(border = C.Orange.copy(alpha = 0.5f)) {
        Text("Konfiguracja", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = C.Text)
        Text("Żeby aplikacja sama zapisywała miejsce po zgaszeniu auta, zezwól na:", fontSize = 14.sp, color = C.Muted)
        SetupRow(Icons.Rounded.Bluetooth, "Bluetooth", "Rozpoznanie radia w aucie", perms.bt) { actions.requestBt() }
        SetupRow(Icons.Rounded.DirectionsCar, "Wybierz auto", car ?: "Radio, z którym łączy się telefon", car != null) {
            if (!perms.bt) actions.requestBt() else onPickCar()
        }
        SetupRow(Icons.Rounded.MyLocation, "Lokalizacja", "Pozycja auta", perms.loc) { actions.requestLoc() }
        SetupRow(Icons.Rounded.LocationOn, "Lokalizacja: zawsze", "Wybierz „Zezwalaj zawsze” – zapis po zgaszeniu auta", perms.bgLoc) { actions.requestBgLoc() }
        SetupRow(Icons.Rounded.Notifications, "Powiadomienia", "Informacja, gdzie zaparkowano", perms.notif) { actions.requestNotif() }
        SetupRow(Icons.Rounded.BatteryChargingFull, "Bateria bez ograniczeń", "Inaczej system może uśpić aplikację", perms.battery) { actions.requestBattery() }
    }
}

@Composable
private fun SetupRow(icon: ImageVector, title: String, sub: String, ok: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(enabled = !ok, onClick = onClick).padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, tint = if (ok) C.Muted else C.Text, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = if (ok) C.Muted else C.Text)
            Text(sub, fontSize = 13.sp, color = C.Muted)
        }
        if (ok) Icon(Icons.Rounded.CheckCircle, "OK", tint = C.Green, modifier = Modifier.size(24.dp))
        else Text("Zezwól", fontSize = 15.sp, color = C.Accent, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun FinderCard(s: Store.Spot, me: Location?, azimuth: Float?) {
    Card {
        if (me == null) {
            Text("Włącz lokalizację, żeby zobaczyć kierunek i odległość do auta.", fontSize = 15.sp, color = C.Muted)
            return@Card
        }
        val target = Location("car").apply { latitude = s.lat; longitude = s.lon }
        val dist = me.distanceTo(target)
        val bearing = me.bearingTo(target)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Box(Modifier.size(96.dp).clip(CircleShape).background(C.Raised), contentAlignment = Alignment.Center) {
                if (dist < 15) {
                    Icon(Icons.Rounded.DirectionsCar, null, tint = C.Green, modifier = Modifier.size(48.dp))
                } else if (azimuth != null) {
                    Icon(Icons.Rounded.Navigation, "Kierunek do auta", tint = C.Accent, modifier = Modifier.size(64.dp).rotate(bearing - azimuth))
                } else {
                    Icon(Icons.Rounded.Navigation, null, tint = C.Muted, modifier = Modifier.size(56.dp))
                }
            }
            Column(Modifier.weight(1f)) {
                Text(if (dist < 15) "Jesteś przy aucie" else distText(dist), fontSize = 32.sp, fontWeight = FontWeight.Bold, color = C.Text)
                Text(
                    when {
                        dist < 15 -> "Auto powinno być obok"
                        azimuth != null -> "Idź w kierunku strzałki"
                        else -> "Kompas niedostępny"
                    },
                    fontSize = 14.sp, color = C.Muted,
                )
                if (me.accuracy > 25) Text("Słaby sygnał GPS (±${me.accuracy.roundToInt()} m)", fontSize = 13.sp, color = C.Orange)
            }
        }
    }
}

@Composable
private fun DevicePicker(actions: Actions, onDismiss: () -> Unit) {
    val devices = remember { actions.pairedDevices() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.Card,
        title = { Text("Wybierz radio w aucie", color = C.Text) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (devices.isEmpty()) Text("Brak sparowanych urządzeń. Sparuj telefon z radiem w ustawieniach Bluetooth.", color = C.Muted)
                devices.forEach { (name, mac) ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { Store.setCar(mac, name); onDismiss() }.padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Rounded.Bluetooth, null, tint = C.Accent, modifier = Modifier.size(22.dp))
                        Column {
                            Text(name, fontSize = 16.sp, color = C.Text)
                            Text(mac, fontSize = 12.sp, color = C.Muted)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Zamknij") } },
    )
}

@Composable
private fun UpdateItem() {
    val ctx = LocalContext.current
    val st by Updater.state.collectAsState()
    val sub = when (val s = st) {
        Updater.State.Idle -> "Sprawdź, czy jest nowa wersja"
        Updater.State.Checking -> "Sprawdzam…"
        Updater.State.UpToDate -> "Masz najnowszą wersję"
        is Updater.State.Available -> "Dostępna wersja ${s.name} – dotknij, aby zainstalować"
        is Updater.State.Downloading -> "Pobieranie ${(s.progress * 100).roundToInt()}%"
        is Updater.State.Error -> s.msg
    }
    SettingItem(Icons.Rounded.SystemUpdate, "Aktualizacja", sub, highlight = st is Updater.State.Available) {
        if (st is Updater.State.Available) Updater.downloadAndInstall(ctx) else Updater.check(silent = false)
    }
    (st as? Updater.State.Downloading)?.let {
        LinearProgressIndicator(progress = { it.progress }, modifier = Modifier.fillMaxWidth(), color = C.Accent)
    }
}

@Composable
private fun SettingItem(icon: ImageVector, title: String, sub: String, highlight: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (highlight) C.Accent.copy(alpha = 0.14f) else C.Card)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, null, tint = C.Accent, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = C.Text)
            Text(sub, fontSize = 13.sp, color = C.Muted)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = C.Muted, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun Card(border: Color = C.Border, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(C.Card).border(1.dp, border, RoundedCornerShape(22.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
private fun Label(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), fontSize = 13.sp, letterSpacing = 1.5.sp, color = C.Muted, fontWeight = FontWeight.SemiBold, modifier = modifier)
}

@Composable
private fun PrimaryButton(text: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(18.dp)).background(C.Accent).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = C.Bg, modifier = Modifier.size(26.dp))
        Spacer(Modifier.size(10.dp))
        Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = C.Bg)
    }
}

@Composable
private fun SecondaryButton(text: String, icon: ImageVector, modifier: Modifier = Modifier, h: Dp = 54.dp, onClick: () -> Unit) {
    Row(
        modifier.height(h).clip(RoundedCornerShape(16.dp)).background(C.Raised).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = C.Text, modifier = Modifier.size(22.dp))
        Spacer(Modifier.size(8.dp))
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = C.Text)
    }
}

// ---------- mapa ----------

@Composable
private fun ParkMap(s: Store.Spot, modifier: Modifier) {
    val ctx = LocalContext.current
    val holder = remember {
        val map = MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            isTilesScaledToDpi = true
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            overlayManager.tilesOverlay.setColorFilter(TilesOverlay.INVERT_COLORS)
            overlayManager.tilesOverlay.setLoadingBackgroundColor(android.graphics.Color.rgb(17, 22, 28))
            controller.setZoom(17.5)
        }
        val me = MyLocationNewOverlay(GpsMyLocationProvider(ctx), map)
        try { me.enableMyLocation() } catch (_: Exception) {}
        val marker = Marker(map).apply {
            icon = BitmapDrawable(ctx.resources, carPin())
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = "Moje auto"
        }
        map.overlays.add(me)
        map.overlays.add(marker)
        Triple(map, marker, me)
    }
    LaunchedEffect(s.lat, s.lon) {
        val p = GeoPoint(s.lat, s.lon)
        holder.second.position = p
        holder.first.controller.setCenter(p)
        holder.first.invalidate()
    }
    DisposableEffect(Unit) {
        holder.first.onResume()
        onDispose {
            holder.third.disableMyLocation()
            holder.first.onPause()
            holder.first.onDetach()
        }
    }
    Box(modifier) {
        AndroidView(factory = { holder.first }, modifier = Modifier.fillMaxSize())
        Box(
            Modifier.align(Alignment.BottomEnd).padding(12.dp).size(44.dp).clip(CircleShape).background(Color(0xE60A0C0F))
                .clickable { holder.first.controller.animateTo(GeoPoint(s.lat, s.lon)) },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.DirectionsCar, "Pokaż auto", tint = C.Text, modifier = Modifier.size(22.dp)) }
        Box(
            Modifier.align(Alignment.BottomStart).padding(12.dp).size(44.dp).clip(CircleShape).background(Color(0xE60A0C0F))
                .clickable { holder.third.myLocation?.let { holder.first.controller.animateTo(it) } },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.MyLocation, "Moja pozycja", tint = C.Text, modifier = Modifier.size(22.dp)) }
    }
}

/** Pinezka „P” w kolorze CAR OS. */
private fun carPin(): Bitmap {
    val w = 84
    val h = 104
    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(b)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = android.graphics.Color.rgb(47, 211, 240)
    val path = android.graphics.Path().apply {
        moveTo(w / 2f, h.toFloat())
        lineTo(w / 2f - 16, 70f)
        lineTo(w / 2f + 16, 70f)
        close()
    }
    c.drawPath(path, p)
    c.drawCircle(w / 2f, 42f, 40f, p)
    p.color = android.graphics.Color.rgb(10, 12, 15)
    c.drawCircle(w / 2f, 42f, 32f, p)
    p.color = android.graphics.Color.WHITE
    p.textSize = 42f
    p.isFakeBoldText = true
    p.textAlign = Paint.Align.CENTER
    c.drawText("P", w / 2f, 57f, p)
    return b
}

// ---------- pozycja i kompas ----------

@SuppressLint("MissingPermission")
@Composable
private fun rememberPhoneLocation(enabled: Boolean): Location? {
    val ctx = LocalContext.current
    var loc by remember { mutableStateOf<Location?>(null) }
    DisposableEffect(enabled) {
        if (!enabled) return@DisposableEffect onDispose {}
        val lm = ctx.getSystemService(LocationManager::class.java)
        loc = Locator.lastKnown(ctx)
        val l = object : LocationListener {
            override fun onLocationChanged(l: Location) {
                val prev = loc
                if (prev == null || l.provider == LocationManager.GPS_PROVIDER || l.time - prev.time > 15_000) loc = l
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try { lm?.requestLocationUpdates(p, 1000L, 0f, l, Looper.getMainLooper()) } catch (_: Exception) {}
        }
        onDispose { try { lm?.removeUpdates(l) } catch (_: Exception) {} }
    }
    return loc
}

/** Azymut telefonu (stopnie od północy geograficznej) z czujnika obrotu. */
@Composable
private fun rememberAzimuth(me: Location?): Float? {
    val ctx = LocalContext.current
    var az by remember { mutableStateOf<Float?>(null) }
    var smooth by remember { mutableFloatStateOf(Float.NaN) }
    val declination = remember(me?.latitude?.toInt(), me?.longitude?.toInt()) {
        me?.let { GeomagneticField(it.latitude.toFloat(), it.longitude.toFloat(), it.altitude.toFloat(), System.currentTimeMillis()).declination } ?: 0f
    }
    DisposableEffect(declination) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val rot = FloatArray(9)
        val ori = FloatArray(3)
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rot, e.values)
                SensorManager.getOrientation(rot, ori)
                val deg = (Math.toDegrees(ori[0].toDouble()).toFloat() + declination + 360f) % 360f
                // wygładzanie z obsługą przejścia 359° → 0°
                smooth = if (smooth.isNaN()) deg else {
                    var d = deg - smooth
                    if (d > 180) d -= 360
                    if (d < -180) d += 360
                    (smooth + d * 0.15f + 360f) % 360f
                }
                az = smooth
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (sensor != null) sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(l) }
    }
    return az
}
