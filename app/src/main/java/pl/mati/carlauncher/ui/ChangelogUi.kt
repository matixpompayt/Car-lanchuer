package pl.mati.carlauncher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.mati.carlauncher.BuildConfig
import pl.mati.carlauncher.data.Changelog

/** Tytuł i punkty jednego wydania. */
@Composable
fun ChangelogPoints(e: Changelog.Entry, size: TextUnit = 18.sp) {
    val accent = LocalAccent.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        e.title?.let { Text(it, fontSize = size * 1.15f, fontWeight = FontWeight.SemiBold, color = CarColors.Text) }
        e.points.forEach { p ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("•", fontSize = size, color = accent, fontWeight = FontWeight.Bold)
                Text(p, fontSize = size, color = CarColors.Text2)
            }
        }
    }
}

/** Karta wersji na liście zmian. */
@Composable
fun ChangelogCard(e: Changelog.Entry, badge: String? = null) {
    val accent = LocalAccent.current
    val current = e.version == BuildConfig.VERSION_NAME
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).carSurface(RoundedCornerShape(20.dp), CarColors.Card)
            .then(if (current) Modifier.border(1.5.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(20.dp)) else Modifier)
            .padding(horizontal = 22.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("CAR OS ${e.version}", fontSize = 15.sp, color = CarColors.Muted, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)
            e.date?.let { Text(it, fontSize = 15.sp, color = CarColors.Dim) }
            (badge ?: if (current) "ZAINSTALOWANA" else null)?.let {
                Text(
                    it, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = accent,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(accent.copy(alpha = 0.14f)).padding(horizontal = 9.dp, vertical = 3.dp),
                )
            }
        }
        ChangelogPoints(e)
    }
}

/** Raz po aktualizacji: co weszło w tej wersji. */
@Composable
fun WhatsNewOverlay() {
    val e by Changelog.whatsNew.collectAsState()
    val phase by UiState.phase.collectAsState()
    val show = e != null && phase == Phase.MAIN
    AnimatedVisibility(show, enter = fadeIn() + scaleIn(initialScale = 0.96f), exit = fadeOut()) {
        val entry = e ?: return@AnimatedVisibility
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { Changelog.markSeen() },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.width(760.dp).clip(RoundedCornerShape(30.dp)).carSurface(RoundedCornerShape(30.dp), CarColors.Panel, heavy = true)
                    .border(1.dp, CarColors.Border, RoundedCornerShape(30.dp))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    CarOsLogo(64.dp)
                    Column {
                        Text("Nowość w CAR OS ${entry.version}", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = CarColors.Text)
                        Text("Aktualizacja zainstalowana", fontSize = 17.sp, color = CarColors.Muted)
                    }
                }
                Column(Modifier.heightIn(max = 330.dp).verticalScroll(rememberScrollState())) {
                    ChangelogPoints(entry, 20.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    SecondaryButton("Wszystkie zmiany", {
                        Changelog.markSeen()
                        UiState.settingsPage.value = "update"
                        UiState.go(Screen.SETTINGS)
                    }, Modifier.weight(1f))
                    PrimaryButton("Super", { Changelog.markSeen() }, Modifier.weight(1f))
                }
            }
        }
    }
}
