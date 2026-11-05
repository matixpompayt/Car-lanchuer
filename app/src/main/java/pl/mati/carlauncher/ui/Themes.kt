package pl.mati.carlauncher.ui

import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Gdzie jest nawigacja między ekranami. */
enum class NavStyle { DOCK, BOTTOM, TOP_TABS, RIGHT_RAIL, FLOATING }

/** Układ ekranu głównego. */
enum class HomeStyle { CLASSIC, MINIMAL, VW, SPORT, FULLMAP, RETRO }

/** Tło całego systemu. */
enum class BgStyle { WALLPAPER, SOLID, VW, SPORT, RETRO }

private val NUM_DEFAULT = FontFamily(Typeface.create("sans-serif-condensed", Typeface.BOLD))
private val NUM_SPORT = FontFamily(Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC))
private val NUM_RETRO = FontFamily(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD))
private val NUM_VW = FontFamily(Typeface.create("sans-serif-condensed", Typeface.NORMAL))
private val BODY_CONDENSED = FontFamily(Typeface.create("sans-serif-condensed", Typeface.NORMAL))
val MONO: FontFamily = FontFamily(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD))

/**
 * Motyw systemu: zmienia układ (nawigacja, ekran główny), kolory, kształty, czcionki i tło całego CAR OS.
 */
data class SysTheme(
    val id: String,
    val name: String,
    val desc: String,
    val nav: NavStyle,
    val home: HomeStyle,
    val bg: BgStyle,
    /** null = zestaw klasyczny (ciemny + wybrany jasny wariant). */
    val dark: Palette?,
    /** null = zawsze [dark] (motyw tylko nocny). */
    val light: Palette?,
    /** Zmiana zaokrągleń kart i przycisków (ujemna = ostrzejsze). */
    val corner: Dp = 0.dp,
    val borders: Boolean = true,
    val borderWidth: Dp = 1.dp,
    val pill: Boolean = false,
    /** Kolor akcentu narzucony przez motyw (null = wybrany w ustawieniach). */
    val accent: Color? = null,
    val numFont: FontFamily = NUM_DEFAULT,
    val bodyFont: FontFamily? = null,
    val compactStatus: Boolean = false,
) {
    val insetStart: Dp get() = if (nav == NavStyle.DOCK) 124.dp else 24.dp
    val insetEnd: Dp get() = if (nav == NavStyle.RIGHT_RAIL) 124.dp else 24.dp
    val insetBottom: Dp get() = if (nav == NavStyle.BOTTOM || nav == NavStyle.FLOATING) 110.dp else 28.dp
    val insetTop: Dp get() = when (nav) { NavStyle.TOP_TABS -> 58.dp; NavStyle.RIGHT_RAIL -> 16.dp; else -> 0.dp }
    fun radius(r: Dp): Dp = (r + corner).coerceAtLeast(3.dp)
}

