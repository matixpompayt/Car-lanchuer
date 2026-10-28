package pl.mati.carlauncher.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import pl.mati.carlauncher.data.MediaRepository
import pl.mati.carlauncher.data.ObdManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------- formatowanie ----------

private val PL = Locale("pl", "PL")
private val TIME_FMT = SimpleDateFormat("HH:mm", PL)
private val DATE_FMT = SimpleDateFormat("EEE, d MMM", PL)

fun formatTime(ms: Long): String = TIME_FMT.format(Date(ms))
fun formatDate(ms: Long): String = DATE_FMT.format(Date(ms))
fun Float.pl1(): String = String.format(PL, "%.1f", this)

fun formatDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
fun rememberNow(): State<Long> = produceState(System.currentTimeMillis()) {
    while (true) {
        value = System.currentTimeMillis()
        delay(1000 - value % 1000)
    }
}

fun obdColor(s: ObdManager.Status): Color = when (s) {
    ObdManager.Status.CONNECTED -> CarColors.Green
    ObdManager.Status.CONNECTING -> CarColors.Orange
    ObdManager.Status.ERROR -> CarColors.Red
    ObdManager.Status.OFF -> CarColors.Muted
}

// ---------- uruchamianie aplikacji ----------

object Launch {
    private const val MAPS = "com.google.android.apps.maps"
    private const val WAZE = "com.waze"

    fun pkg(ctx: Context, pkg: String): Boolean {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return start(ctx, i)
    }

    fun start(ctx: Context, intent: Intent): Boolean = try {
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    fun navigation(ctx: Context) {
        if (pkg(ctx, MAPS) || pkg(ctx, WAZE)) return
        if (!start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=")))) toast(ctx, "Brak aplikacji nawigacji")
    }

    fun music(ctx: Context) {
        val current = MediaRepository.state.value.pkg
        if (current != null && pkg(ctx, current)) return
        if (pkg(ctx, "com.spotify.music")) return
        if (!start(ctx, Intent(MediaStore.INTENT_ACTION_MUSIC_PLAYER))) toast(ctx, "Brak aplikacji muzycznej")
    }

    fun phone(ctx: Context) {
        if (!start(ctx, Intent(Intent.ACTION_DIAL))) toast(ctx, "Brak aplikacji telefonu")
    }

    fun toast(ctx: Context, text: String) = Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()
}

// ---------- klocki UI ----------

@Composable
fun CarCard(
    modifier: Modifier = Modifier,
    color: Color = CarColors.Card,
    radius: Dp = 24.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Box(
        modifier
            .clip(shape)
            .background(color)
            .border(1.dp, CarColors.Border, shape),
        content = content,
    )
}

@Composable
fun Caption(text: String, modifier: Modifier = Modifier, size: TextUnit = 13.sp) {
    Text(
        text.uppercase(PL),
        modifier = modifier,
        color = CarColors.Muted,
        fontSize = size,
        letterSpacing = 1.2.sp,
        maxLines = 1,
    )
}

@Composable
fun ValueText(value: String, unit: String, size: TextUnit, modifier: Modifier = Modifier) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = size, color = CarColors.Text)) {
                append(value)
            }
            withStyle(SpanStyle(fontSize = size * 0.36f, color = CarColors.Muted, fontWeight = FontWeight.Medium)) {
                append(" $unit")
            }
        },
        modifier = modifier,
        maxLines = 1,
    )
}

@Composable
fun ProgressBar(fraction: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 6.dp) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(CarColors.Track),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .clip(RoundedCornerShape(height / 2))
                .background(color),
        )
    }
}

@Composable
fun RoundIconButton(
    icon: ImageVector,
    label: String,
    size: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    iconSize: Dp = size * 0.5f,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(if (filled) CarColors.Text else Color.Transparent)
            .clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = if (filled) CarColors.Bg else CarColors.Text, modifier = Modifier.size(iconSize))
    }
}

@Composable
fun AlbumArt(art: Bitmap?, size: Dp, radius: Dp, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(radius)
    val image = remember(art) { art?.asImageBitmap() }
    if (image != null) {
        Image(
            image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(shape),
        )
    } else {
        Box(
            modifier
                .size(size)
                .clip(shape)
                .background(Brush.linearGradient(listOf(accent, Color(0xFF1C2530)))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.MusicNote, null, tint = CarColors.Text.copy(alpha = 0.8f), modifier = Modifier.size(size * 0.4f))
        }
    }
}

/** Mały pasek odtwarzacza (ekran Pojazd). */
@Composable
fun MiniPlayer(modifier: Modifier = Modifier) {
    val m by MediaRepository.state.collectAsState()
    CarCard(modifier, color = CarColors.Card2, radius = 20.dp) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AlbumArt(m.art, 60.dp, 12.dp)
            Column(Modifier.weight(1f)) {
                Text(m.title ?: "Nic nie gra", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(m.artist ?: m.appName ?: "", fontSize = 16.sp, color = CarColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            RoundIconButton(Icons.Rounded.SkipPrevious, "Poprzedni", 56.dp, MediaRepository::previous)
            RoundIconButton(
                if (m.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                if (m.playing) "Pauza" else "Odtwórz",
                56.dp, MediaRepository::playPause, filled = true, iconSize = 28.dp,
            )
            RoundIconButton(Icons.Rounded.SkipNext, "Następny", 56.dp, MediaRepository::next)
        }
    }
}
