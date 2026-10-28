package pl.mati.carlauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.LocalParking
import androidx.compose.material.icons.rounded.Map
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

@Composable
fun NavScreen() {
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

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        MapCard(Modifier.weight(1f).fillMaxHeight())
        Column(Modifier.width(400.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CarTextField(
                    query, { query = it }, "Dokąd jedziemy?", Modifier.weight(1f),
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

            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (results.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Caption("Wyniki", Modifier.weight(1f))
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).clickable { RouteRepository.clearResults(); query = "" },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Rounded.Close, "Wyczyść", tint = CarColors.Muted, modifier = Modifier.size(22.dp)) }
                    }
                    val loc = LocationRepository.location.value
                    results.forEach { r ->
                        val km = loc?.let { RouteRepository.dist(it.latitude, it.longitude, r.lat, r.lon) }
                        Dest(Icons.Rounded.Place, r.name, listOfNotNull(km?.let { formatDistance(it) }, r.detail.ifBlank { null }).joinToString(" · ")) {
                            RouteRepository.navigate(ctx, r)
                            UiState.go(Screen.HOME)
                        }
                    }
                } else {
                    Caption("Ulubione", Modifier.padding(top = 4.dp))
                    Dest(Icons.Rounded.Home, "Dom", p.home.ifBlank { "Dodaj adres" }) {
                        if (p.home.isBlank()) UiState.openSetup(4) else { RouteRepository.navigateToAddress(ctx, p.home, "Dom"); UiState.go(Screen.HOME) }
                    }
                    Dest(Icons.Rounded.Work, "Praca", p.work.ifBlank { "Dodaj adres" }) {
                        if (p.work.isBlank()) UiState.openSetup(4) else { RouteRepository.navigateToAddress(ctx, p.work, "Praca"); UiState.go(Screen.HOME) }
                    }
                    Caption("W pobliżu", Modifier.padding(top = 4.dp))
                    Dest(Icons.Rounded.LocalGasStation, "Stacje paliw", "Pokaż najbliższe") { query = "stacja paliw"; doSearch("stacja paliw", nearby = true) }
                    Dest(Icons.Rounded.LocalParking, "Parking", "Pokaż najbliższe") { query = "parking"; doSearch("parking", nearby = true) }
                }
            }
            if (route != null) {
                SecondaryButton("Zakończ trasę", { RouteRepository.stop() }, Modifier.fillMaxWidth(), icon = Icons.Rounded.Close)
            } else {
                SecondaryButton("Otwórz Mapy Google", { Launch.navigation(ctx) }, Modifier.fillMaxWidth(), icon = Icons.Rounded.Map)
            }
        }
    }
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
