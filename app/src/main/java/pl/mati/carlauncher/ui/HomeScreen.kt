package pl.mati.carlauncher.ui

import androidx.compose.foundation.background
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.Add
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.material3.Icon
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import kotlinx.coroutines.delay
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.lerp
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import pl.mati.carlauncher.data.LocationRepository
import pl.mati.carlauncher.data.MediaRepository
import pl.mati.carlauncher.data.ObdManager
import pl.mati.carlauncher.data.Prefs
import pl.mati.carlauncher.data.ServiceRepository
import pl.mati.carlauncher.data.TripRepository
import pl.mati.carlauncher.data.WeatherRepository

@Composable
fun HomeScreen() {
    when (CarColors.theme.home) {
        HomeStyle.MINIMAL -> { HomeScreen2(); return }
        HomeStyle.VW -> { HomeVw(); return }
        HomeStyle.SPORT -> { HomeSport(); return }
        HomeStyle.FULLMAP -> { HomeFullMap(); return }
        HomeStyle.RETRO -> { HomeRetro(); return }
        HomeStyle.CLASSIC -> Unit
    }
    val editing by UiState.homeEdit.collectAsState()
    val split by Prefs.homeSplit.collectAsState()
    val swap by Prefs.homeSwap.collectAsState()
    val showMap by Prefs.homeShowMap.collectAsState()
    val showMedia by Prefs.homeShowMedia.collectAsState()
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { UiState.homeEdit.value = false } }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (editing) HomeEditBar(showMap, showMedia)
        var rowWidth by remember { mutableIntStateOf(1) }
        Row(
            Modifier.weight(1f).fillMaxWidth().onSizeChanged { rowWidth = maxOf(1, it.width) },
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val mapW = if (showMap) split else 0f
            val map: @Composable () -> Unit = {
                Box(Modifier.weight(mapW).fillMaxHeight()) {
                    MapCard(Modifier.fillMaxSize())
                    if (editing) CardEditOverlay(
                        "Mapa", onHide = { Prefs.setHomeShowMap(false) },
                        onSmaller = { Prefs.setHomeSplit(split - 0.05f) }, onBigger = { Prefs.setHomeSplit(split + 0.05f) },
                        onSwap = { Prefs.setHomeSwap(!swap) },
                    )
                }
            }
            val handle: @Composable () -> Unit = {
                if (editing) SplitHandle(swap) { dx -> Prefs.homeSplit.value.let { Prefs.setHomeSplit(it + (if (swap) -dx else dx) / rowWidth) } }
            }
            val right: @Composable () -> Unit = {
                Column(
                    Modifier.weight(if (showMap) 1f - split else 1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (showMedia) Box(Modifier.weight(1f).fillMaxWidth().longPressToEdit { UiState.homeEdit.value = true }) {
                        MediaCard(Modifier.fillMaxSize())
                        if (editing) CardEditOverlay("Muzyka", onHide = { Prefs.setHomeShowMedia(false) })
                    }
                    VehicleTiles(if (showMedia) Modifier.fillMaxWidth() else Modifier.fillMaxWidth().weight(1f), fill = !showMedia)
                }
            }
            if (showMap && !swap) { map(); handle(); right() }
            else if (showMap) { right(); handle(); map() }
            else right()
        }
    }
}

/** Pasek edycji: przywracanie ukrytych kart, układ domyślny, koniec edycji. */
@Composable
private fun HomeEditBar(showMap: Boolean, showMedia: Boolean) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).carSurface(RoundedCornerShape(18.dp), CarColors.Panel, heavy = true).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Edycja ekranu: przeciągnij uchwyt albo − / +, ✕ ukrywa, ⇄ zamienia strony", fontSize = 14.sp, color = CarColors.Muted, modifier = Modifier.weight(1f))
        if (!showMap) SecondaryButton("Mapa", { Prefs.setHomeShowMap(true) }, height = 48.dp, icon = Icons.Rounded.Add)
        if (!showMedia) SecondaryButton("Muzyka", { Prefs.setHomeShowMedia(true) }, height = 48.dp, icon = Icons.Rounded.Add)
        SecondaryButton("Domyślny", { Prefs.resetHomeLayout() }, height = 48.dp)
        PrimaryButton("Gotowe", { UiState.homeEdit.value = false })
    }
}

