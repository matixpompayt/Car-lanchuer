package pl.mati.carlauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.LocalParking
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.mati.carlauncher.data.Prefs

@Composable
fun NavScreen() {
    val ctx = LocalContext.current
    val p by Prefs.profile.collectAsState()
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        MapCard(Modifier.weight(1f).fillMaxHeight())
        Column(Modifier.width(380.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(CarColors.Card)
                    .border(1.dp, CarColors.Border, RoundedCornerShape(28.dp))
                    .clickable { Launch.mapsSearch(ctx, "") }
                    .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Rounded.Search, null, tint = CarColors.Muted, modifier = Modifier.size(24.dp))
                Text("Dokąd jedziemy?", fontSize = 18.sp, color = CarColors.Muted, modifier = Modifier.weight(1f))
                Icon(Icons.Rounded.Mic, null, tint = CarColors.Muted, modifier = Modifier.size(24.dp))
            }
            Caption("Ulubione", Modifier.padding(top = 6.dp))
            Dest(Icons.Rounded.Home, "Dom", p.home.ifBlank { "Dodaj adres" }) {
                if (p.home.isBlank()) UiState.openSetup(4) else Launch.navigateTo(ctx, p.home)
            }
            Dest(Icons.Rounded.Work, "Praca", p.work.ifBlank { "Dodaj adres" }) {
                if (p.work.isBlank()) UiState.openSetup(4) else Launch.navigateTo(ctx, p.work)
            }
            Dest(Icons.Rounded.LocalGasStation, "Stacje paliw", "w pobliżu") { Launch.mapsSearch(ctx, "stacja paliw") }
            Dest(Icons.Rounded.LocalParking, "Parking", "w pobliżu") { Launch.mapsSearch(ctx, "parking") }
            Spacer(Modifier.weight(1f))
            SecondaryButton("Otwórz Mapy Google", { Launch.navigation(ctx) }, Modifier.fillMaxWidth(), icon = Icons.Rounded.Map)
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
            Text(title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, fontSize = 15.sp, color = CarColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = CarColors.Muted, modifier = Modifier.size(24.dp))
    }
}
