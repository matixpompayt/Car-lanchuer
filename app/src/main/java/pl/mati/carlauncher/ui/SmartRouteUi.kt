package pl.mati.carlauncher.ui

import androidx.compose.animation.AnimatedVisibility
import pl.mati.caros.core.Countries
import pl.mati.caros.core.RoutePlan
import pl.mati.carlauncher.data.FuelPrices
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Coffee
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Traffic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import pl.mati.carlauncher.data.FuelRepository
import pl.mati.carlauncher.data.LocationRepository
import pl.mati.carlauncher.data.Prefs
import pl.mati.carlauncher.data.RouteRepository
import pl.mati.carlauncher.data.SmartRoute
import pl.mati.carlauncher.data.TrafficRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private fun durTxt(s: Int): String {
    val m = (s / 60.0).roundToInt()
    return if (m >= 60) "${m / 60} h ${"%02d".format(m % 60)} min" else "$m min"
}

private fun hhmm(t: Long) = SimpleDateFormat("HH:mm", Locale("pl")).format(Date(t))

/** Inteligentny plan w planowaniu trasy: omijanie korków, kolejność przystanków, godzina wyjazdu, tankowanie i przerwy. */
@Composable
fun SmartPlanCar(p: RouteRepository.Plan) {
    val accent = LocalAccent.current
    val scope = rememberCoroutineScope()
    val r = p.chosen ?: return
    val tPlan by TrafficRepository.plan.collectAsState()
    val stats by FuelRepository.stats.collectAsState()
    val range = stats.rangeKm
    val travelS = tPlan[r.id]?.travelS?.toDouble() ?: r.durationS
    var ordering by remember { mutableStateOf(false) }
    var slots by remember(r.id) { mutableStateOf<List<SmartRoute.Slot>?>(null) }
    var slotsBusy by remember(r.id) { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var plan by remember(r.id) { mutableStateOf<SmartRoute.Plan?>(null) }
    var stops by remember(r.id) { mutableStateOf<List<SmartRoute.Stop>?>(null) }
    // wybrana propozycja w każdym postoju (Inna → następna)
    val pick = remember(r.id) { androidx.compose.runtime.mutableStateMapOf<Int, Int>() }
    val fav by FuelPrices.fav.collectAsState()
    var stopsErr by remember(r.id) { mutableStateOf<String?>(null) }
    var stopsTry by remember(r.id) { mutableStateOf(0) }
    val reserve by Prefs.minFuelRange.collectAsState()
    val needStops = (range != null && range < r.distanceM / 1000 + reserve) || travelS > 2 * 3600 + 20 * 60
    LaunchedEffect(r.id, range?.let { (it / 20).roundToInt() }, reserve, stopsTry, fav) {
        stopsErr = null
        if (!needStops) { stops = emptyList(); return@LaunchedEffect }
        stops = null
        pick.clear()
        stops = try {
            SmartRoute.stops(ctx, r, travelS, range).also { plan = it }.stops
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            stopsErr = e.message ?: "błąd"
            emptyList()
        }
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).carSurface(RoundedCornerShape(18.dp), CarColors.Card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Rounded.AutoAwesome, null, tint = accent, modifier = Modifier.size(24.dp))
            Text("Inteligentny plan", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        // 1) omijanie korków
        if (TrafficRepository.enabled) {
            val times = p.alternatives.map { tPlan[it.id]?.travelS?.toDouble() ?: it.durationS }
            val worst = times.maxOrNull() ?: travelS
            val closedOther = p.alternatives.any { a -> a.id != r.id && tPlan[a.id]?.jams?.any { it.kind == "ROAD_CLOSURE" } == true }
            SmartLine(
                Icons.Rounded.Traffic, CarColors.Green,
                when {
                    closedOther -> "Wybrana trasa omija zamkniętą drogę"
                    p.alternatives.size > 1 && worst - travelS >= 60 -> "Najszybsza z korkami – do ${durTxt((worst - travelS).toInt())} szybciej niż inne"
                    else -> "Trasa sprawdzona z korkami na żywo; w czasie jazdy zaproponuję szybszą, jeśli coś się zakorkuje"
                },
            )
        }
        // 2) kolejność przystanków
        if (p.stops.size >= 2) {
            SmartBtn(Icons.Rounded.SwapVert, if (ordering) "Układam…" else "Ułóż przystanki w najlepszej kolejności", ordering) {
                val l = LocationRepository.location.value ?: return@SmartBtn
                ordering = true
                scope.launch {
                    val res = SmartRoute.optimizeStops(GeoPoint(l.latitude, l.longitude), p.stops, p.dest)
                    ordering = false
                    when {
                        res == null -> Toaster.show("Nie udało się ułożyć – sprawdź internet")
                        res.first == p.stops || res.second < 30 -> Toaster.show("Obecna kolejność jest najlepsza")
                        else -> {
                            RouteRepository.setStops(res.first)
                            Toaster.show("Nowa kolejność – szybciej o ${durTxt(res.second)}")
                        }
                    }
                }
            }
        }
        // 3) najlepsza godzina wyjazdu
        val sl = slots
        if (sl == null) {
            if (TrafficRepository.enabled) SmartBtn(Icons.Rounded.Schedule, if (slotsBusy) "Sprawdzam korki w najbliższych godzinach…" else "Kiedy najlepiej wyjechać?", slotsBusy) {
                slotsBusy = true
                scope.launch {
                    slots = SmartRoute.departures(r)
                    slotsBusy = false
                }
            }
        } else if (sl.isNotEmpty()) {
            val best = sl.minBy { it.travelS }
            val now = sl.first()
            Text(
                if (best.depart == now.depart || now.travelS - best.travelS < 120) "Teraz jest dobrze – później nie będzie wyraźnie szybciej"
                else "Najlepiej wyjechać o ${hhmm(best.depart)}: ${durTxt(best.travelS)} (−${durTxt(now.travelS - best.travelS)})",
                fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            )
            val mx = sl.maxOf { it.travelS }.toFloat()
            val mn = sl.minOf { it.travelS }.toFloat()
            Row(Modifier.fillMaxWidth().height(80.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
                sl.forEach { s ->
                    val f = if (mx - mn < 1f) 0.6f else 0.3f + 0.7f * (s.travelS - mn) / (mx - mn)
                    Box(Modifier.weight(1f).fillMaxHeight(f).clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp)).background(if (s == best) CarColors.Green else accent.copy(alpha = 0.45f)))
                }
            }
            Row(Modifier.fillMaxWidth()) {
                sl.forEachIndexed { i, s -> Text(if (i % 4 == 0) hhmm(s.depart) else "", fontSize = 13.sp, color = CarColors.Muted, maxLines = 1, softWrap = false, modifier = Modifier.weight(1f)) }
            }
        } else {
            Text("Brak prognozy ruchu na najbliższe godziny", fontSize = 15.sp, color = CarColors.Muted)
        }
        // 4) tankowanie i przerwy
        val st = stops
        when {
            !needStops -> SmartLine(Icons.Rounded.LocalGasStation, CarColors.Muted, if (range != null) "Zasięg ${range.roundToInt()} km wystarczy, przerwa niepotrzebna" else "Krótka trasa – bez postojów")
            st == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Spinner(20.dp); Text("Szukam stacji i miejsc na przerwę…", fontSize = 16.sp, color = CarColors.Muted)
            }
            st.isEmpty() && stopsErr != null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.LocalGasStation, null, tint = CarColors.Orange, modifier = Modifier.size(24.dp))
                Text("Nie udało się pobrać stacji – serwery map nie odpowiadają ($stopsErr)", fontSize = 16.sp, color = CarColors.Text2, modifier = Modifier.weight(1f))
                Box(
                    Modifier.clip(RoundedCornerShape(12.dp)).background(accent.copy(alpha = 0.18f)).clickable { stopsTry++ }.padding(horizontal = 14.dp, vertical = 8.dp),
                ) { Text("Ponów", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = accent) }
            }
            st.isEmpty() -> SmartLine(Icons.Rounded.LocalGasStation, CarColors.Muted, "Brak stacji i MOP-ów blisko trasy w miejscach, gdzie wypada postój")
            else -> {
                val res = plan?.res
                st.forEachIndexed { si, s ->
                    val k = (pick[si] ?: 0).coerceIn(0, s.options.size - 1)
                    val o = s.options[k]
                    val fuelStop = s.kind == "fuel"
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(if (fuelStop) Icons.Rounded.LocalGasStation else Icons.Rounded.Coffee, null, tint = if (fuelStop) CarColors.Orange else accent, modifier = Modifier.size(26.dp))
                        Column(Modifier.weight(1f)) {
                            Text(o.place.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val head = if (fuelStop) (if (st.count { it.kind == "fuel" } > 1) "Tankowanie ${s.n}" else "Tankowanie") else "Przerwa po ok. ${s.hours} h"
                            Text("$head · ${o.atKm.roundToInt()} km · ok. ${hhmm(o.atTime)}", fontSize = 14.sp, color = CarColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (fuelStop) {
                                val price = RoutePlan.priceText(o)?.let { pt ->
                                    pt + " " + when (o.src) {
                                        "station" -> if (o.open) "na stacji" else "na stacji · teraz zamknięta"
                                        "max" -> "cena maks. w Polsce" + if (o.cheap) " · zwykle tańsza sieć" else ""
                                        "avg" -> "średnio: ${Countries.name(o.iso)}"
                                        else -> ""
                                    }
                                }
                                val left = if (range != null && res != null) RoutePlan.rangeLeftAt(res, range, o.atKm) else null
                                Text(
                                    listOfNotNull(price ?: "${fav.short} – brak ceny", left?.let { "zostanie ok. ${it.coerceAtLeast(0.0).roundToInt()} km zasięgu" }).joinToString(" · "),
                                    fontSize = 14.sp, color = if (o.src == "station" && o.open) CarColors.Green else CarColors.Text2, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (s.options.size > 1) Box(
                            Modifier.clip(RoundedCornerShape(12.dp)).border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                .clickable { pick[si] = (k + 1) % s.options.size }.padding(horizontal = 12.dp, vertical = 8.dp),
                        ) { Text("Inna ${k + 1}/${s.options.size}", fontSize = 15.sp, color = accent) }
                        val added = p.stops.any { kotlin.math.abs(it.lat - o.place.lat) < 1e-5 && kotlin.math.abs(it.lon - o.place.lon) < 1e-5 }
                        if (!added && p.stops.size < 8) Box(
                            Modifier.clip(RoundedCornerShape(12.dp)).background(accent.copy(alpha = 0.18f)).clickable {
                                SmartRoute.addStopInOrder(r, p.stops, o)
                                Toaster.show("Dodano przystanek: ${o.place.name}")
                            }.padding(horizontal = 14.dp, vertical = 8.dp),
                        ) { Text("Dodaj", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = accent) }
                        else if (added) Text("dodany", fontSize = 15.sp, color = CarColors.Green)
                    }
                }
                if (res != null && st.any { it.kind == "fuel" }) {
                    Text(
                        "${fav.short}: ceny na stacjach w Niemczech, Francji, Austrii, Włoszech i Hiszpanii, w Polsce cena maksymalna, w innych krajach UE średnia" +
                            (if (res.deKeyMissing) ". Trasa przez Niemcy – ceny na stacjach po dodaniu klucza Tankerkönig w CAR OS Connect" else "") +
                            (if (res.fullGuess) ". Pełny bak – szacunek (dopisz tankowania do pełna)" else ""),
                        fontSize = 13.sp, color = CarColors.Muted,
                    )
                }
            }
        }
    }
}

@Composable
private fun SmartLine(icon: ImageVector, tint: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
        Text(text, fontSize = 16.sp, color = CarColors.Text2)
    }
}