/** Nakładka edycji na dużą kartę (mapa / muzyka). */
@Composable
private fun CardEditOverlay(
    title: String,
    onHide: () -> Unit,
    onSmaller: (() -> Unit)? = null,
    onBigger: (() -> Unit)? = null,
    onSwap: (() -> Unit)? = null,
) {
    val accent = LocalAccent.current
    Box(
        Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp)).background(CarColors.Bg.copy(alpha = 0.55f))
            .border(2.dp, accent, RoundedCornerShape(24.dp))
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(); } },  // blokuje mapę / przyciski pod spodem
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = CarColors.Text)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onSmaller != null) RoundIconButton(Icons.Rounded.Remove, "Mniejsza", 56.dp, onSmaller)
                if (onBigger != null) RoundIconButton(Icons.Rounded.Add, "Większa", 56.dp, onBigger)
                if (onSwap != null) RoundIconButton(Icons.Rounded.SwapHoriz, "Zamień strony", 56.dp, onSwap)
                Box(
                    Modifier.size(56.dp).clip(CircleShape).background(CarColors.Red).clickable(onClick = onHide),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Close, "Ukryj", tint = Color.White, modifier = Modifier.size(28.dp)) }
            }
        }
    }
}

/** Uchwyt między kolumnami – przeciąganie zmienia szerokość mapy. */
@Composable
private fun SplitHandle(swap: Boolean, onDrag: (Float) -> Unit) {
    val accent = LocalAccent.current
    Box(
        Modifier.width(28.dp).fillMaxHeight()
            .pointerInput(swap) { detectHorizontalDragGestures { ch, dx -> ch.consume(); onDrag(dx) } },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(8.dp).height(90.dp).clip(RoundedCornerShape(4.dp)).background(accent))
    }
}

/** Karta z prośbą o dostęp do powiadomień (bez niego brak muzyki i manewrów). */
@Composable
fun NoAccessCard(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    Column(
        modifier.padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text("Sterowanie muzyką", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Włącz dostęp do powiadomień dla CAR OS – wtedy zobaczysz tu Spotify, radio i manewry z Map Google.",
            fontSize = 17.sp, color = CarColors.Text2,
        )
        Box(
            Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(LocalAccent.current)
                .clickable { Launch.notificationAccess(ctx) }
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Text("Włącz dostęp", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = CarColors.Bg)
        }
    }
}

