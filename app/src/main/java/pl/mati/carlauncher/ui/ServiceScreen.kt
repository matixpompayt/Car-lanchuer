package pl.mati.carlauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Speed
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.mati.carlauncher.data.ServiceRepository
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.roundToInt

private val DATE = SimpleDateFormat("d.MM.yyyy", PL)

@Composable
fun ServiceScreen() {
    val items by ServiceRepository.items.collectAsState()
    val odo by ServiceRepository.odometerKm.collectAsState()
    val odoSet by ServiceRepository.odometerSet.collectAsState()
    var editOdo by remember { mutableStateOf(false) }
    var odoText by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Serwis", fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
        CarCard(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 22.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                IconBadge(Icons.Rounded.Speed, 56.dp)
                Column(Modifier.weight(1f)) {
                    Caption("Przebieg")
                    if (editOdo) {
                        CarTextField(
                            odoText, { odoText = it.filter(Char::isDigit).take(7) }, "np. 245000", Modifier.width(320.dp),
                            keyboardType = KeyboardType.Number, imeAction = ImeAction.Done,
                            onDone = { odoText.toIntOrNull()?.let { ServiceRepository.setOdometer(it) }; editOdo = false },
                        )
                    } else {
                        Text(
                            if (odoSet) "${"%,d".format(odo.roundToInt()).replace(',', ' ')} km" else "Wpisz przebieg z licznika",
                            fontSize = 32.sp, fontWeight = FontWeight.Bold, fontFamily = Condensed,
                        )
                        Text("Dalej liczony sam z jazd (GPS/OBD). Raz na jakiś czas popraw wg licznika.", fontSize = 15.sp, color = CarColors.Muted)
                    }
                }
                if (editOdo) {
                    PrimaryButton("Zapisz", { odoText.toIntOrNull()?.let { ServiceRepository.setOdometer(it) }; editOdo = false })
                } else {
                    SecondaryButton(if (odoSet) "Popraw" else "Wpisz", { odoText = if (odoSet) odo.roundToInt().toString() else ""; editOdo = true }, height = 56.dp)
                }
            }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val dues = items.map { ServiceRepository.due(it) }
                .sortedWith(compareByDescending<ServiceRepository.Due> { it.overdue }.thenByDescending { it.soon }.thenByDescending { it.fraction })
            items(dues, key = { it.item.id }) { d ->
                ServiceRow(d, expanded == d.item.id, odoSet) { expanded = if (expanded == d.item.id) null else d.item.id }
            }
        }
    }
}

@Composable
private fun ServiceRow(d: ServiceRepository.Due, open: Boolean, odoSet: Boolean, onToggle: () -> Unit) {
    val accent = LocalAccent.current
    val color = when {
        !d.known -> CarColors.Muted
        d.overdue -> CarColors.Red
        d.soon -> CarColors.Orange
        else -> CarColors.Green
    }
    val icon: ImageVector = when {
        !d.known -> Icons.Rounded.Schedule
        d.overdue || d.soon -> Icons.Rounded.ErrorOutline
        else -> Icons.Rounded.CheckCircle
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(CarColors.Card).clickable(onClick = onToggle).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(icon, null, tint = color, modifier = Modifier.size(28.dp))
            Column(Modifier.weight(1f)) {
                Text(d.item.name, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                Text(ServiceRepository.describe(d), fontSize = 15.sp, color = color)
            }
            Text(
                listOfNotNull(
                    d.item.intervalKm.takeIf { it > 0 }?.let { "co ${it / 1000} tys. km" },
                    d.item.intervalMonths.takeIf { it > 0 }?.let { "co $it mies." },
                ).joinToString(" / "),
                fontSize = 15.sp, color = CarColors.Muted,
            )
        }
        if (d.known) ProgressBar(d.fraction, color, height = 5.dp)
        if (open) ServiceEditor(d.item, odoSet, accent)
    }
}

@Composable
private fun ServiceEditor(item: ServiceRepository.Item, odoSet: Boolean, accent: androidx.compose.ui.graphics.Color) {
    var lastKm by remember(item) { mutableStateOf(if (item.lastKm >= 0) item.lastKm.toString() else "") }
    var lastDate by remember(item) { mutableStateOf(if (item.lastDate > 0) DATE.format(Date(item.lastDate)) else "") }
    var iKm by remember(item) { mutableStateOf(if (item.intervalKm > 0) item.intervalKm.toString() else "") }
    var iMo by remember(item) { mutableStateOf(if (item.intervalMonths > 0) item.intervalMonths.toString() else "") }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Field("Ostatnio przy (km)", lastKm, { lastKm = it.filter(Char::isDigit).take(7) }, "np. 230000", Modifier.weight(1f))
        Field("Ostatnio dnia", lastDate, { lastDate = it.take(10) }, "dd.mm.rrrr", Modifier.weight(1f), KeyboardType.Text)
        Field("Co ile km", iKm, { iKm = it.filter(Char::isDigit).take(6) }, "0 = brak", Modifier.weight(0.8f))
        Field("Co ile mies.", iMo, { iMo = it.filter(Char::isDigit).take(3) }, "0 = brak", Modifier.weight(0.8f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        PrimaryButton("Zapisz", {
            val date = try { DATE.parse(lastDate)?.time ?: 0L } catch (_: Exception) { 0L }
            ServiceRepository.update(
                item.copy(
                    lastKm = lastKm.toIntOrNull() ?: -1,
                    lastDate = date,
                    intervalKm = iKm.toIntOrNull() ?: 0,
                    intervalMonths = iMo.toIntOrNull() ?: 0,
                ),
            )
            ServiceRepository.checkReminders()
            Toaster.show("Zapisano: ${item.name}")
        })
        SecondaryButton("Wykonano dziś", {
            ServiceRepository.markDone(item.id)
            ServiceRepository.checkReminders()
            Toaster.show(if (odoSet) "Zapisano wymianę przy obecnym przebiegu" else "Zapisano datę – wpisz przebieg, żeby liczyć też km")
        }, icon = Icons.Rounded.Build)
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier, type: KeyboardType = KeyboardType.Number) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Caption(label)
        CarTextField(value, onChange, hint, Modifier.fillMaxWidth(), keyboardType = type)
    }
}