object SysThemes {
    val CLASSIC = SysTheme(
        "classic", "Klasyczny", "Boczny pasek, karty z ramkami, tapety – CAR OS 1",
        NavStyle.DOCK, HomeStyle.CLASSIC, BgStyle.WALLPAPER, null, null,
    )
    val MINIMAL = SysTheme(
        "minimal", "Minimal", "Pasek na dole, czerń / biel bez ramek, duża prędkość i mapa – jak w Tesli",
        NavStyle.BOTTOM, HomeStyle.MINIMAL, BgStyle.SOLID, Palettes.DARK2, Palettes.LIGHT2,
        corner = 6.dp, borders = false, pill = true, compactStatus = true,
    )
    val VW = SysTheme(
        "vw", "VW Nocny", "Niebieskie podświetlenie i czerwone wskazówki jak w Touranie, zegary, zakładki u góry (zawsze ciemny)",
        NavStyle.TOP_TABS, HomeStyle.VW, BgStyle.VW, Palettes.VW, null,
        corner = (-12).dp, borders = true, accent = Color(0xFFFF2D2D), numFont = NUM_VW, compactStatus = true,
    )
    val SPORT = SysTheme(
        "sport", "Sport", "Karbon i żółć, shift-light z obrotów, telemetria, pasek po prawej (zawsze ciemny)",
        NavStyle.RIGHT_RAIL, HomeStyle.SPORT, BgStyle.SPORT, Palettes.SPORT, null,
        corner = (-14).dp, borders = true, accent = Color(0xFFFFD400), numFont = NUM_SPORT, bodyFont = BODY_CONDENSED, compactStatus = true,
    )
    val FULLMAP = SysTheme(
        "fullmap", "Pełna mapa", "Mapa na cały ekran, wszystko w pływających okienkach, menu w kapsule na dole",
        NavStyle.FLOATING, HomeStyle.FULLMAP, BgStyle.SOLID, Palettes.DARK2, Palettes.LIGHT2,
        corner = 4.dp, borders = false, pill = true, compactStatus = true,
    )
    val RETRO = SysTheme(
        "retro", "Retro 80s", "Neon, zachód słońca i siatka, kaseta zamiast odtwarzacza, cyfrowe liczby (zawsze ciemny)",
        NavStyle.DOCK, HomeStyle.RETRO, BgStyle.RETRO, Palettes.RETRO, null,
        corner = (-4).dp, borders = true, borderWidth = 1.5.dp, accent = Color(0xFF00E5FF), numFont = NUM_RETRO, bodyFont = BODY_CONDENSED,
    )

    val ALL = listOf(CLASSIC, MINIMAL, VW, SPORT, FULLMAP, RETRO)
    fun byId(id: String) = ALL.firstOrNull { it.id == id } ?: CLASSIC
}

@Composable
fun ThemeCard(t: SysTheme, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Column(
        modifier.clip(RoundedCornerShape(22.dp)).background(CarColors.Card)
            .border(2.dp, if (selected) accent else Color.Transparent, RoundedCornerShape(22.dp))
            .clickable(onClick = onClick).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ThemePreview(t, Modifier.fillMaxWidth().height(116.dp))
        Text(t.name + if (selected) " ✓" else "", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = if (selected) accent else CarColors.Text)
        Text(t.desc, fontSize = 13.sp, color = CarColors.Muted, maxLines = 3)
    }
}