@Composable
private fun MediaCard(modifier: Modifier) {
    val m by MediaRepository.state.collectAsState()
    val np = rememberNowPlaying()
    val access by MediaRepository.access.collectAsState()
    val ctx = LocalContext.current
    var pos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(m, np.key, np.title) {
        while (true) {
            pos = Player.position(np)
            delay(500)
        }
    }

    val accent = LocalAccent.current
    // kolor tła z okładki (Palette), płynnie zmieniany
    val artColor = remember(np.art) {
        np.art?.let { bmp ->
            try {
                val p = androidx.palette.graphics.Palette.from(bmp).maximumColorCount(12).generate()
                val sw = p.darkVibrantSwatch ?: p.vibrantSwatch ?: p.darkMutedSwatch ?: p.dominantSwatch
                sw?.rgb?.let { androidx.compose.ui.graphics.Color(it) }
            } catch (_: Exception) {
                null
            }
        }
    }
    val tint by androidx.compose.animation.animateColorAsState(artColor ?: accent.copy(alpha = 0.5f), androidx.compose.animation.core.tween(700), label = "tint")

    val artImg = remember(np.art) { np.art?.asImageBitmap() }
    val shadow = if (CarColors.isLight) androidx.compose.ui.text.TextStyle() else androidx.compose.ui.text.TextStyle(
        shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.7f), androidx.compose.ui.geometry.Offset(0f, 2f), 8f),
    )
    // napisy na ciemnych „pigułkach” nad okładką – zawsze jasne
    val onChip = Color(0xFFEEF1F4)

    CarCard(modifier) {
        if (!access) {
            NoAccessCard(Modifier.fillMaxSize())
        } else {
            // tło: okładka na całą kartę (albo kolor akcentu, gdy brak okładki)
            androidx.compose.animation.Crossfade(targetState = artImg, modifier = Modifier.fillMaxSize(), animationSpec = androidx.compose.animation.core.tween(600), label = "bg") { img ->
                if (img != null) {
                    Image(img, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(listOf(tint.copy(alpha = 0.55f), Color.Transparent)),
                        ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.MusicNote, null, tint = CarColors.Text.copy(alpha = 0.08f), modifier = Modifier.size(200.dp))
                    }
                }
            }
            // przyciemnienie: lekko u góry, mocno na dole pod napisami i przyciskami
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.35f),
                        0.25f to Color.Black.copy(alpha = 0.05f),
                        0.5f to (if (CarColors.isLight) tint.copy(alpha = 0.25f).compositeOver(CarColors.Card.copy(alpha = 0.35f)) else tint.copy(alpha = 0.35f).compositeOver(Color.Black.copy(alpha = 0.35f))),
                        0.72f to CarColors.Card.copy(alpha = 0.92f),
                        1f to CarColors.Card,
                    ),
                ),
            )
            Column(
                Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 18.dp),
            ) {
                // źródło
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { UiState.go(Screen.MUSIC) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            when (np.key) { "bt" -> Icons.Rounded.Bluetooth; "radio" -> Icons.Rounded.Radio; else -> Icons.Rounded.MusicNote },
                            null, tint = onChip, modifier = Modifier.size(18.dp),
                        )
                        Caption(np.appName ?: "Muzyka", size = 13.sp, color = onChip)
                    }
                    Box(Modifier.weight(1f))
                    Box(Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.45f)).padding(6.dp)) {
                        Icon(Icons.Rounded.ChevronRight, "Otwórz odtwarzacz", tint = onChip, modifier = Modifier.size(22.dp))
                    }
                }
                // wolne miejsce – tu widać okładkę
                Box(Modifier.fillMaxWidth().weight(1f).clickable { UiState.go(Screen.MUSIC) })
                // tytuł i wykonawca nad paskiem postępu
                Column(Modifier.fillMaxWidth().clickable { UiState.go(Screen.MUSIC) }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(np.title ?: "Nic nie gra", style = shadow, fontSize = 30.sp, fontWeight = FontWeight.Bold, lineHeight = 34.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(np.artist ?: "Dotknij, aby wybrać źródło", style = shadow, fontSize = 19.sp, color = CarColors.Text.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(Modifier.height(12.dp))
                // postęp
                if (np.duration > 0) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ProgressBar(pos.toFloat() / np.duration, CarColors.Text, height = 4.dp)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(formatDuration(pos), fontSize = 14.sp, color = CarColors.Text2)
                            Text(formatDuration(np.duration), fontSize = 14.sp, color = CarColors.Text2)
                        }
                    }
                    Box(Modifier.height(6.dp))
                }
                // sterowanie
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (np.controllable || np.key == null) {
                        RoundIconButton(Icons.Rounded.SkipPrevious, "Poprzedni", 60.dp, { Player.previous(np) }, iconSize = 34.dp)
                        RoundIconButton(
                            if (np.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            if (np.playing) "Pauza" else "Odtwórz",
                            72.dp, { if (np.pkg == null && np.key == null) Launch.music(ctx) else Player.playPause(np) }, filled = true, iconSize = 36.dp,
                        )
                        RoundIconButton(Icons.Rounded.SkipNext, "Następny", 60.dp, { Player.next(np) }, iconSize = 34.dp)
                    } else {
                        SecondaryButton(if (np.isRadio) "Otwórz radio" else "Otwórz Bluetooth", {
                            SourceApps.open(ctx, if (np.isRadio) SourceApps.Kind.RADIO else SourceApps.Kind.BT)
                        }, height = 56.dp)
                    }
                }
            }
        }
    }
}

/** Kafle do wyboru na ekranie głównym (Ustawienia → Wygląd). */
const val MAX_TILES = 8

val TILE_DEFS = listOf(
    "speed" to "Prędkość",
    "coolant" to "Płyn chłodzący",
    "boost" to "Doładowanie",
    "voltage" to "Napięcie",
    "load" to "Obciążenie silnika",
    "rpm" to "Obroty",
    "weather" to "Pogoda",
    "service" to "Najbliższy serwis",
    "today" to "Dziś przejechane",
    "fuel" to "Zasięg (tankowania)",
    "week" to "Ten tydzień (km)",
    "month" to "Ten miesiąc (km)",
    "outside" to "Temperatura zewnętrzna (CAN)",
    "battery" to "Akumulator",
    "shift" to "Najbliższa zmiana",
    "price" to "Cena paliwa (max)",
)

