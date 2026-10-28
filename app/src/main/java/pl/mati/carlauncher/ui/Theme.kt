package pl.mati.carlauncher.ui

import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import pl.mati.carlauncher.data.Prefs

object CarColors {
    val Bg = Color(0xFF0A0C0F)
    val BgDeep = Color(0xFF070809)
    val Dock = Color(0xFF0E1115)
    val Card = Color(0xFF14181E)
    val Card2 = Color(0xFF111418)
    val Panel = Color(0xFF12161B)
    val Raised = Color(0xFF1A1F26)
    val MapBg = Color(0xFF11161C)
    val Border = Color(0xFF1E232B)
    val Text = Color(0xFFEEF1F4)
    val Text2 = Color(0xFFC3C9D0)
    val Muted = Color(0xFF9AA3AE)
    val Dim = Color(0xFF6B7480)
    val Track = Color(0xFF262C35)
    val Switch = Color(0xFF2A313B)
    val Green = Color(0xFF7CF06B)
    val CallGreen = Color(0xFF2BB673)
    val Orange = Color(0xFFFFB03A)
    val Red = Color(0xFFE5484D)
    val RedZone = Color(0xFF3A1418)
    val Overlay = Color(0xE60A0C0F)

    val AccentOptions = listOf(
        0xFF2FD3F0.toInt(),
        0xFFFF6A2B.toInt(),
        0xFF7CF06B.toInt(),
        0xFFB18CFF.toInt(),
        0xFFFFD23F.toInt(),
        0xFFE8EAED.toInt(),
    )
}

/** Tapety systemu (nazwa + pędzel). */
val Wallpapers: List<Pair<String, Brush>> = listOf(
    "Grafit" to Brush.linearGradient(listOf(Color(0xFF12161B), Color(0xFF0A0C0F)), Offset(0f, 0f), Offset(600f, 1400f)),
    "Noc" to Brush.radialGradient(listOf(Color(0xFF16304A), Color(0xFF0A0C0F)), Offset(1000f, 100f), 900f),
    "Asfalt" to Brush.linearGradient(listOf(Color(0xFF1B1E22), Color(0xFF0A0C0F), Color(0xFF17130F)), Offset(0f, 0f), Offset(1280f, 720f)),
    "Zachód" to Brush.radialGradient(listOf(Color(0xFF3A1E14), Color(0xFF0A0C0F)), Offset(250f, 800f), 900f),
)

val LocalAccent = staticCompositionLocalOf { Color(0xFF2FD3F0) }

/** Wąski krój na liczby (systemowy, bez pobierania czcionek). */
val Condensed: FontFamily = FontFamily(Typeface.create("sans-serif-condensed", Typeface.BOLD))

/** Projekt jest rysowany w 1280 dp szerokości i skalowany do ekranu, niezależnie od DPI radia. */
const val DESIGN_WIDTH = 1280f

@Composable
fun CarRoot(content: @Composable () -> Unit) {
    val accentInt by Prefs.accent.collectAsState()
    MaterialTheme(colorScheme = darkColorScheme()) {
        CompositionLocalProvider(
            LocalAccent provides Color(accentInt),
            LocalContentColor provides CarColors.Text,
        ) {
            BoxWithConstraints(Modifier.fillMaxSize().background(CarColors.Bg)) {
                val scale = constraints.maxWidth / DESIGN_WIDTH
                val designHeight = constraints.maxHeight / scale
                CompositionLocalProvider(LocalDensity provides Density(scale, 1f)) {
                    Box(Modifier.size(DESIGN_WIDTH.dp, designHeight.dp)) {
                        content()
                    }
                }
            }
        }
    }
}
