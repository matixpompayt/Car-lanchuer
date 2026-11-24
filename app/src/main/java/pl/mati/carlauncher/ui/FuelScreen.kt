package pl.mati.carlauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.mati.carlauncher.data.FuelRepository
import pl.mati.carlauncher.data.ServiceRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID
import kotlin.math.roundToInt

private val FILL_FMT = SimpleDateFormat("d MMM yyyy, HH:mm", PL)

fun Double.pl2(): String = String.format(PL, "%.2f", this)
fun Double.pl1d(): String = String.format(PL, "%.1f", this)

/** Tankowania: spalanie, zasięg, koszty, lista i dodawanie. */
@Composable
fun FuelScreen() {
    val fills by FuelRepository.fills.collectAsState()
    val s by FuelRepository.stats.collectAsState()
    val tank by FuelRepository.tankL.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var calib by remember { mutableStateOf(false) }
    val rk by FuelRepository.rangeK.collectAsState()
    var deleting by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { FuelRepository.load(); FuelRepository.recompute() }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Tankowania", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("Bak $tank l", fontSize = 17.sp, color = CarColors.Muted)
                RoundIconButton(Icons.Rounded.Remove, "Mniejszy bak", 44.dp, { FuelRepository.setTank(tank - 1) })
                RoundIconButton(Icons.Rounded.Add, "Większy bak", 44.dp, { FuelRepository.setTank(tank + 1) })
                PrimaryButton("Dodaj tankowanie", { adding = true }, icon = Icons.Rounded.LocalGasStation)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                FuelStat("Średnie spalanie", s.avgL100?.pl1d() ?: "—", "l/100 km",
                    if (s.avgL100 == null) "po 2 tankowaniach do pełna" else s.lastL100?.let { "ostatnio ${it.pl1d()}" } ?: "", Modifier.weight(1f))
                // dotknij – korekta zasięgu z licznikiem auta
                val pct = Math.round((rk - 1) * 100)
                FuelStat("Zasięg" + if (pct != 0L && !s.rangeFromCar) " · korekta ${if (pct > 0) "+" else ""}$pct %" else "", s.rangeKm?.roundToInt()?.toString() ?: "—", "km",
                    when {
                        s.rangeKm == null -> "zatankuj do pełna i wpisz"
                        s.fromCan -> "${s.fuelLeftL?.roundToInt()} l w baku · z czujnika auta"
                        s.estimated -> "szacunek · ${s.fuelLeftL?.roundToInt()} l w baku"
                        else -> "ok. ${s.fuelLeftL?.roundToInt()} l w baku"
                    }, Modifier.weight(1f),
                    color = if ((s.rangeKm ?: 999.0) < 80) CarColors.Red else null,
                    onClick = { calib = true })
                FuelStat("Koszt kilometra", s.costPerKm?.let { (it * 100).pl1d() } ?: "—", "gr/km",
                    s.lastPrice?.let { "ostatnio ${it.pl2()} zł/l" } ?: "", Modifier.weight(1f))
                FuelStat("Ten miesiąc", s.monthCost.roundToInt().toString(), "zł", "${s.monthLiters.pl1d()} l", Modifier.weight(1f))
            }
            if (fills.isEmpty()) {
                Text(
                    "Brak tankowań. Po zatankowaniu dotknij „Dodaj tankowanie” (albo dodaj w CAR OS Connect w telefonie). " +
                        "Tankuj do pełna, żeby CAR OS policzył średnie spalanie i zasięg.",
                    fontSize = 18.sp, color = CarColors.Muted,
                )
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(fills, key = { it.id }) { f ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).carSurface(RoundedCornerShape(18.dp), CarColors.Card).padding(horizontal = 18.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        IconBadge(Icons.Rounded.LocalGasStation, 46.dp)
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${f.liters.pl1d()} l · ${f.cost.pl2()} zł" + if (f.full) "  · do pełna" else "",
                                fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                FILL_FMT.format(Date(f.time)) + " · ${"%,d".format(f.odoKm).replace(',', ' ')} km · ${f.pricePerL.pl2()} zł/l",
                                fontSize = 15.sp, color = CarColors.Muted,
                            )
                        }
                        if (deleting == f.id) {
                            SecondaryButton("Usunąć?", { FuelRepository.remove(f.id); deleting = null }, height = 48.dp)
                        } else {
                            RoundIconButton(Icons.Rounded.DeleteOutline, "Usuń", 44.dp, { deleting = f.id })
                        }
                    }
                }
            }
        }
        if (adding) FuelAddDialog { adding = false }
        if (calib) RangeCalDialog { calib = false }
    }
}