/** Mała makieta motywu: kolory, położenie menu i układ ekranu głównego. */
@Composable
private fun ThemePreview(t: SysTheme, modifier: Modifier) {
    val p = t.dark ?: Palettes.DARK
    val acc = t.accent ?: LocalAccent.current
    val bg = when (t.bg) {
        BgStyle.RETRO -> androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFF12052A), Color(0xFF3A0A4A), Color(0xFF12052A)))
        else -> androidx.compose.ui.graphics.SolidColor(p.bg)
    }
    val r = t.radius(6.dp)
    val line = if (t.borders) (if (t.bg == BgStyle.RETRO) Color(0xFFFF2E97) else p.border) else Color.Transparent
    @Composable
    fun block(m: Modifier, c: Color = p.card) = Box(m.clip(RoundedCornerShape(r)).background(c).border(1.dp, line, RoundedCornerShape(r)))
    @Composable
    fun icons(vertical: Boolean, m: Modifier) {
        if (vertical) Column(m.padding(4.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(5) { i -> Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(2.dp)).background(if (i == 0) acc else p.muted)) }
        } else Row(m.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
            repeat(5) { i -> Box(Modifier.width(10.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(if (i == 0) acc else p.muted)) }
        }
    }
    @Composable
    fun home(m: Modifier) {
        when (t.home) {
            HomeStyle.VW -> Row(m.padding(5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(30.dp).height(30.dp).clip(RoundedCornerShape(50)).border(2.dp, p.text2, RoundedCornerShape(50)))
                block(Modifier.weight(1f).fillMaxHeight(), p.mapBg)
                Box(Modifier.width(30.dp).height(30.dp).clip(RoundedCornerShape(50)).border(2.dp, p.text2, RoundedCornerShape(50)))
            }
            HomeStyle.SPORT -> Row(m.padding(5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Box(Modifier.width(36.dp).height(16.dp).background(p.text))
                    repeat(3) { Box(Modifier.fillMaxWidth(0.4f + it * 0.2f).height(4.dp).background(acc)) }
                }
                block(Modifier.weight(1f).fillMaxHeight(), p.mapBg)
            }
            HomeStyle.FULLMAP -> Box(m.background(Color(0xFF2A2F36))) {
                Box(Modifier.align(Alignment.TopCenter).padding(4.dp).width(40.dp).height(9.dp).clip(RoundedCornerShape(5.dp)).background(p.overlay))
                Column(Modifier.align(Alignment.CenterStart).padding(4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    repeat(3) { Box(Modifier.width(20.dp).height(8.dp).clip(RoundedCornerShape(4.dp)).background(p.overlay)) }
                }
            }
            HomeStyle.RETRO -> Row(m.padding(5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                block(Modifier.weight(1.3f).fillMaxHeight(), p.mapBg)
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.fillMaxWidth().height(14.dp).background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color(0xFFFF2E97), Color(0xFFFFB000)))))
                    block(Modifier.weight(1f).fillMaxWidth())
                }
            }
            HomeStyle.MINIMAL -> Row(m.padding(5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Column(Modifier.width(40.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Box(Modifier.width(28.dp).height(14.dp).background(p.text))
                    Box(Modifier.width(34.dp).height(4.dp).background(p.dim))
                    Box(Modifier.weight(1f))
                    block(Modifier.fillMaxWidth().height(10.dp), p.card2)
                }
                block(Modifier.weight(1f).fillMaxHeight(), Color(0xFF2A2F36))
            }
            HomeStyle.CLASSIC -> Row(m.padding(5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                block(Modifier.weight(1.5f).fillMaxHeight(), Color(0xFF1A2230))
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    block(Modifier.weight(1f).fillMaxWidth())
                    Row(Modifier.height(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) { repeat(3) { block(Modifier.weight(1f).fillMaxSize()) } }
                }
            }
        }
    }
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(bg)) {
        when (t.nav) {
            NavStyle.DOCK -> Row(Modifier.fillMaxSize()) {
                icons(true, Modifier.width(16.dp).fillMaxHeight().background(p.dock))
                home(Modifier.weight(1f).fillMaxHeight())
            }
            NavStyle.RIGHT_RAIL -> Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().height(5.dp)) {
                    repeat(12) { i -> Box(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 1.dp).background(if (i < 6) Color(0xFF30D158) else if (i < 9) acc else if (i < 10) Color(0xFFFF1E1E) else p.dim)) }
                }
                Row(Modifier.weight(1f)) {
                    home(Modifier.weight(1f).fillMaxHeight())
                    icons(true, Modifier.width(16.dp).fillMaxHeight().background(p.dock))
                }
            }
            NavStyle.TOP_TABS -> Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().height(12.dp).background(p.dock).padding(horizontal = 4.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(5) { i -> Box(Modifier.weight(1f).fillMaxHeight().background(if (i == 0) acc else p.text2)) }
                }
                home(Modifier.weight(1f).fillMaxWidth())
            }
            NavStyle.BOTTOM -> Column(Modifier.fillMaxSize()) {
                home(Modifier.weight(1f).fillMaxWidth())
                icons(false, Modifier.fillMaxWidth().height(14.dp).background(p.dock))
            }
            NavStyle.FLOATING -> Box(Modifier.fillMaxSize()) {
                home(Modifier.fillMaxSize())
                icons(false, Modifier.align(Alignment.BottomCenter).padding(bottom = 5.dp).width(70.dp).height(13.dp).clip(RoundedCornerShape(7.dp)).background(p.overlay))
            }
        }
    }
}
