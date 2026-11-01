package pl.mati.carlauncher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Save
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.mati.carlauncher.data.BroadcastProbe
import pl.mati.carlauncher.data.CanRepository
import pl.mati.carlauncher.data.CanRepository.Field
import pl.mati.carlauncher.data.CanRepository.Mapping

object CanScanner {
    val open = MutableStateFlow(false)
}

private typealias Snap = CanRepository.Row

/** Czytelny opis klucza (bez długich prefiksów nazw pakietów). */
private fun shortKey(k: String): String {
    val src = when {
        k.startsWith("bc:") -> "komunikat"
        k.startsWith("set:") -> "ustawienie"
        k.startsWith("prop:") -> "właściwość"
        k.startsWith("fyt:") -> "FYT"
        else -> ""
    }
    val body = k.substringAfter(':')
    val shortBody = if (k.startsWith("bc:")) {
        val action = body.substringBefore('#').substringBefore('@')
        val rest = body.removePrefix(action)
        action.split('.').takeLast(2).joinToString(".") + rest
    } else body
    return "$src · $shortBody"
}

/** Skaner: wszystkie liczby, które da się podsłuchać w radiu; dotknij, żeby przypisać. */
@Composable
fun CanScannerOverlay() {
    val open by CanScanner.open.collectAsState()
    LaunchedEffect(open) { CanRepository.scannerOpen.value = open }
    AnimatedVisibility(open, enter = fadeIn(), exit = fadeOut()) { ScannerContent() }
}

@Composable
private fun ScannerContent() {
    val ctx = LocalContext.current
    val src by CanRepository.sources.collectAsState()
    val maps by CanRepository.mappings.collectAsState()
    val vals by CanRepository.values.collectAsState()
    val scanning by BroadcastProbe.scanning.collectAsState()
    var onlyChanging by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var list by remember { mutableStateOf(emptyList<Snap>()) }
    var total by remember { mutableStateOf(0) }
    var assign by remember { mutableStateOf<Snap?>(null) }
    var saving by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(onlyChanging, query) {
        while (true) {
            list = try { withContext(Dispatchers.Default) { CanRepository.snapshot(onlyChanging, query) } } catch (_: Exception) { list }
            total = CanRepository.signals.size
            delay(700)
        }
    }

    Box(
        Modifier.fillMaxSize().background(CarColors.Bg)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Dane z radia (CAN)", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = CarColors.Text)
                    Text(
                        "Sygnałów: $total · komunikaty: ${src.actions}${if (scanning) " (szukam…)" else ""} · aplikacje CAN: ${src.canPackages.size} · FYT: ${src.fyt}",
                        fontSize = 15.sp, color = CarColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    note?.let { Text(it, fontSize = 15.sp, color = LocalAccent.current, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                CarChip("Tylko zmieniające się", onlyChanging, { onlyChanging = !onlyChanging })
                SecondaryButton(if (saving) "Zapisuję…" else "Zapisz raport", {
                    if (saving) return@SecondaryButton
                    saving = true
                    scope.launch {
                        val path = withContext(Dispatchers.IO) { CanRepository.saveReport(ctx) }
                        note = if (path != null) "Raport zapisany: $path – wyślij mi ten plik" else "Nie udało się zapisać raportu"
                        saving = false
                    }
                }, height = 52.dp, icon = Icons.Rounded.Save)
                SecondaryButton("Wyczyść", { CanRepository.clearSignals() }, height = 52.dp, icon = Icons.Rounded.Delete)
                RoundIconButton(Icons.Rounded.Close, "Zamknij", 52.dp, { CanScanner.open.value = false })
            }

            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                // sygnały
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CarTextField(query, { query = it }, "Filtruj (np. speed, rpm, temp, can)", Modifier.fillMaxWidth())
                    if (list.isEmpty()) {
                        Text(
                            if (total == 0) "Na razie nic. Włącz zapłon / silnik i poczekaj kilka sekund – radio musi coś wysłać."
                            else "Brak sygnałów pasujących do filtra.",
                            fontSize = 18.sp, color = CarColors.Muted, modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(list, key = { it.key }) { s ->
                            val assignedTo = maps.entries.firstOrNull { it.value.key == s.key }?.key
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CarColors.Card)
                                    .then(if (assignedTo != null) Modifier.border(1.5.dp, LocalAccent.current, RoundedCornerShape(14.dp)) else Modifier)
                                    .clickable { assign = s }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(shortKey(s.key), fontSize = 16.sp, color = CarColors.Text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "min ${CanRepository.fmt(s.min)} · max ${CanRepository.fmt(s.max)}" +
                                            (if (s.changes > 0) " · zmian/min: ${s.changes}" else "") +
                                            (assignedTo?.let { " · = ${it.label}" } ?: ""),
                                        fontSize = 13.sp, color = if (assignedTo != null) LocalAccent.current else CarColors.Muted, maxLines = 1,
                                    )
                                }
                                Text(
                                    CanRepository.fmt(s.value), fontSize = 28.sp, fontFamily = Condensed, fontWeight = FontWeight.Bold,
                                    color = if (s.changes > 0) LocalAccent.current else CarColors.Text,
                                )
                            }
                        }
                    }
                }

                // przypisane
                Column(
                    Modifier.width(380.dp).fillMaxHeight().clip(RoundedCornerShape(20.dp)).background(CarColors.Card).padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Przypisane", fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = CarColors.Text)
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(Field.entries.toList()) { f ->
                            val m = maps[f]
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(f.label, fontSize = 16.sp, color = if (m != null) CarColors.Text else CarColors.Muted)
                                    if (m != null) Text(shortKey(m.key), fontSize = 12.sp, color = CarColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Text(
                                    vals[f]?.let { CanRepository.fmt(Math.round(it * 10) / 10.0) + " " + f.unit } ?: "—",
                                    fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = if (m != null) LocalAccent.current else CarColors.Dim,
                                )
                                if (m != null) RoundIconButton(Icons.Rounded.Close, "Usuń przypisanie", 34.dp, { CanRepository.unassign(f) })
                            }
                        }
                    }
                    Text(
                        "Jak szukać: włącz silnik i dodaj gazu – obroty to liczba, która rośnie z gazem (np. 800 → 2500). " +
                            "Temperatura płynu powoli rośnie po zapaleniu. Włącz „Tylko zmieniające się”. " +
                            "Jeśli nic nie pasuje – „Zapisz raport” i wyślij mi plik z Pobrane/CarOS.",
                        fontSize = 13.sp, color = CarColors.Muted,
                    )
                }
            }
        }
        assign?.let { s -> AssignDialog(s, maps, { note = it }) { assign = null } }
    }
}