@Composable
private fun FuelStat(label: String, value: String, unit: String, sub: String, modifier: Modifier, color: Color? = null, onClick: (() -> Unit)? = null) {
    CarCard(if (onClick != null) modifier.clip(RoundedCornerShape(22.dp)).clickable(onClick = onClick) else modifier) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Caption(label)
            ValueText(value, unit, 38.sp, color = color ?: CarColors.Text)
            if (sub.isNotBlank()) Text(sub, fontSize = 14.sp, color = CarColors.Muted, maxLines = 1)
        }
    }
}

/** Nowe tankowanie: litry, kwota, przebieg – z klawiatury w panelu. */
@Composable
private fun FuelAddDialog(onDismiss: () -> Unit) {
    val odo by ServiceRepository.odometerKm.collectAsState()
    var liters by remember { mutableStateOf("") }
    var cost by remember { mutableStateOf("") }
    var km by remember { mutableStateOf(if (ServiceRepository.odometerSet.value) odo.roundToInt().toString() else "") }
    var full by remember { mutableStateOf(true) }
    var field by remember { mutableStateOf(0) }
    CarPanel(
        onDismiss, "Nowe tankowanie", "Wpisz z dystrybutora i licznika – CAR OS policzy spalanie, zasięg i koszty",
        Icons.Rounded.LocalGasStation,
        actions = { ctl ->
            val l = padNum(liters)
            val c = padNum(cost)
            val o = km.toIntOrNull()
            val ok = l != null && l in 1.0..200.0 && c != null && c >= 0 && o != null && o > 0
            PanelButton("Anuluj", { ctl.dismiss() }, Modifier.weight(1f))
            PanelButton("Zapisz tankowanie", {
                if (!ok) return@PanelButton
                FuelRepository.add(FuelRepository.Fill(UUID.randomUUID().toString(), System.currentTimeMillis(), o!!, l!!, c!!, full))
                Toaster.show("Zapisano tankowanie")
                ctl.dismiss()
            }, Modifier.weight(1.4f), icon = Icons.Rounded.Check, primary = true, enabled = ok)
        },
    ) {
        val l = padNum(liters)
        val c = padNum(cost)
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PadField("Litry", liters, "l", field == 0, { field = 0 }, Modifier.fillMaxWidth(), hint = "0")
                PadField(
                    "Kwota", cost, "zł", field == 1, { field = 1 }, Modifier.fillMaxWidth(), hint = "0",
                    sub = if (l != null && c != null && l > 0 && c > 0) "${(c / l).pl2()} zł/l" else null,
                )
                PadField("Przebieg", km, "km", field == 2, { field = 2 }, Modifier.fillMaxWidth(), hint = "z licznika")
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).carSurface(RoundedCornerShape(20.dp), CarColors.Card)
                        .clickable { full = !full }.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Do pełna", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                        Text("Do odbicia pistoletu – potrzebne do spalania", fontSize = 14.sp, color = CarColors.Muted)
                    }
                    CarSwitch(full)
                }
            }
            NumPad(
                onKey = { ch ->
                    when (field) {
                        0 -> liters = padType(liters, ch, 3, 2)
                        1 -> cost = padType(cost, ch, 4, 2)
                        else -> km = padType(km, ch, 7, 0)
                    }
                },
                onBackspace = {
                    when (field) {
                        0 -> liters = liters.dropLast(1)
                        1 -> cost = cost.dropLast(1)
                        else -> km = km.dropLast(1)
                    }
                },
                onClear = { when (field) { 0 -> liters = ""; 1 -> cost = ""; else -> km = "" } },
                modifier = Modifier.width(340.dp),
                decimal = field != 2,
            )
        }
    }
}