@Composable
private fun SmartBtn(icon: ImageVector, text: String, busy: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.5.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            .clickable(enabled = !busy, onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (busy) Spinner(22.dp) else Icon(icon, null, tint = accent, modifier = Modifier.size(24.dp))
        Text(text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** W czasie jazdy: „Szybsza trasa −8 min” z przyciskami Zmień / Nie. */
@Composable
fun FasterRouteBanner() {
    val o by SmartRoute.offer.collectAsState()
    val accent = LocalAccent.current
    var last by remember { mutableStateOf<SmartRoute.Offer?>(null) }
    o?.let { last = it }
    LaunchedEffect(o) {
        // bez odpowiedzi – znika po 40 s
        if (o != null) { kotlinx.coroutines.delay(40_000); if (SmartRoute.offer.value == o) SmartRoute.declineOffer() }
    }
    Box(
        Modifier.fillMaxWidth().padding(start = CarColors.theme.insetStart, end = CarColors.theme.insetEnd, top = 58.dp + CarColors.theme.insetTop),
        contentAlignment = Alignment.TopCenter,
    ) {
        AnimatedVisibility(o != null, enter = fadeIn() + slideInVertically { -it }, exit = fadeOut() + slideOutVertically { -it }) {
            val x = last ?: return@AnimatedVisibility
            Row(
                Modifier.widthIn(max = 720.dp).clip(RoundedCornerShape(22.dp)).carSurface(RoundedCornerShape(22.dp), CarColors.Panel, heavy = true)
                    .border(2.dp, CarColors.Green, RoundedCornerShape(22.dp)).padding(start = 18.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(Icons.Rounded.Traffic, null, tint = CarColors.Green, modifier = Modifier.size(34.dp))
                Column(Modifier.weight(1f, fill = false)) {
                    Text(
                        if (x.closure) "Zamknięta droga na trasie" else "Szybsza trasa: −${durTxt(x.savedS)}",
                        fontSize = 21.sp, fontWeight = FontWeight.Bold,
                    )
                    Text(if (x.closure) "Mam objazd" + (if (x.savedS >= 60) " (−${durTxt(x.savedS)})" else "") else "Omija korek na obecnej trasie", fontSize = 16.sp, color = CarColors.Muted)
                }
                Box(Modifier.clip(RoundedCornerShape(16.dp)).background(CarColors.Green).clickable { SmartRoute.acceptOffer(); Toaster.show("Jedziemy szybszą trasą") }.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Text("Zmień", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF06210F))
                }
                Box(Modifier.clip(RoundedCornerShape(16.dp)).background(CarColors.Raised).clickable { SmartRoute.declineOffer() }.padding(horizontal = 18.dp, vertical = 12.dp)) {
                    Text("Nie", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = accent)
                }
            }
        }
    }
}