@Composable
private fun VehicleTiles(modifier: Modifier, fill: Boolean = false) {
    val keysStr by Prefs.homeTiles.collectAsState()
    val keys = keysStr.split(",").filter { k -> TILE_DEFS.any { it.first == k } }.distinct().take(MAX_TILES)
    val editing by UiState.homeEdit.collectAsState()
    /** Wybór kafla: indeks do podmiany, -1 = dodaj nowy, null = zamknięte. */
    var pickFor by remember { mutableStateOf<Int?>(null) }
    val perRow = minOf(4, maxOf(1, keys.size + (if (editing && keys.size < MAX_TILES) 1 else 0)))
    val size = if (perRow >= 4) 34.sp else 44.sp
    fun save(l: List<String>) = Prefs.setHomeTiles(l.joinToString(","))

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val slots: List<String?> = keys + (if ((editing && keys.size < MAX_TILES) || keys.isEmpty()) listOf(null) else emptyList())
        slots.chunked(4).forEachIndexed { rowIdx, rowSlots ->
        Row(
            Modifier.fillMaxWidth().then(if (fill) Modifier.weight(1f) else Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min)),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            rowSlots.forEachIndexed { j, k ->
                val i = rowIdx * 4 + j
                if (k == null) {
                    AddTile(Modifier.weight(1f).fillMaxHeight()) { UiState.homeEdit.value = true; pickFor = -1 }
                    return@forEachIndexed
                }
                val wiggle = if (editing) {
                    val t = rememberInfiniteTransition(label = "wig$i")
                    val r by t.animateFloat(-0.8f, 0.8f, infiniteRepeatable(tween(260 + i * 40), RepeatMode.Reverse), label = "r$i")
                    r
                } else 0f
                Box(
                    Modifier.weight(1f).fillMaxHeight()
                        .graphicsLayer { rotationZ = wiggle }
                        .longPressToEdit { UiState.homeEdit.value = true },
                ) {
                    TileContent(k, Modifier.fillMaxWidth().fillMaxHeight(), size)
                    if (editing) TileEditOverlay(
                        canLeft = i > 0, canRight = i < keys.lastIndex,
                        onTap = { pickFor = i },
                        onRemove = { save(keys - k) },
                        onLeft = { val l = keys.toMutableList(); java.util.Collections.swap(l, i, i - 1); save(l) },
                        onRight = { val l = keys.toMutableList(); java.util.Collections.swap(l, i, i + 1); save(l) },
                    )
                }
            }
            // pusta reszta wiersza – kafle w kolejnych wierszach tej samej szerokości
            repeat(perRow - rowSlots.size) { Box(Modifier.weight(1f)) }
        }
        }
    }
    pickFor?.let { idx ->
        TilePicker(
            current = keys.getOrNull(idx), used = keys,
            onPick = { k ->
                val l = keys.toMutableList()
                if (idx >= 0 && idx < l.size) {
                    val other = l.indexOf(k)
                    if (other >= 0 && other != idx) l[other] = l[idx]   // zamiana miejscami, jeśli już jest na ekranie
                    l[idx] = k
                } else if (k !in l) l += k
                save(l.take(MAX_TILES))
                pickFor = null
            },
            onDismiss = { pickFor = null },
        )
    }
}

/** Przytrzymanie kafla (bez kliknięcia pod spodem) włącza edycję. */
private fun Modifier.longPressToEdit(onLong: () -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            waitForUpOrCancellation(PointerEventPass.Initial)
        }
        if (up == null) {
            onLong()
            // połknij resztę gestu, żeby kafel nie otworzył ekranu
            while (true) {
                val e = awaitPointerEvent(PointerEventPass.Initial)
                e.changes.forEach { it.consume() }
                if (e.changes.none { it.pressed }) break
            }
        }
    }
}

