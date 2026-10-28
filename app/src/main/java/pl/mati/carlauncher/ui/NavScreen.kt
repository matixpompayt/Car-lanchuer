package pl.mati.carlauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddLocationAlt
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.LocalParking
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.mati.carlauncher.data.LocationRepository
import pl.mati.carlauncher.data.Prefs
import pl.mati.carlauncher.data.RouteRepository
import kotlin.math.roundToInt

@Composable
fun NavScreen() {
    val plan by RouteRepository.plan.collectAsState()
    var addingStop by remember { mutableStateOf(false) }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        MapCard(Modifier.weight(1f).fillMaxHeight())
        Column(Modifier.width(410.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val p = plan
            if (p != null && !addingStop) {
                PlannerPanel(p, onAddStop = { addingStop = true })
            } else {
                SearchPanel(
                    addingStop = addingStop && p != null,
                    onPick = { place ->
                        if (addingStop && RouteRepository.plan.value != null) RouteRepository.addStop(place)
                        else RouteRepository.planTo(place)
                        addingStop = false
                    },
                    onCancelStop = { addingStop = false },
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.SearchPanel(addingStop: Boolean, onPick: (RouteRepository.Place) -> Unit, onCancelStop: () -> Unit) {
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    val p by Prefs.profile.collectAsState()
    val results by RouteRepository.results.collectAsState()
    val busy by RouteRepository.busy.collectAsState()
    val error by RouteRepository.error.collectAsState()
    val route by RouteRepository.route.collectAsState()
    var query by remember { mutableStateOf("") }

    fun doSearch(q: String, nearby: Boolean = false) {
        focus.clearFocus()
        RouteRepository.search(q, nearby)
    }

    if (addingStop) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Dodaj przystanek", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            CloseButton { RouteRepository.clearResults(); onCancelStop() }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CarTextField(
            query, { query = it }, if (addingStop) "Przez…" else "Dokąd jedziemy?", Modifier.weight(1f),
            imeAction = ImeAction.Search, onDone = { doSearch(query) },
        )
        Box(
            Modifier.size(64.dp).clip(RoundedCornerShape(18.dp)).background(LocalAccent.current).clickable { doSearch(query) },
            contentAlignment = Alignment.Center,
        ) {
            if (busy) Spinner(26.dp) else Icon(Icons.Rounded.Search, "Szukaj", tint = CarColors.Bg, modifier = Modifier.size(30.dp))
        }
    }
    error?.let { Text(it, fontSize = 16.sp, color = CarColors.Orange) }

    Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (results.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Caption("Wyniki", Modifier.weight(1f))
                CloseButton { RouteRepository.clearResults(); query = "" }
            }
            val loc = LocationRepository.location.value
            results.forEach { r ->
                val d = loc?.let { RouteRepository.dist(it.latitude, it.longitude, r.lat, r.lon) }
                Dest(Icons.Rounded.Place, r.name, listOfNotNull(d?.let { formatDistance(it) }, r.detail.ifBlank { null }).joinToString(" · ")) { onPick(r) }
            }
        } else {
            if (!addingStop) {
                Caption("Ulubione", Modifier.padding(top = 4.dp))
                Dest(Icons.Rounded.Home, "Dom", p.home.ifBlank { "Dodaj adres" }) {
                    if (p.home.isBlank()) UiState.openSetup(4) else RouteRepository.planToAddress(p.home, "Dom")
                }
                Dest(Icons.Rounded.Work, "Praca", p.work.ifBlank { "Dodaj adres" }) {
                    if (p.work.isBlank()) UiState.openSetup(4) else RouteRepository.planToAddress(p.work, "Praca")
                }
            }
            Caption("W pobliżu", Modifier.padding(top = 4.dp))
            Dest(Icons.Rounded.LocalGasStation, "Stacje paliw", "Pokaż najbliższe") { query = "stacja paliw"; doSearch("stacja paliw", nearby = true) }
            Dest(Icons.Rounded.LocalParking, "Parking", "Pokaż najbliższe") { query = "parking"; doSearch("parking", nearby = true) }
        }
    }
    if (!addingStop) {
        if (route != null) SecondaryButton("Zakończ trasę", { RouteRepository.stop() }, Modifier.fillMaxWidth(), icon = Icons.Rounded.Close)
        else SecondaryButton("Otwórz Mapy Google", { Launch.navigation(ctx) }, Modifier.fillMaxWidth(), icon = Icons.Rounded.Map)
    }
}

@Composable
private fun ColumnScope.PlannerPanel(p: RouteRepository.Plan, onAddStop: () -> Unit) {
    val ctx = LocalContext.current
    val accent = LocalAccent.current
    val bg by Prefs.mapsInBackground.collectAsState()
    var canReturn by remember { mutableStateOf(RouteRepository.canReturn(ctx)) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Planowanie trasy", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        CloseButton { RouteRepository.cancelPlan() }
    }

    Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // przebieg: start → przystanki → cel
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(CarColors.Card).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Waypoint(Icons.Rounded.MyLocation, "Twoja pozycja", null)
            p.stops.forEachIndexed { i, s -> Waypoint(Icons.Rounded.Place, s.name, onRemove = { RouteRepository.removeStop(i) }) }
            Waypoint(Icons.Rounded.Flag, p.dest.name, null, bold = true)
        }
        if (p.stops.size < 3) {
            Row(
                Modifier.clip(RoundedCornerShape(14.dp)).clickable { RouteRepository.clearResults(); onAddStop() }.padding(vertical = 8.dp, horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Rounded.AddLocationAlt, null, tint = accent, modifier = Modifier.size(24.dp))
                Text("Dodaj przystanek (przez…)", fontSize = 18.sp, color = accent, fontWeight = FontWeight.SemiBold)
            }
        }

        Caption("Warianty trasy", Modifier.padding(top = 4.dp))
        when {
            p.computing -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 10.dp)) {
                Spinner(); Text(p.error ?: "Wyznaczam trasy…", fontSize = 18.sp, color = CarColors.Text2)
            }
            p.error != null -> Text(p.error, fontSize = 17.sp, color = CarColors.Orange)
            else -> {
                val fastest = p.alternatives.minOf { it.durationS }
                p.alternatives.forEachIndexed { i, r ->
                    val sel = i == p.selected
                    val shape = RoundedCornerShape(18.dp)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .background(if (sel) accent.copy(alpha = 0.14f) else CarColors.Card)
                            .border(2.dp, if (sel) accent else Color.Transparent, shape)
                            .clickable { RouteRepository.select(i) }
                            .padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("${(r.durationS / 60).roundToInt()} min", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = if (sel) accent else CarColors.Text)
                            val diff = ((r.durationS - fastest) / 60).roundToInt()
                            Text(
                                formatDistance(r.distanceM) + " · przyjazd " + formatEta(r.durationS) + (if (diff > 0) " · +$diff min" else " · najszybsza"),
                                fontSize = 15.sp, color = CarColors.Muted,
                            )
                        }
                        Text("Trasa ${i + 1}", fontSize = 16.sp, color = if (sel) accent else CarColors.Muted)
                    }
                }
            }
        }

        SettingRow("Mapy Google w tle", "Głos prowadzi w tle, CAR OS wraca na ekran", onClick = { Prefs.setMapsInBackground(!bg) }) { CarSwitch(bg) }
        if (bg && !canReturn) {
            SettingRow("Zezwól na powrót do CAR OS", "Wymagane: „Wyświetlanie nad innymi aplikacjami”", onClick = {
                RouteRepository.requestReturnPermission(ctx); canReturn = RouteRepository.canReturn(ctx)
            }) { Text("Zezwól", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = accent) }
        }
    }

    PrimaryButton(
        "Jedź",
        {
            canReturn = RouteRepository.canReturn(ctx)
            if (bg && !canReturn) Toaster.show("Bez zgody „nad innymi aplikacjami” wrócisz do CAR OS przyciskiem HOME")
            RouteRepository.start(ctx)
            UiState.go(Screen.HOME)
        },
        Modifier.fillMaxWidth(),
        enabled = !p.computing && p.chosen != null,
        icon = Icons.Rounded.Navigation,
    )
}

@Composable
private fun Waypoint(icon: ImageVector, name: String, onRemove: (() -> Unit)?, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, tint = if (bold) LocalAccent.current else CarColors.Muted, modifier = Modifier.size(22.dp))
        Text(name, fontSize = 18.sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (onRemove != null) CloseButton(onRemove)
    }
}

@Composable
private fun CloseButton(onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Rounded.Close, "Zamknij", tint = CarColors.Muted, modifier = Modifier.size(24.dp)) }
}

@Composable
private fun Dest(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(CarColors.Card)
            .border(1.dp, CarColors.Border, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IconBadge(icon, 48.dp)
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotBlank()) Text(sub, fontSize = 15.sp, color = CarColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = CarColors.Muted, modifier = Modifier.size(24.dp))
    }
}
