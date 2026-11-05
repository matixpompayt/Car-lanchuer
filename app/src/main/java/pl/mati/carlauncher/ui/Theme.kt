package pl.mati.carlauncher.ui

import androidx.compose.runtime.remember
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

    /** Wygląd 2.0 – noc: czysta czerń i neutralne szarości, karty bez obramowań. */
    val DARK2 = Palette(
        "dark2", "Czerń", false,
        Color(0xFF000000), Color(0xFF000000), Color(0xFF0B0B0C),
        Color(0xFF151517), Color(0xFF1C1C1F), Color(0xFF18181B), Color(0xFF232327), Color(0xFF101012),
        Color(0x00000000), Color(0xFFF5F5F7), Color(0xFFC7C7CC), Color(0xFF8E8E93), Color(0xFF636366),
        Color(0xFF2C2C30), Color(0xFF3A3A3E), Color(0xE6101012),
        Color(0xFF30D158), Color(0xFFFF9F0A), Color(0xFFFF453A), Color(0xFF3A1512), Color(0xFF5A1A16),
        Color(0xFF13293D), Color(0xFF64D2FF),
    )

    /** Wygląd 2.0 – dzień: jasnoszary i biały, bez obramowań. */
    val LIGHT2 = Palette(
        "light2", "Biel", true,
        Color(0xFFE9E9EC), Color(0xFFDEDEE2), Color(0xFFF7F7F9),
        Color(0xFFFFFFFF), Color(0xFFF4F4F6), Color(0xFFFAFAFB), Color(0xFFFFFFFF), Color(0xFFE2E2E6),
        Color(0x00000000), Color(0xFF111114), Color(0xFF3A3A3F), Color(0xFF6E6E73), Color(0xFF8E8E93),
        Color(0xFFD5D5DA), Color(0xFFC7C7CC), Color(0xEBFFFFFF),
        Color(0xFF248A3D), Color(0xFFC75F00), Color(0xFFD70015), Color(0xFFF6D5D3), Color(0xFFF2B8B5),
        Color(0xFFD3E4F2), Color(0xFF0B63A8),
    )

    /** Płynne przejście między zestawami kolorów (t = 0…1). */
    fun lerp(a: Palette, b: Palette, t: Float): Palette {
        fun c(x: Color, y: Color) = androidx.compose.ui.graphics.lerp(x, y, t)
        return Palette(
            b.id, b.name, if (t < 0.5f) a.light else b.light,
            c(a.bg, b.bg), c(a.bgDeep, b.bgDeep), c(a.dock, b.dock),
            c(a.card, b.card), c(a.card2, b.card2), c(a.panel, b.panel), c(a.raised, b.raised), c(a.mapBg, b.mapBg),
            c(a.border, b.border), c(a.text, b.text), c(a.text2, b.text2), c(a.muted, b.muted), c(a.dim, b.dim),
            c(a.track, b.track), c(a.switch, b.switch), c(a.overlay, b.overlay),
            c(a.green, b.green), c(a.orange, b.orange), c(a.red, b.red), c(a.redZone, b.redZone), c(a.blink, b.blink),
            c(a.rainBg, b.rainBg), c(a.rainText, b.rainText),
        )
    }
    fun light(id: String) = LIGHT.firstOrNull { it.id == id } ?: PEARL
}

object CarColors {
    /** Aktualny zestaw kolorów (zmiana = przemalowanie całego interfejsu). */
    var palette by mutableStateOf(Palettes.DARK)
    /** Wygląd 2.0 (test): inny układ (pasek na dole), kształty i kolory. */
    var ui2 by mutableStateOf(false)

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
    val anim by Prefs.animations.collectAsState()
    val ui2 by Prefs.ui2.collectAsState()
    CarColors.ui2 = ui2
    val target = if (ui2) (if (light) Palettes.LIGHT2 else Palettes.DARK2) else if (light) Palettes.light(variant) else Palettes.DARK
    var first by remember { mutableStateOf(true) }
    // dzień ↔ noc: wszystkie kolory przechodzą płynnie (ok. 1 s), przy starcie od razu właściwy tryb
    LaunchedEffect(target) {
        val from = CarColors.palette
        if (first || !anim || from == target) {
            first = false
            CarColors.palette = target
            return@LaunchedEffect
        }
        val a = androidx.compose.animation.core.Animatable(0f)
        a.animateTo(1f, androidx.compose.animation.core.tween(1000, easing = androidx.compose.animation.core.FastOutSlowInEasing)) {
            CarColors.palette = Palettes.lerp(from, target, value)
        }
        CarColors.palette = target
    }
    MaterialTheme(colorScheme = if (CarColors.isLight) lightColorScheme() else darkColorScheme()) {
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
