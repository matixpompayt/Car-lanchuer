package pl.mati.carlauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.WbCloudy
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.mati.carlauncher.data.ForecastRepository
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.roundToInt

private val WEEKDAY = SimpleDateFormat("EEE", PL)

fun weatherText(code: Int): String = when (code) {
    0 -> "Bezchmurnie"
    1 -> "Przeważnie słonecznie"
    2 -> "Częściowe zachmurzenie"
    3 -> "Pochmurno"
    45, 48 -> "Mgła"
    in 51..57 -> "Mżawka"
    in 61..67 -> "Deszcz"
    in 71..77 -> "Śnieg"
    in 80..82 -> "Przelotny deszcz"
    85, 86 -> "Przelotny śnieg"
    in 95..99 -> "Burza"
    else -> "—"
}

fun weatherIcon(code: Int): ImageVector = when (code) {
    0, 1 -> Icons.Rounded.WbSunny
    2 -> Icons.Rounded.WbCloudy
    3, 45, 48 -> Icons.Rounded.Cloud
    in 51..67, in 80..82 -> Icons.Rounded.WaterDrop
    in 71..77, 85, 86 -> Icons.Rounded.AcUnit
    in 95..99 -> Icons.Rounded.Bolt
    else -> Icons.Rounded.Cloud
}

@Composable
fun WeatherScreen() {
    val f by ForecastRepository.forecast.collectAsState()
    val err by ForecastRepository.error.collectAsState()
    val rw by ForecastRepository.routeWeather.collectAsState()
    val place by ForecastRepository.place.collectAsState()
    val accent = LocalAccent.current
    LaunchedEffect(Unit) { if (f == null || System.currentTimeMillis() - (f?.fetched ?: 0) > 15 * 60_000) ForecastRepository.refresh() }

    val fc = f
    if (fc == null) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
            if (err != null) {
                Text(err ?: "", fontSize = 20.sp, color = CarColors.Orange)
                SecondaryButton("Spróbuj ponownie", { ForecastRepository.refresh() }, icon = Icons.Rounded.Refresh)
            } else {
                Spinner(40.dp)
                Text("Pobieram prognozę… (potrzebna pozycja GPS)", fontSize = 19.sp, color = CarColors.Muted)
            }
        }
        return
    }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(Modifier.width(420.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            CarCard(Modifier.fillMaxWidth().weight(1f)) {
                Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.LocationOn, null, tint = accent, modifier = Modifier.size(26.dp))
                        Text(place ?: "Twoja okolica", fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                    Caption("Teraz")
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        Icon(weatherIcon(fc.code), null, tint = accent, modifier = Modifier.size(84.dp))
                        Text("${fc.temp.roundToInt()}°", fontSize = 96.sp, fontWeight = FontWeight.Bold, fontFamily = Condensed)
                    }
                    Text(weatherText(fc.code), fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.Air, null, tint = CarColors.Muted, modifier = Modifier.size(22.dp))
                        Text("Wiatr ${fc.wind.roundToInt()} km/h", fontSize = 18.sp, color = CarColors.Muted)
                    }
                    Text("Aktualizacja ${formatTime(fc.fetched)}", fontSize = 15.sp, color = CarColors.Dim)
                }
            }
            rw?.let { r ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                        .background(if (r.rain) Color(0xFF15324A) else CarColors.Card).padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Icon(if (r.rain) weatherIcon(r.code) else Icons.Rounded.WbSunny, null, tint = if (r.rain) Color(0xFF6CC4FF) else CarColors.Green, modifier = Modifier.size(32.dp))
                    Column {
                        Caption("Na trasie")
                        Text(r.text + (r.atTime?.let { " (ok. ${formatTime(it)})" } ?: ""), fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            CarCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Caption("Najbliższe godziny")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        fc.hours.take(10).forEach { h ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(formatTime(h.time), fontSize = 15.sp, color = CarColors.Muted)
                                Icon(weatherIcon(h.code), null, tint = CarColors.Text2, modifier = Modifier.size(28.dp))
                                Text("${h.temp.roundToInt()}°", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                                Text(if (h.prob > 0) "${h.prob}%" else " ", fontSize = 14.sp, color = Color(0xFF6CC4FF))
                            }
                        }
                    }
                }
            }
            CarCard(Modifier.fillMaxWidth().weight(1f)) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Caption("Kolejne dni")
                    fc.days.take(6).forEachIndexed { i, d ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Text(if (i == 0) "Dziś" else WEEKDAY.format(Date(d.time)).replaceFirstChar { it.uppercase() }, fontSize = 19.sp, modifier = Modifier.width(70.dp))
                            Icon(weatherIcon(d.code), null, tint = CarColors.Text2, modifier = Modifier.size(26.dp))
                            Text(weatherText(d.code), fontSize = 17.sp, color = CarColors.Muted, modifier = Modifier.weight(1f))
                            if (d.precipSum >= 0.1) Text("${d.precipSum.toFloat().pl1()} mm", fontSize = 16.sp, color = Color(0xFF6CC4FF))
                            Text("${d.min.roundToInt()}° / ${d.max.roundToInt()}°", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}
