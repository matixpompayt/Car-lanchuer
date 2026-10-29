package pl.mati.carlauncher.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import pl.mati.carlauncher.data.MediaRepository

/** Znane aplikacje multimedialne – pokazujemy tylko zainstalowane. */
private val MEDIA_APPS = listOf(
    "com.spotify.music",
    "com.google.android.apps.youtube.music",
    "com.aspiro.tidal",
    "deezer.android.app",
    "com.maxmpz.audioplayer",
    "org.videolan.vlc",
    "com.android.music",
)

private data class MediaApp(val pkg: String, val label: String, val radio: Boolean)

private fun installedMediaApps(ctx: Context): List<MediaApp> {
    val pm = ctx.packageManager
    return MEDIA_APPS.mapNotNull { p ->
        if (pm.getLaunchIntentForPackage(p) == null) return@mapNotNull null
        val label = try { pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString() } catch (_: Exception) { p }
        MediaApp(p, label, p.contains("radio"))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MusicScreen() {
    val ctx = LocalContext.current
    val m by MediaRepository.state.collectAsState()
    val access by MediaRepository.access.collectAsState()
    val apps = remember { installedMediaApps(ctx) }
    var pos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(m) {
        while (true) {
            pos = MediaRepository.position()
            delay(500)
        }
    }

    if (!access) {
        CarCard(Modifier.fillMaxSize()) { NoAccessCard(Modifier.fillMaxSize().padding(40.dp)) }
        return
    }

    Row(
        Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(36.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumArt(m.art, 440.dp, 28.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            val radioPkg = remember { SourceApps.pkgOf(ctx, SourceApps.Kind.RADIO) }
            val btPkg = remember { SourceApps.pkgOf(ctx, SourceApps.Kind.BT) }
            run {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CarChip("Radio", radioPkg != null && m.pkg == radioPkg, {
                        if (!SourceApps.open(ctx, SourceApps.Kind.RADIO)) {
                            Toaster.show("Nie znalazłem aplikacji radia – wybierz ją w Ustawieniach → Dźwięk")
                            UiState.openSettings("sound")
                        }
                    }, Icons.Rounded.Radio)
                    CarChip("Bluetooth", btPkg != null && m.pkg == btPkg, {
                        if (!SourceApps.open(ctx, SourceApps.Kind.BT)) {
                            Toaster.show("Nie znalazłem muzyki Bluetooth radia – wybierz aplikację w Ustawieniach → Dźwięk")
                            UiState.openSettings("sound")
                        }
                    }, Icons.Rounded.Bluetooth)
                    apps.filter { it.pkg != radioPkg && it.pkg != btPkg }.forEach { a ->
                        CarChip(
                            a.label, m.pkg == a.pkg,
                            { if (!Launch.pkg(ctx, a.pkg)) Toaster.show("Nie można otworzyć: ${a.label}") },
                            if (a.radio) Icons.Rounded.Radio else Icons.Rounded.MusicNote,
                        )
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Caption((if (m.playing) "Teraz gra" else "Pauza") + (m.appName?.let { " · $it" } ?: ""), size = 15.sp)
                Text(m.title ?: "Nic nie gra", fontSize = 44.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(m.artist ?: "Wybierz źródło powyżej", fontSize = 24.sp, color = CarColors.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (m.duration > 0) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SeekBar(pos.toFloat() / m.duration) { f -> MediaRepository.seekTo((f * m.duration).toLong()) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatDuration(pos), fontSize = 16.sp, color = CarColors.Muted)
                        Text(formatDuration(m.duration), fontSize = 16.sp, color = CarColors.Muted)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                RoundIconButton(Icons.Rounded.SkipPrevious, "Poprzedni", 72.dp, MediaRepository::previous, iconSize = 40.dp)
                RoundIconButton(
                    if (m.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    if (m.playing) "Pauza" else "Odtwórz",
                    92.dp, { if (m.pkg == null) Launch.music(ctx) else MediaRepository.playPause() }, filled = true, iconSize = 44.dp,
                )
                RoundIconButton(Icons.Rounded.SkipNext, "Następny", 72.dp, MediaRepository::next, iconSize = 40.dp)
                Spacer(Modifier.weight(1f))
                RoundIconButton(Icons.Rounded.Equalizer, "Korektor", 60.dp, { Launch.equalizer(ctx) }, iconSize = 30.dp)
            }
        }
    }
}

@Composable
private fun SeekBar(fraction: Float, onSeek: (Float) -> Unit) {
    val accent = LocalAccent.current
    var width by remember { mutableIntStateOf(1) }
    val cb by rememberUpdatedState(onSeek)
    Box(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .onSizeChanged { width = it.width.coerceAtLeast(1) }
            .pointerInput(Unit) { detectTapGestures { cb((it.x / width).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) { detectHorizontalDragGestures { c, _ -> cb((c.position.x / width).coerceIn(0f, 1f)) } },
        contentAlignment = Alignment.CenterStart,
    ) {
        ProgressBar(fraction, accent, height = 6.dp)
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight(), contentAlignment = Alignment.CenterEnd) {
            Box(Modifier.offset(x = 9.dp).size(18.dp).clip(CircleShape).background(CarColors.Text))
        }
    }
}
