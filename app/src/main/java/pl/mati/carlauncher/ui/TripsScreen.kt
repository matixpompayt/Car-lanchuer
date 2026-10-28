package pl.mati.carlauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DirectionsCar
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.mati.carlauncher.data.TripRepository
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.roundToInt

private val DAY_FMT = SimpleDateFormat("EEEE, d MMMM", PL)

fun formatDurationLong(s: Long): String {
    val h = s / 3600
    val m = (s % 3600) / 60
    return if (h > 0) "$h h $m min" else "$m min"
}

@Composable
fun TripsScreen() {
    val trips by TripRepository.trips.collectAsState()
    val current by TripRepository.current.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Dziennik tras", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (trips.isNotEmpty()) {
                if (confirmClear) {
                    SecondaryButton("Na pewno usunąć?", { TripRepository.clearAll(); confirmClear = false }, height = 52.dp)
                } else {
                    SecondaryButton("Wyczyść", { confirmClear = true }, height = 52.dp, icon = Icons.Rounded.DeleteOutline)
                }
            }
        }
        // trigger przeliczenia podsumowań przy zmianach
        val key = trips.size to current?.distanceM?.toInt()
        val day = remember(key) { TripRepository.summary(TripRepository.startOfDay()) }
        val week = remember(key) { TripRepository.summary(TripRepository.startOfWeek()) }
        val month = remember(key) { TripRepository.summary(TripRepository.startOfMonth()) }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            SummaryCard("Dziś", day, Modifier.weight(1f))
            SummaryCard("Ten tydzień", week, Modifier.weight(1f))
            SummaryCard("Ten miesiąc", month, Modifier.weight(1f))
        }

        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            current?.let { c ->
                item { TripRow(c, live = true) }
            }
            if (trips.isEmpty() && current == null) {
                item {
                    Text(
                        "Brak zapisanych jazd. Jazda zapisze się sama po ruszeniu (GPS albo OBD) i zakończy po 3 minutach postoju.",
                        fontSize = 18.sp, color = CarColors.Muted, modifier = Modifier.padding(vertical = 20.dp),
                    )
                }
            }
            var lastDay = ""
            trips.forEach { t ->
                val d = DAY_FMT.format(Date(t.start)).replaceFirstChar { it.uppercase() }
                if (d != lastDay) {
                    lastDay = d
                    item(key = "h${t.start}") { SectionLabel(d) }
                }
                item(key = t.start) { TripRow(t, live = false) }
            }
        }
    }
}

@Composable
private fun SummaryCard(label: String, s: TripRepository.Summary, modifier: Modifier) {
    CarCard(modifier) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Caption(label)
            ValueText((s.distanceM / 1000).toFloat().pl1(), "km", 40.sp)
            Text("${s.count} jazd · ${formatDurationLong(s.durationS)}", fontSize = 15.sp, color = CarColors.Muted)
        }
    }
}

@Composable
private fun TripRow(t: TripRepository.Trip, live: Boolean) {
    val accent = LocalAccent.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(CarColors.Card).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(if (live) accent else accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.DirectionsCar, null, tint = if (live) CarColors.Bg else accent, modifier = Modifier.size(26.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                (if (live) "Teraz · " else "") + "${formatTime(t.start)} – ${if (live) "…" else formatTime(t.end)}",
                fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
            )
            Text(
                "${formatDurationLong(t.durationS)} · śr. ${t.avgKmh.roundToInt()} km/h · maks. ${t.maxSpeed} km/h" +
                    (t.maxCoolant?.let { " · płyn maks. $it °C" } ?: ""),
                fontSize = 15.sp, color = CarColors.Muted,
            )
        }
        Text("${(t.distanceM / 1000).toFloat().pl1()} km", fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = Condensed, color = if (live) accent else Color.Unspecified)
    }
    if (live) Box(Modifier.height(2.dp))
}