@Composable
private fun TileEditOverlay(canLeft: Boolean, canRight: Boolean, onTap: () -> Unit, onRemove: () -> Unit, onLeft: () -> Unit, onRight: () -> Unit) {
    val accent = LocalAccent.current
    Box(
        Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).background(CarColors.Bg.copy(alpha = 0.45f))
            .border(2.dp, accent, RoundedCornerShape(20.dp)).clickable(onClick = onTap),
    ) {
        Box(
            Modifier.align(Alignment.TopEnd).padding(6.dp).size(32.dp).clip(CircleShape).background(CarColors.Red).clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Close, "Usuń kafel", tint = Color.White, modifier = Modifier.size(20.dp)) }
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (canLeft) Box(
                Modifier.size(34.dp).clip(CircleShape).carSurface(CircleShape, CarColors.Panel, heavy = true).clickable(onClick = onLeft),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.ChevronLeft, "W lewo", tint = CarColors.Text, modifier = Modifier.size(24.dp)) }
            if (canRight) Box(
                Modifier.size(34.dp).clip(CircleShape).carSurface(CircleShape, CarColors.Panel, heavy = true).clickable(onClick = onRight),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.ChevronRight, "W prawo", tint = CarColors.Text, modifier = Modifier.size(24.dp)) }
        }
    }
}

@Composable
private fun AddTile(modifier: Modifier, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Box(
        modifier.heightIn(min = 96.dp).clip(RoundedCornerShape(20.dp)).border(2.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.Add, null, tint = accent, modifier = Modifier.size(32.dp))
            Text("Dodaj kafel", fontSize = 15.sp, color = accent, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun TilePicker(current: String?, used: List<String>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val accent = LocalAccent.current
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(760.dp).clip(RoundedCornerShape(26.dp)).carSurface(RoundedCornerShape(26.dp), CarColors.Panel, heavy = true)
                .border(1.dp, CarColors.Border, RoundedCornerShape(26.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (current == null) "Dodaj kafel" else "Zmień kafel", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = CarColors.Text)
            TILE_DEFS.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { (k, label) ->
                        val sel = k == current
                        val onScreen = k in used && !sel
                        Column(
                            Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                                .background(if (sel) accent.copy(alpha = 0.18f) else CarColors.Card)
                                .border(1.dp, if (sel) accent else CarColors.Border, RoundedCornerShape(16.dp))
                                .clickable { onPick(k) }.padding(horizontal = 14.dp, vertical = 12.dp),
                        ) {
                            Text(label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = if (sel) accent else CarColors.Text, maxLines = 1)
                            if (sel || onScreen) Text(if (sel) "teraz tutaj" else "na ekranie – zamieni miejscami", fontSize = 13.sp, color = CarColors.Muted, maxLines = 1)
                        }
                    }
                    repeat(3 - row.size) { Box(Modifier.weight(1f)) }
                }
            }
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) { SecondaryButton("Anuluj", onDismiss, height = 52.dp) }
        }
    }
}

/** Kafle bez tła (wygląd 2.0). */
val LocalMinimalTiles = androidx.compose.runtime.staticCompositionLocalOf { false }