@Composable
private fun AssignDialog(s: Snap, maps: Map<Field, Mapping>, onNote: (String) -> Unit, onDismiss: () -> Unit) {
    val current = maps.entries.firstOrNull { it.value.key == s.key }
    var field by remember { mutableStateOf(current?.key ?: Field.RPM) }
    var scale by remember { mutableStateOf(current?.value?.scale ?: 1.0) }
    var offset by remember { mutableStateOf(current?.value?.offset ?: 0.0) }
    val live by CanRepository.tick.collectAsState()
    val raw = remember(live) { CanRepository.signals[s.key]?.value ?: s.value }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(820.dp).clip(RoundedCornerShape(26.dp)).background(CarColors.Panel)
                .border(1.dp, CarColors.Border, RoundedCornerShape(26.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(26.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Przypisz wartość", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = CarColors.Text)
            Text(shortKey(s.key), fontSize = 15.sp, color = CarColors.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Caption("Co to jest?")
            Field.entries.chunked(5).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { f -> CarChip(f.label.substringBefore(" ("), field == f, { field = f }) }
                }
            }
            Caption("Mnożnik")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1.0, 0.1, 0.01, 0.001, 0.25, 0.5, 0.75, 10.0).forEach { v ->
                    CarChip("×" + CanRepository.fmt(v).replace('.', ','), scale == v, { scale = v })
                }
            }
            Caption("Przesunięcie")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.0, -40.0, -48.0, -50.0, -100.0, -273.0).forEach { v ->
                    CarChip(if (v == 0.0) "brak" else CanRepository.fmt(v), offset == v, { offset = v })
                }
            }
            val result = raw * scale + offset
            Text(
                "Teraz: ${CanRepository.fmt(raw)} → ${CanRepository.fmt(Math.round(result * 100) / 100.0)} ${field.unit}",
                fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = LocalAccent.current,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton("Anuluj", onDismiss, Modifier.weight(1f), height = 56.dp)
                if (current != null) SecondaryButton("Usuń przypisanie", { CanRepository.unassign(current.key); onDismiss() }, Modifier.weight(1f), height = 56.dp)
                PrimaryButton("Przypisz: ${field.label.substringBefore(" (")}", {
                    CanRepository.assign(field, Mapping(s.key, scale, offset))
                    onNote("${field.label}: przypisano – widać na ekranie Pojazd i w kafelkach")
                    onDismiss()
                }, Modifier.weight(1.4f))
            }
        }
    }
}
