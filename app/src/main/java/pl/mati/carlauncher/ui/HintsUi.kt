package pl.mati.carlauncher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.Work
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.mati.carlauncher.data.DestinationHints
import pl.mati.carlauncher.data.ForecastRepository
import pl.mati.carlauncher.data.Prefs
import pl.mati.carlauncher.data.RouteRepository
import pl.mati.carlauncher.data.TireReminder
import java.text.SimpleDateFormat
import java.util.Date

/** „Jedziesz do: Praca · zwykle 18 min” – z Dziennika tras, po zapłonie. */
@Composable
fun DestinationBanner() {
    val h by DestinationHints.hint.collectAsState()
    val route by RouteRepository.route.collectAsState()
    val ctx = LocalContext.current
    val accent = LocalAccent.current
    var last by remember { mutableStateOf<DestinationHints.Hint?>(null) }
    h?.let { last = it }
    val show = h != null && route == null
    Box(Modifier.fillMaxSize().padding(start = CarColors.theme.insetStart, end = CarColors.theme.insetEnd, bottom = CarColors.theme.insetBottom), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(show, enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it }) {
            val s = last ?: return@AnimatedVisibility
            val shape = RoundedCornerShape(24.dp)
            Row(
                Modifier.clip(shape).carSurface(shape, CarColors.Panel, heavy = true)
                    .border(1.dp, accent.copy(alpha = 0.5f), shape)
                    .padding(horizontal = 22.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                IconBadge(
                    when (s.kind) {
                        DestinationHints.Kind.HOME -> Icons.Rounded.Home
                        DestinationHints.Kind.WORK -> Icons.Rounded.Work
                        DestinationHints.Kind.PLACE -> Icons.Rounded.Place
                    },
                    56.dp,
                )
                Column(Modifier.widthIn(max = 520.dp)) {
                    Text("Jedziesz do: ${s.name}?", fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(s.detail, fontSize = 16.sp, color = CarColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                PrimaryButton("Prowadź", {
                    DestinationHints.dismiss()
                    RouteRepository.quickStartPlace(ctx, RouteRepository.Place(s.name, s.detail, s.lat, s.lon))
                    Toaster.show("Wyznaczam trasę: ${s.name}…")
                })
                SecondaryButton("Nie", { DestinationHints.dismiss() })
            }
        }
    }
}

/** Przypomnienie o zmianie opon (z prognozy). */
@Composable
fun TireBanner() {
    val r by TireReminder.reminder.collectAsState()
    var last by remember { mutableStateOf<TireReminder.Reminder?>(null) }
    r?.let { last = it }
    Box(Modifier.fillMaxSize().padding(start = CarColors.theme.insetStart, end = CarColors.theme.insetEnd, bottom = CarColors.theme.insetBottom), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(r != null, enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it }) {
            val s = last ?: return@AnimatedVisibility
            val shape = RoundedCornerShape(24.dp)
            val tint = if (s.toWinter) androidx.compose.ui.graphics.Color(0xFF5AB4FF) else CarColors.Orange
            Row(
                Modifier.clip(shape).carSurface(shape, CarColors.Panel, heavy = true)
                    .border(1.dp, tint.copy(alpha = 0.6f), shape)
                    .padding(horizontal = 22.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                IconBadge(if (s.toWinter) Icons.Rounded.AcUnit else Icons.Rounded.WbSunny, 56.dp)
                Column(Modifier.widthIn(max = 470.dp)) {
                    Text(s.title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(s.text, fontSize = 15.sp, color = CarColors.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (s.toWinter) {
                    PrimaryButton("Mam zimowe", { TireReminder.changed("winter") })
                    if (s.askType) SecondaryButton("Całoroczne", { TireReminder.changed("all") })
                } else {
                    PrimaryButton("Mam letnie", { TireReminder.changed("summer") })
                }
                SecondaryButton("Jutro", { TireReminder.later() })
            }
        }
    }
}

/** Karta „Opony” w Serwisie: jakie są założone i co mówi prognoza. */
@Composable
fun TireCard(modifier: Modifier = Modifier) {
    val type by Prefs.tireType.collectAsState()
    val changed by Prefs.tireChanged.collectAsState()
    val f by ForecastRepository.forecast.collectAsState()
    val r = remember(f, type, changed) { TireReminder.evaluate(f) }
    CarCard(modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 22.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            IconBadge(Icons.Rounded.Album, 56.dp)
            Column(Modifier.weight(1f)) {
                Caption("Opony")
                Text(
                    "Założone: " + TireReminder.label(type) +
                        (if (changed > 0 && type.isNotEmpty()) " (od " + SimpleDateFormat("d.MM.yyyy", PL).format(Date(changed)) + ")" else ""),
                    fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                )
                Text(
                    r?.title?.let { "$it – " + r.text } ?: "Prognoza na najbliższe dni nie wymaga zmiany. Przypomnę, gdy średnia temperatura spadnie poniżej 7 °C.",
                    fontSize = 15.sp, color = if (r != null) CarColors.Orange else CarColors.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((k, l) in listOf("summer" to "Letnie", "winter" to "Zimowe", "all" to "Całoroczne")) {
                    if (k == type) PrimaryButton(l, { }) else SecondaryButton(l, { TireReminder.changed(k) }, height = 56.dp)
                }
            }
        }
    }
}
