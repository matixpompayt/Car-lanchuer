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
                FuelStat("Zasięg", s.rangeKm?.roundToInt()?.toString() ?: "—", "km",
                    when {
                        s.rangeKm == null -> "zatankuj do pełna i wpisz"
                        s.estimated -> "szacunek · ${s.fuelLeftL?.roundToInt()} l w baku"
                        else -> "ok. ${s.fuelLeftL?.roundToInt()} l w baku"
                    }, Modifier.weight(1f),
                    color = if ((s.rangeKm ?: 999.0) < 80) CarColors.Red else null)
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
    }
}

@Composable
private fun FuelStat(label: String, value: String, unit: String, sub: String, modifier: Modifier, color: Color? = null) {
    CarCard(modifier) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Caption(label)
            ValueText(value, unit, 38.sp, color = color ?: CarColors.Text)
            if (sub.isNotBlank()) Text(sub, fontSize = 14.sp, color = CarColors.Muted, maxLines = 1)
        }
    }
}

@Composable
private fun FuelAddDialog(onDismiss: () -> Unit) {
    val odo by ServiceRepository.odometerKm.collectAsState()
    var liters by remember { mutableStateOf("") }
    var cost by remember { mutableStateOf("") }
    var km by remember { mutableStateOf(if (ServiceRepository.odometerSet.value) odo.roundToInt().toString() else "") }
    var full by remember { mutableStateOf(true) }
    fun num(t: String) = t.replace(',', '.').toDoubleOrNull()
    val l = num(liters)
    val c = num(cost)
    val o = km.toIntOrNull()
    val ok = l != null && l in 1.0..200.0 && c != null && c >= 0 && o != null && o > 0
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(720.dp).clip(RoundedCornerShape(26.dp)).carSurface(RoundedCornerShape(26.dp), CarColors.Panel, heavy = true)
                .border(1.dp, CarColors.Border, RoundedCornerShape(26.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(26.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Nowe tankowanie", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Caption("Litry")
                    CarTextField(liters, { liters = it.filter { ch -> ch.isDigit() || ch == ',' || ch == '.' }.take(6) }, "np. 45,3", Modifier.fillMaxWidth(), keyboardType = KeyboardType.Decimal)
                }
                Column(Modifier.weight(1f)) {
                    Caption("Kwota (zł)")
                    CarTextField(cost, { cost = it.filter { ch -> ch.isDigit() || ch == ',' || ch == '.' }.take(7) }, "np. 289,90", Modifier.fillMaxWidth(), keyboardType = KeyboardType.Decimal)
                }
                Column(Modifier.weight(1f)) {
                    Caption("Przebieg (km)")
                    CarTextField(km, { km = it.filter(Char::isDigit).take(7) }, "z licznika", Modifier.fillMaxWidth(), keyboardType = KeyboardType.Number, imeAction = ImeAction.Done)
                }
            }
            SettingRow("Do pełna", "Zatankowane do odbicia pistoletu – potrzebne do liczenia spalania", onClick = { full = !full }) { CarSwitch(full) }
            if (l != null && c != null && l > 0) Text("Cena: ${(c / l).pl2()} zł/l", fontSize = 17.sp, color = CarColors.Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton("Anuluj", onDismiss, Modifier.weight(1f), height = 56.dp)
                PrimaryButton("Zapisz", {
                    if (!ok) return@PrimaryButton
                    FuelRepository.add(FuelRepository.Fill(UUID.randomUUID().toString(), System.currentTimeMillis(), o!!, l!!, c!!, full))
                    Toaster.show("Zapisano tankowanie")
                    onDismiss()
                }, Modifier.weight(1f), enabled = ok)
            }
        }
    }
}

/** Mała ikona w kółku (na liście). */
@Composable
private fun Dot(color: Color) = Box(Modifier.size(10.dp).clip(CircleShape).background(color))
