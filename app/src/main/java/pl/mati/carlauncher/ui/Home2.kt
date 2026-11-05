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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.mati.carlauncher.data.LocationRepository
import pl.mati.carlauncher.data.Prefs
import pl.mati.carlauncher.data.SpeedLimitRepository

/**
 * Wygląd 2.0 (test): jak w Tesli – po lewej duża prędkość, ograniczenie i dane bez ramek,
 * na dole muzyka; reszta ekranu to mapa.
 */
@Composable
fun HomeScreen2() {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(Modifier.width(360.dp).fillMaxHeight().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SpeedBlock()
            Box(Modifier.fillMaxWidth().height(1.dp).background(CarColors.Border))
            StatsGrid(Modifier.weight(1f).fillMaxWidth())
            MiniPlayer(Modifier.fillMaxWidth().height(88.dp))
        }
        MapCard(Modifier.weight(1f).fillMaxHeight(), showSpeed = false, radius = 30.dp)
    }
}

@Composable
private fun SpeedBlock() {
    val d by pl.mati.carlauncher.data.Vehicle.data.collectAsState()
    val gps by LocationRepository.gpsSpeed.collectAsState()
    val lim by SpeedLimitRepository.limit.collectAsState()
    val over by SpeedLimitRepository.over.collectAsState()
    val est by SpeedLimitRepository.estimated.collectAsState()
    val road by SpeedLimitRepository.roadName.collectAsState()
    val limitsOn by Prefs.speedLimits.collectAsState()
    val speed = d.speed ?: gps
    val red = Color(0xFFFF4D4F)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    speed?.toString() ?: "--", fontSize = 104.sp, fontFamily = Condensed, fontWeight = FontWeight.Bold,
                    color = if (over) red else CarColors.Text, lineHeight = 104.sp,
                )
                Text("km/h", fontSize = 22.sp, color = CarColors.Muted, modifier = Modifier.padding(start = 8.dp, bottom = 18.dp))
            }
            Text(
                road ?: "", fontSize = 16.sp, color = CarColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (limitsOn && lim != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(78.dp).clip(CircleShape).background(Color.White).border(9.dp, Color(0xFFE0262B), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(lim.toString(), color = Color(0xFF111111), fontWeight = FontWeight.Bold, fontSize = if (lim!! >= 100) 26.sp else 32.sp)
                }
                if (est) Text("wg przepisów", fontSize = 12.sp, color = CarColors.Muted, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** Kafle z ekranu głównego bez ramek, po dwa w rzędzie. */
@Composable
private fun StatsGrid(modifier: Modifier) {
    val keysStr by Prefs.homeTiles.collectAsState()
    val keys = keysStr.split(",").filter { k -> TILE_DEFS.any { it.first == k } }.distinct().take(MAX_TILES)
    CompositionLocalProvider(LocalMinimalTiles provides true) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (keys.isEmpty()) {
                Text(
                    "Dodaj dane w Ustawienia → Wygląd → Kafle ekranu głównego",
                    fontSize = 15.sp, color = CarColors.Muted,
                    modifier = Modifier.clickable { UiState.openSettings("look") },
                )
            }
            keys.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { k -> TileContent(k, Modifier.weight(1f), 34.sp) }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}