/** Treść jednego kafla wg klucza. */
@Composable
internal fun TileContent(k: String, m: Modifier, size: androidx.compose.ui.unit.TextUnit) {
    val d by pl.mati.carlauncher.data.Vehicle.data.collectAsState()
    val gps by LocationRepository.gpsSpeed.collectAsState()
    when (k) {
        "speed" -> {
            val over by pl.mati.carlauncher.data.SpeedLimitRepository.over.collectAsState()
            val lim by pl.mati.carlauncher.data.SpeedLimitRepository.limit.collectAsState()
            SmallTile(
                if (lim != null) "Prędkość · max $lim" else "Prędkość", (d.speed ?: gps)?.toString() ?: "--", "km/h", m, size,
                valueColor = if (over) Color(0xFFFF4D4F) else CarColors.Text,
                labelColor = if (over) Color(0xFFFF4D4F) else CarColors.Muted,
            ) { UiState.go(Screen.VEHICLE) }
        }
        "coolant" -> SmallTile("Płyn chł.", d.coolant?.toString() ?: "--", "°C", m, size) { UiState.go(Screen.VEHICLE) }
        "boost" -> SmallTile("Doład.", d.boostBar?.pl1() ?: "--", "bar", m, size) { UiState.go(Screen.VEHICLE) }
        "voltage" -> SmallTile("Napięcie", d.voltage?.pl1() ?: "--", "V", m, size) { UiState.go(Screen.VEHICLE) }
        "load" -> SmallTile("Obciąż.", d.load?.toString() ?: "--", "%", m, size) { UiState.go(Screen.VEHICLE) }
        "rpm" -> SmallTile("Obroty", d.rpm?.toString() ?: "--", "", m, size) { UiState.go(Screen.VEHICLE) }
        "outside" -> SmallTile("Na zewnątrz", d.outside?.toString() ?: "--", "°C", m, size) { UiState.go(Screen.VEHICLE) }
        "weather" -> {
            val temp by WeatherRepository.temperature.collectAsState()
            SmallTile("Pogoda", temp?.toString() ?: "--", "°C", m, size) { UiState.go(Screen.WEATHER) }
        }
        "today", "week", "month" -> {
            val trips by TripRepository.trips.collectAsState()
            val cur by TripRepository.current.collectAsState()
            val km = remember(k, trips.size, cur?.distanceM?.toInt()) {
                val from = when (k) { "week" -> TripRepository.startOfWeek(); "month" -> TripRepository.startOfMonth(); else -> TripRepository.startOfDay() }
                TripRepository.summary(from).distanceM / 1000
            }
            val label = when (k) { "week" -> "Tydzień"; "month" -> "Miesiąc"; else -> "Dziś" }
            SmallTile(label, if (km >= 100) km.toInt().toString() else km.toFloat().pl1(), "km", m, size) { UiState.go(Screen.TRIPS) }
        }
        "service" -> {
            val odo by ServiceRepository.odometerKm.collectAsState()
            val svcItems by ServiceRepository.items.collectAsState()
            ServiceTile(m, size, odo, svcItems)
        }
        "price" -> {
            val snap by pl.mati.carlauncher.data.FuelPrices.data.collectAsState()
            val fav by pl.mati.carlauncher.data.FuelPrices.fav.collectAsState()
            val p = snap?.today?.get(fav)
            val ch = snap?.change?.get(fav)
            SmallTile(
                fav.short + when { ch == null || kotlin.math.abs(ch) < 0.005 -> ""; ch > 0 -> " ▲"; else -> " ▼" },
                p?.let { String.format(PL, "%.2f", it) } ?: "--", "zł", m, size,
                labelColor = when { ch == null || kotlin.math.abs(ch) < 0.005 -> CarColors.Muted; ch > 0 -> CarColors.Red; else -> CarColors.Green },
            ) { UiState.go(Screen.PRICES) }
        }
        "fuel" -> {
            val fs by pl.mati.carlauncher.data.FuelRepository.stats.collectAsState()
            SmallTile(
                "Zasięg", fs.rangeKm?.let { kotlin.math.round(it).toInt() }?.toString() ?: "--", "km", m, size,
                valueColor = if ((fs.rangeKm ?: 999.0) < 80) Color(0xFFFF4D4F) else CarColors.Text,
            ) { UiState.go(Screen.FUEL) }
        }
        "battery" -> {
            val v by pl.mati.carlauncher.data.CarCare.voltage.collectAsState()
            val b by pl.mati.carlauncher.data.CarCare.battery.collectAsState()
            val shown = v ?: b?.rest
            val col = when (b?.level) { 2 -> Color(0xFFFF4D4F); 1 -> CarColors.Orange; else -> CarColors.Text }
            SmallTile(
                when (b?.level) { 2 -> "Akumulator · słaby"; 1 -> "Akumulator · uwaga"; else -> "Akumulator" },
                shown?.pl1() ?: "--", "V", m, size, valueColor = col, labelColor = if ((b?.level ?: 0) > 0) col else CarColors.Muted,
            ) { UiState.openSettings("car") }
        }
        "shift" -> {
            val now = rememberNow()
            val info = remember(now.value / 60_000) {
                val t = java.time.LocalDateTime.now()
                val todayMin = t.hour * 60 + t.minute
                val today = pl.mati.carlauncher.data.WorkSchedule.shiftStartingOn(t.toLocalDate())?.takeIf { !it.off && it.start > todayMin }
                if (today != null) "Zmiana dziś" to pl.mati.carlauncher.data.WorkSchedule.hm(today.start)
                else pl.mati.carlauncher.data.WorkSchedule.shiftStartingOn(t.toLocalDate().plusDays(1))?.takeIf { !it.off }
                    ?.let { "Zmiana jutro" to pl.mati.carlauncher.data.WorkSchedule.hm(it.start) } ?: ("Zmiana" to "--")
            }
            SmallTile(info.first, info.second, "", m, size) { UiState.openSettings("auto") }
        }
    }
}

