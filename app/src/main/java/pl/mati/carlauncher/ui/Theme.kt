package pl.mati.carlauncher.ui

import pl.mati.carlauncher.data.ThemeManager
import androidx.compose.ui.graphics.lerp
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.lightColorScheme
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

/** Zestaw kolorów interfejsu – ciemny (noc) albo jeden z jasnych (dzień). Układ ekranów ten sam. */
data class Palette(
    val id: String,
    val name: String,
    val light: Boolean,
    val bg: Color, val bgDeep: Color, val dock: Color,
    val card: Color, val card2: Color, val panel: Color, val raised: Color, val mapBg: Color,
    val border: Color, val text: Color, val text2: Color, val muted: Color, val dim: Color,
    val track: Color, val switch: Color, val overlay: Color,
    val green: Color, val orange: Color, val red: Color, val redZone: Color, val blink: Color,
    val rainBg: Color, val rainText: Color,
)

object Palettes {
    val DARK = Palette(
        "dark", "Ciemny", false,
        Color(0xFF0A0C0F), Color(0xFF070809), Color(0xFF0E1115),
        Color(0xFF14181E), Color(0xFF111418), Color(0xFF12161B), Color(0xFF1A1F26), Color(0xFF11161C),
        Color(0xFF1E232B), Color(0xFFEEF1F4), Color(0xFFC3C9D0), Color(0xFF9AA3AE), Color(0xFF6B7480),
        Color(0xFF262C35), Color(0xFF2A313B), Color(0xE60A0C0F),
        Color(0xFF7CF06B), Color(0xFFFFB03A), Color(0xFFE5484D), Color(0xFF3A1418), Color(0xFF5A1418),
        Color(0xFF15324A), Color(0xFF6CC4FF),
    )

    /** Perłowy: chłodny, jasnoszary – nie biały, żeby nie raził. */
    val PEARL = Palette(
        "perla", "Perłowy", true,
        Color(0xFFD7DCE2), Color(0xFFCBD1D8), Color(0xFFC9D0D8),
        Color(0xFFE8ECF0), Color(0xFFE0E5EA), Color(0xFFE3E7EC), Color(0xFFF0F3F6), Color(0xFFDCE1E6),
        Color(0xFFC1C9D2), Color(0xFF151A21), Color(0xFF2D3540), Color(0xFF586370), Color(0xFF7A8490),
        Color(0xFFC3CBD4), Color(0xFFB4BDC8), Color(0xEBE8ECF0),
        Color(0xFF2C8C3A), Color(0xFFC77708), Color(0xFFD0383E), Color(0xFFF1CDCF), Color(0xFFF0B3B6),
        Color(0xFFCADDEC), Color(0xFF1D6A9E),
    )

    /** Piaskowy: ciepły, kremowo-szary. */
    val SAND = Palette(
        "piasek", "Piaskowy", true,
        Color(0xFFDBD5CB), Color(0xFFD0C9BE), Color(0xFFCDC5B9),
        Color(0xFFEAE5DD), Color(0xFFE3DDD4), Color(0xFFE5DFD6), Color(0xFFF2EEE8), Color(0xFFE0DAD0),
        Color(0xFFC6BDB0), Color(0xFF211C16), Color(0xFF3A332A), Color(0xFF6A6154), Color(0xFF887F72),
        Color(0xFFC9C0B3), Color(0xFFBBB1A3), Color(0xEBEAE5DD),
        Color(0xFF3A8A35), Color(0xFFBF6E05), Color(0xFFC9393B), Color(0xFFF0D0C8), Color(0xFFEDB7AE),
        Color(0xFFD3DDE3), Color(0xFF2A6890),
    )

    /** Mgła: ciemniejszy jasny – najmniej odblasków. */
    val MIST = Palette(
        "mgla", "Mgła", true,
        Color(0xFFC2C9D1), Color(0xFFB7BFC8), Color(0xFFB4BCC6),
        Color(0xFFD4DAE0), Color(0xFFCDD3DA), Color(0xFFCFD5DC), Color(0xFFDDE2E7), Color(0xFFC8CFD6),
        Color(0xFFA8B1BC), Color(0xFF11161C), Color(0xFF27303A), Color(0xFF4B5562), Color(0xFF68727E),
        Color(0xFFAAB3BE), Color(0xFF9DA7B3), Color(0xEBD4DAE0),
        Color(0xFF25803A), Color(0xFFB36A06), Color(0xFFC23338), Color(0xFFE8C4C7), Color(0xFFE2A8AC),
        Color(0xFFBFD2E1), Color(0xFF175F92),
    )

    val LIGHT = listOf(PEARL, SAND, MIST)
    fun light(id: String) = LIGHT.firstOrNull { it.id == id } ?: PEARL
}

object CarColors {
    /** Aktualny zestaw kolorów (zmiana = przemalowanie całego interfejsu). */
    var palette by mutableStateOf(Palettes.DARK)

    val isLight get() = palette.light
    val Bg get() = palette.bg
    val BgDeep get() = palette.bgDeep
    val Dock get() = palette.dock
    val Card get() = palette.card
    val Card2 get() = palette.card2
    val Panel get() = palette.panel
    val Raised get() = palette.raised
    val MapBg get() = palette.mapBg
    val Border get() = palette.border
    val Text get() = palette.text
    val Text2 get() = palette.text2
    val Muted get() = palette.muted
    val Dim get() = palette.dim
    val Track get() = palette.track
    val Switch get() = palette.switch
    val Green get() = palette.green
    val CallGreen = Color(0xFF2BB673)
    val Orange get() = palette.orange
    val Red get() = palette.red
    val RedZone get() = palette.redZone
    val Blink get() = palette.blink
    val Overlay get() = palette.overlay
    val RainBg get() = palette.rainBg
    val RainText get() = palette.rainText

    val AccentOptions = listOf(
        0xFF2FD3F0.toInt(),
        0xFFFF6A2B.toInt(),
        0xFF7CF06B.toInt(),
        0xFFB18CFF.toInt(),
        0xFFFFD23F.toInt(),
        0xFFE8EAED.toInt(),
    )

    /** Akcent na jasnym tle: przyciemniony, żeby był czytelny. */
    fun accentFor(c: Color, light: Boolean): Color {
        if (!light) return c
        val lum = 0.299f * c.red + 0.587f * c.green + 0.114f * c.blue
        return if (lum > 0.85f) Color(0xFF3A4450) else lerp(c, Color.Black, if (lum > 0.6f) 0.38f else 0.22f)
    }
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
    val light by ThemeManager.light.collectAsState()
    val variant by Prefs.lightVariant.collectAsState()
    val pal = if (light) Palettes.light(variant) else Palettes.DARK
    LaunchedEffect(pal) { CarColors.palette = pal }
    MaterialTheme(colorScheme = if (pal.light) lightColorScheme() else darkColorScheme()) {
        CompositionLocalProvider(
            LocalAccent provides CarColors.accentFor(Color(accentInt), CarColors.isLight),
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