/**
 * Korekta zasięgu: wpisz zasięg z licznika auta – CAR OS liczy mnożnik i od teraz pokazuje tyle samo
 * (także w telefonie). Albo ręcznie −/+ 1 %, albo bez korekty.
 */
@Composable
private fun RangeCalDialog(onDismiss: () -> Unit) {
    val s by FuelRepository.stats.collectAsState()
    val cur by FuelRepository.rangeK.collectAsState()
    var k by remember { mutableStateOf(cur) }
    var km by remember { mutableStateOf("") }
    val raw = s.rangeRawKm
    val typed = km.toIntOrNull()
    // wpisany zasięg z licznika ma pierwszeństwo przed −/+
    val kNew = if (typed != null && raw != null && raw > 5) (typed / raw).coerceIn(0.5, 1.5) else k
    val pct = Math.round((kNew - 1) * 100)
    CarPanel(
        onDismiss, "Korekta zasięgu",
        if (s.rangeFromCar) "Zasięg podaje samo auto (CAN)" else "Wpisz zasięg z licznika auta – CAR OS dopasuje swój (także w telefonie)",
        Icons.Rounded.Speed,
        actions = { ctl ->
            PanelButton("Bez korekty", { FuelRepository.setRangeK(1.0); Toaster.show("Zasięg bez korekty"); ctl.dismiss() }, Modifier.weight(1f))
            PanelButton("Zapisz", {
                FuelRepository.setRangeK(kNew)
                Toaster.show(if (pct == 0L) "Zasięg bez korekty" else "Korekta zasięgu: ${if (pct > 0) "+" else ""}$pct %")
                ctl.dismiss()
            }, Modifier.weight(1f), icon = Icons.Rounded.Check, primary = true, enabled = !s.rangeFromCar)
        },
    ) {
        if (s.rangeFromCar) {
            Text("To ta sama wartość co na liczniku – korekta nie jest potrzebna.", fontSize = 19.sp, color = CarColors.Text2)
            return@CarPanel
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PadField("Zasięg na liczniku", km, "km", true, {}, Modifier.fillMaxWidth(), hint = raw?.let { "${(it * kNew).roundToInt()}" } ?: "0")
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).carSurface(RoundedCornerShape(20.dp), CarColors.Card).padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Korekta", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    RoundIconButton(Icons.Rounded.Remove, "Mniej", 56.dp, { km = ""; k = ((kNew * 100).roundToInt() - 1) / 100.0 })
                    Text(
                        (if (pct > 0) "+" else "") + "$pct %", fontSize = 32.sp, fontWeight = FontWeight.Bold,
                        color = if (pct == 0L) CarColors.Text else LocalAccent.current, modifier = Modifier.width(110.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    RoundIconButton(Icons.Rounded.Add, "Więcej", 56.dp, { km = ""; k = ((kNew * 100).roundToInt() + 1) / 100.0 })
                }
                if (raw != null) {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(LocalAccent.current.copy(alpha = 0.10f)).padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text("Bez korekty ${raw.roundToInt()} km", fontSize = 17.sp, color = CarColors.Muted)
                        Text("Z korektą ${(raw * kNew).roundToInt()} km", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    }
                } else Text("Zasięg będzie znany po tankowaniu do pełna albo z czujnika paliwa.", fontSize = 17.sp, color = CarColors.Muted)
            }
            NumPad(
                onKey = { ch -> km = padType(km, ch, 4, 0) },
                onBackspace = { km = km.dropLast(1) },
                onClear = { km = "" },
                modifier = Modifier.width(340.dp),
                decimal = false,
            )
        }
    }
}

/** Mała ikona w kółku (na liście). */
@Composable
private fun Dot(color: Color) = Box(Modifier.size(10.dp).clip(CircleShape).background(color))
