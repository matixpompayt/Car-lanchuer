package pl.mati.carlauncher.ui

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Motyw systemu: zmienia układ, kolory i kształty całego CAR OS. */
data class UiTheme(val id: String, val name: String, val desc: String)

val UI_THEMES = listOf(
    UiTheme("classic", "Klasyczny", "Boczny pasek, karty z ramkami, tapety – CAR OS 1"),
    UiTheme("minimal", "Minimal", "Pasek na dole, czerń / biel bez ramek, duża prędkość i mapa – jak w Tesli"),
)

@Composable
fun ThemeCard(t: UiTheme, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Column(
        modifier.clip(RoundedCornerShape(22.dp)).background(CarColors.Card)
            .border(2.dp, if (selected) accent else Color.Transparent, RoundedCornerShape(22.dp))
            .clickable(onClick = onClick).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ThemePreview(t.id, Modifier.fillMaxWidth().height(120.dp))
        Text(t.name + if (selected) " ✓" else "", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = if (selected) accent else CarColors.Text)
        Text(t.desc, fontSize = 14.sp, color = CarColors.Muted, maxLines = 2)
    }
}

/** Mała makieta układu motywu. */
@Composable
private fun ThemePreview(id: String, modifier: Modifier) {
    val accent = LocalAccent.current
    when (id) {
        "minimal" -> Column(modifier.clip(RoundedCornerShape(14.dp)).background(Color.Black).padding(6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Column(Modifier.width(54.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.width(34.dp).height(16.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFFF5F5F7)))
                    Box(Modifier.width(44.dp).height(5.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF3A3A3E)))
                    Box(Modifier.width(40.dp).height(5.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF3A3A3E)))
                    Box(Modifier.weight(1f))
                    Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFF1C1C1F)))
                }
                Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(10.dp)).background(Color(0xFF2A2F36)))
            }
            Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF0B0B0C)), horizontalArrangement = Arrangement.Center) {
                repeat(5) { i -> Box(Modifier.padding(horizontal = 4.dp, vertical = 4.dp).width(8.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(if (i == 0) accent else Color(0xFF8E8E93))) }
            }
        }
        else -> Row(modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xFF0A0C0F)), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Column(Modifier.width(16.dp).fillMaxHeight().background(Color(0xFF0E1115)).padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(5) { i -> Box(Modifier.padding(horizontal = 4.dp).fillMaxWidth().height(7.dp).clip(RoundedCornerShape(2.dp)).background(if (i == 0) accent else Color(0xFF6B7480))) }
            }
            Row(Modifier.weight(1f).fillMaxHeight().padding(6.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.weight(1.5f).fillMaxHeight().clip(RoundedCornerShape(6.dp)).background(Color(0xFF1A2230)).border(1.dp, Color(0xFF1E232B), RoundedCornerShape(6.dp)))
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Color(0xFF14181E)).border(1.dp, Color(0xFF1E232B), RoundedCornerShape(6.dp)))
                    Row(Modifier.height(22.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        repeat(3) { Box(Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(4.dp)).background(Color(0xFF14181E)).border(1.dp, Color(0xFF1E232B), RoundedCornerShape(4.dp))) }
                    }
                }
            }
        }
    }
}
