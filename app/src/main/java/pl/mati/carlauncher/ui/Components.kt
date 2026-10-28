package pl.mati.carlauncher.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.SolidColor
import kotlinx.coroutines.delay
import pl.mati.carlauncher.data.MediaRepository
import pl.mati.carlauncher.data.ObdManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------- formatowanie ----------

val PL = Locale("pl", "PL")
private val TIME_FMT = SimpleDateFormat("HH:mm", PL)
private val DATE_FMT = SimpleDateFormat("EEE, d MMM", PL)

fun formatTime(ms: Long): String = TIME_FMT.format(Date(ms))
fun formatDate(ms: Long): String = DATE_FMT.format(Date(ms))
fun Float.pl1(): String = String.format(PL, "%.1f", this)

fun formatDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

fun greeting(name: String, ms: Long = System.currentTimeMillis()): String {
    val h = java.util.Calendar.getInstance().apply { timeInMillis = ms }.get(java.util.Calendar.HOUR_OF_DAY)
    val base = if (h < 5 || h >= 18) "Dobry wieczór" else "Dzień dobry"
    return if (name.isBlank()) base else "$base, $name"
}

fun isNightHour(ms: Long = System.currentTimeMillis()): Boolean {
    val h = java.util.Calendar.getInstance().apply { timeInMillis = ms }.get(java.util.Calendar.HOUR_OF_DAY)
    return h >= 19 || h < 6
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
    const val MAPS = "com.google.android.apps.maps"
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
        if (!start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=")))) toast("Brak aplikacji nawigacji")
    }

    fun navigateTo(ctx: Context, address: String) {
        val uri = Uri.parse("google.navigation:q=" + Uri.encode(address))
        if (start(ctx, Intent(Intent.ACTION_VIEW, uri).setPackage(MAPS))) return
        if (start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(address))))) return
        toast("Brak aplikacji nawigacji")
    }

    fun mapsSearch(ctx: Context, query: String) {
        val uri = Uri.parse("geo:0,0?q=" + Uri.encode(query))
        if (start(ctx, Intent(Intent.ACTION_VIEW, uri).setPackage(MAPS))) return
        if (!start(ctx, Intent(Intent.ACTION_VIEW, uri))) toast("Brak aplikacji nawigacji")
    }

    fun music(ctx: Context) {
        val current = MediaRepository.state.value.pkg
        if (current != null && pkg(ctx, current)) return
        if (pkg(ctx, "com.spotify.music")) return
        if (!start(ctx, Intent(MediaStore.INTENT_ACTION_MUSIC_PLAYER))) toast("Brak aplikacji muzycznej")
    }

    fun dialer(ctx: Context, number: String = "") {
        val i = if (number.isBlank()) Intent(Intent.ACTION_DIAL) else Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number)))
        if (!start(ctx, i)) toast("Brak aplikacji telefonu")
    }

    fun equalizer(ctx: Context) {
        val i = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
            .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0)
            .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, ctx.packageName)
            .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
        if (!start(ctx, i)) toast("To radio nie ma systemowego korektora – użyj korektora w aplikacji muzyki")
    }

    fun notificationAccess(ctx: Context) {
        start(ctx, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    fun toast(text: String) = Toaster.show(text)
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
fun Caption(text: String, modifier: Modifier = Modifier, size: TextUnit = 13.sp, color: Color = CarColors.Muted) {
    Text(
        text.uppercase(PL),
        modifier = modifier,
        color = color,
        fontSize = size,
        letterSpacing = 1.3.sp,
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
    tint: Color? = null,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(if (filled) CarColors.Text else Color.Transparent)
            .clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = tint ?: if (filled) CarColors.Bg else CarColors.Text, modifier = Modifier.size(iconSize))
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
            Icon(Icons.Rounded.MusicNote, null, tint = CarColors.Text.copy(alpha = 0.75f), modifier = Modifier.size(size * 0.35f))
        }
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) {
    val accent = LocalAccent.current
    Row(
        modifier
            .height(64.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (enabled) accent else accent.copy(alpha = 0.3f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = CarColors.Bg, modifier = Modifier.size(24.dp))
        Text(text, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = CarColors.Bg)
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, height: Dp = 64.dp, icon: ImageVector? = null) {
    Row(
        modifier
            .height(height)
            .clip(RoundedCornerShape(18.dp))
            .background(CarColors.Card)
            .clickable(onClick = onClick)
            .padding(horizontal = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = CarColors.Text, modifier = Modifier.size(24.dp))
        Text(text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun CarChip(text: String, selected: Boolean, onClick: () -> Unit, icon: ImageVector? = null) {
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(24.dp)
    Row(
        Modifier
            .height(48.dp)
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.14f) else CarColors.Card)
            .border(1.dp, if (selected) accent.copy(alpha = 0.4f) else CarColors.Border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = if (selected) accent else CarColors.Text2, modifier = Modifier.size(20.dp))
        Text(text, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = if (selected) accent else CarColors.Text2)
    }
}

@Composable
fun CarSwitch(on: Boolean) {
    val accent = LocalAccent.current
    val x by animateFloatAsState(if (on) 1f else 0f, tween(180), label = "sw")
    Box(
        Modifier
            .size(60.dp, 34.dp)
            .clip(RoundedCornerShape(17.dp))
            .background(if (on) accent else CarColors.Switch),
    ) {
        Box(
            Modifier
                .offset(x = 4.dp + 26.dp * x, y = 4.dp)
                .size(26.dp)
                .clip(CircleShape)
                .background(CarColors.Text),
        )
    }
}

@Composable
fun Spinner(size: Dp = 28.dp) {
    val accent = LocalAccent.current
    val t = rememberInfiniteTransition(label = "spin")
    val a by t.animateFloat(0f, 360f, infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Restart), label = "a")
    Canvas(Modifier.size(size)) {
        val st = Stroke(this.size.minDimension * 0.11f, cap = StrokeCap.Round)
        drawArc(CarColors.Switch, 0f, 360f, false, style = st)
        drawArc(accent, a, 90f, false, style = st)
    }
}

/** Wiersz ustawień: tytuł, opis, element po prawej. */
@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    leading: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    val accent = LocalAccent.current
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(CarColors.Card)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (leading != null) Icon(leading, null, tint = accent, modifier = Modifier.size(26.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(subtitle, fontSize = 15.sp, color = CarColors.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

/** Duży suwak wypełniany kolorem (jasność / głośność). */
@Composable
fun FillSlider(
    value: Float,
    onChange: (Float) -> Unit,
    icon: ImageVector,
    label: String,
    valueText: String,
    modifier: Modifier = Modifier,
) {
    val accent = LocalAccent.current
    var width by remember { mutableIntStateOf(1) }
    val cb by rememberUpdatedState(onChange)
    Box(
        modifier
            .height(76.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(CarColors.Raised)
            .onSizeChanged { width = it.width.coerceAtLeast(1) }
            .pointerInput(Unit) { detectTapGestures { cb((it.x / width).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ -> cb((change.position.x / width).coerceIn(0f, 1f)) }
            },
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(value.coerceIn(0f, 1f)).background(accent.copy(alpha = 0.33f)))
        Row(
            Modifier.fillMaxSize().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(icon, null, tint = CarColors.Text, modifier = Modifier.size(28.dp))
            Text(label, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(valueText, fontSize = 19.sp, color = CarColors.Text2)
        }
    }
}

/** Pole tekstowe w stylu systemu. */
@Composable
fun CarTextField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onDone: () -> Unit = {},
) {
    val accent = LocalAccent.current
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = CarColors.Text, fontSize = 24.sp),
        cursorBrush = SolidColor(accent),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone() }, onNext = { onDone() }, onSearch = { onDone() }, onGo = { onDone() }),
        modifier = modifier
            .height(64.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(CarColors.Card)
            .border(2.dp, if (focused) accent else CarColors.Border, RoundedCornerShape(18.dp))
            .onFocusChanged { focused = it.isFocused },
        decorationBox = { inner ->
            Box(Modifier.fillMaxSize().padding(horizontal = 20.dp), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(placeholder, fontSize = 24.sp, color = Color(0xFF5D6672))
                inner()
            }
        },
    )
}

/** Komunikat na dole ekranu (zamiast systemowego Toasta). */
@Composable
fun ToastHost(modifier: Modifier = Modifier) {
    val msg by Toaster.message.collectAsState()
    var visible by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    LaunchedEffect(msg) {
        val m = msg ?: return@LaunchedEffect
        text = m.first
        visible = true
        delay(2600)
        visible = false
    }
    Box(modifier.fillMaxSize().padding(bottom = 28.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(visible, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 }) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF1E242C))
                    .border(1.dp, CarColors.Switch, RoundedCornerShape(18.dp))
                    .padding(horizontal = 22.dp, vertical = 14.dp),
            ) {
                Text(text, fontSize = 18.sp)
            }
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

/** Logo CAR OS: łuk zegara + wskazówka. arc 0..1, needleDeg obrót wskazówki. */
@Composable
fun CarOsLogo(size: Dp, modifier: Modifier = Modifier, arc: Float = 1f, needleDeg: Float = 0f, accent: Color = LocalAccent.current) {
    Canvas(modifier.size(size)) {
        val u = this.size.minDimension / 108f
        val r = 29.7f * u
        val c = Offset(54f * u, 54f * u)
        drawArc(
            accent, 135f, 270f * arc, false,
            topLeft = Offset(c.x - r, c.y - r),
            size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
            style = Stroke(6.5f * u, cap = StrokeCap.Round),
        )
        val pivot = Offset(54f * u, 58f * u)
        val ang = Math.toRadians((-49.4 + needleDeg))
        val len = 18.4f * u
        val tip = Offset(pivot.x + (len * Math.cos(ang)).toFloat(), pivot.y + (len * Math.sin(ang)).toFloat())
        drawLine(CarColors.Text, pivot, tip, strokeWidth = 5.5f * u, cap = StrokeCap.Round)
        drawCircle(CarColors.Text, 4.2f * u, pivot)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Caption(text, modifier.padding(top = 10.dp, bottom = 2.dp), size = 15.sp)
}

@Composable
fun Swatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(54.dp)
            .clip(CircleShape)
            .background(color)
            .border(3.dp, if (selected) CarColors.Text else Color.Transparent, CircleShape)
            .clickable(onClick = onClick),
    )
}

@Composable
fun WallpaperCard(name: String, brush: Brush, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier
            .height(112.dp)
            .clip(shape)
            .background(brush)
            .border(3.dp, if (selected) accent else CarColors.Border, shape)
            .clickable(onClick = onClick)
            .padding(12.dp),
        contentAlignment = Alignment.BottomStart,
    ) {
        Text(name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun IconBadge(icon: ImageVector, size: Dp = 52.dp) {
    val accent = LocalAccent.current
    Box(
        Modifier.size(size).clip(RoundedCornerShape(16.dp)).background(accent.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = accent, modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
fun Gap(w: Dp = 0.dp, h: Dp = 0.dp) = Box(Modifier.width(w).height(h))