@Composable
private fun SmallTile(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier,
    size: androidx.compose.ui.unit.TextUnit,
    valueColor: androidx.compose.ui.graphics.Color = CarColors.Text,
    labelColor: androidx.compose.ui.graphics.Color = CarColors.Muted,
    blink: Boolean = false,
    onClick: () -> Unit,
) {
    if (LocalMinimalTiles.current) {
        Column(modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 6.dp)) {
            Caption(label, color = labelColor)
            ValueText(value, unit, size, Modifier.fillMaxWidth(), color = valueColor)
        }
        return
    }
    val bg = if (blink) {
        val t = rememberInfiniteTransition(label = "blink")
        val f by t.animateFloat(0f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "f")
        lerp(CarColors.Card, CarColors.Blink, f)
    } else CarColors.Card
    CarCard(modifier.clickable(onClick = onClick), color = bg, radius = 20.dp) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Caption(label, color = labelColor)
            ValueText(value, unit, size, Modifier.fillMaxWidth(), color = valueColor)
        }
    }
}

/** Kafel serwisu: rotacja co 15 s z animacją, miga na czerwono, gdy pozycja jest po terminie. */
@Composable
private fun ServiceTile(
    m: Modifier,
    size: androidx.compose.ui.unit.TextUnit,
    odo: Double,
    svcItems: List<ServiceRepository.Item>,
) {
    val pool = remember(odo.toInt() / 10, svcItems) {
        val known = ServiceRepository.dueList().filter { it.known }
        known.filter { it.overdue || it.soon }.ifEmpty { known }
    }
    var idx by remember { mutableIntStateOf(0) }
    LaunchedEffect(pool.size) {
        idx = 0
        if (pool.size > 1) while (true) {
            delay(15_000)
            idx = (idx + 1) % pool.size
        }
    }
    val cur = pool.getOrNull(idx % maxOf(1, pool.size))
    val overdue = cur?.overdue == true

    val t = rememberInfiniteTransition(label = "blink")
    val f by t.animateFloat(0f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "f")
    val bg = if (overdue) lerp(CarColors.Card, CarColors.Blink, f) else CarColors.Card

    CarCard(m.clickable { UiState.go(Screen.SERVICE) }, color = bg, radius = 20.dp) {
        AnimatedContent(
            targetState = cur,
            transitionSpec = {
                (slideInVertically(tween(450)) { it / 2 } + fadeIn(tween(450))) togetherWith
                    (slideOutVertically(tween(350)) { -it / 2 } + fadeOut(tween(300)))
            },
            label = "service",
        ) { d ->
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                if (d == null) {
                    Caption("Serwis")
                    ValueText("--", "", size, Modifier.fillMaxWidth())
                    return@Column
                }
                val name = d.item.name.substringBefore(" ")
                // km zawsze, gdy pozycja je ma; dni tylko gdy nie ma km albo to termin czasowy jest pilny
                val kmUrgent = d.kmLeft != null && d.kmLeft <= 1000
                val daysUrgent = d.daysLeft != null && d.daysLeft <= 30
                val useDays = d.kmLeft == null || (daysUrgent && !kmUrgent)
                val left = if (useDays) d.daysLeft else d.kmLeft
                val v: String
                val u: String
                if (left == null) { v = "--"; u = "" }
                else if (!useDays) {
                    val km = kotlin.math.abs(left)
                    if (km >= 1000) { v = (km / 1000f).pl1(); u = "tys. km" } else { v = km.toString(); u = "km" }
                } else { v = kotlin.math.abs(left).toString(); u = "dni" }
                val counter = if (pool.size > 1) " ${pool.indexOf(d) + 1}/${pool.size}" else ""
                Caption(
                    (if (d.overdue) "$name · po terminie" else name) + counter,
                    color = if (d.overdue) CarColors.Red else CarColors.Muted,
                )
                ValueText(
                    v, u, size, Modifier.fillMaxWidth(),
                    color = if (d.overdue) CarColors.Red else if (d.soon) CarColors.Orange else CarColors.Text,
                )
            }
        }
    }
}
