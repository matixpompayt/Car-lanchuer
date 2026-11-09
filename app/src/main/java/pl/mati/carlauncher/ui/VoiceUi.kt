package pl.mati.carlauncher.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.mati.carlauncher.data.VoiceCommands

/** Przycisk mikrofonu (w pasku bocznym): prosi o zgodę na mikrofon i zaczyna słuchać. */
@Composable
fun rememberVoiceStarter(): () -> Unit {
    val ctx = LocalContext.current
    var pendingStart by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (pendingStart && res[android.Manifest.permission.RECORD_AUDIO] == true) VoiceCommands.listen(ctx)
        else if (pendingStart) Toaster.show("Bez zgody na mikrofon komendy głosowe nie działają")
        pendingStart = false
    }
    return {
        if (VoiceCommands.state.value.phase == VoiceCommands.Phase.LISTENING) VoiceCommands.close()
        else if (VoiceCommands.hasMic(ctx)) VoiceCommands.listen(ctx)
        else { pendingStart = true; launcher.launch(VoiceCommands.permissions) }
    }
}

/** Pasek „Słucham…” na dole ekranu: poziom głosu, rozpoznany tekst, odpowiedź. */
@Composable
fun VoiceOverlay() {
    val st by VoiceCommands.state.collectAsState()
    val accent = LocalAccent.current
    val show = st.phase != VoiceCommands.Phase.IDLE
    var last by remember { mutableStateOf(st) }
    if (show) last = st
    Box(Modifier.fillMaxSize().padding(start = CarColors.theme.insetStart, end = CarColors.theme.insetEnd, bottom = CarColors.theme.insetBottom), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(show, enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it }) {
            val s = last
            val level by animateFloatAsState(s.level, tween(120), label = "lvl")
            Row(
                Modifier.widthIn(min = 520.dp, max = 900.dp).clip(RoundedCornerShape(28.dp)).carSurface(RoundedCornerShape(28.dp), CarColors.Panel, heavy = true)
                    .border(1.5.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(28.dp))
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (s.phase == VoiceCommands.Phase.LISTENING) {
                        Box(
                            Modifier.size(64.dp).graphicsLayer { scaleX = 1f + level * 0.5f; scaleY = 1f + level * 0.5f; alpha = 0.35f }
                                .clip(CircleShape).background(accent),
                        )
                    }
                    Box(Modifier.size(56.dp).clip(CircleShape).background(accent), contentAlignment = Alignment.Center) {
                        Icon(
                            when (s.phase) {
                                VoiceCommands.Phase.DONE -> Icons.Rounded.CheckCircle
                                VoiceCommands.Phase.ERROR -> Icons.Rounded.ErrorOutline
                                else -> Icons.Rounded.Mic
                            },
                            null, tint = CarColors.Bg, modifier = Modifier.size(32.dp),
                        )
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        when (s.phase) {
                            VoiceCommands.Phase.LISTENING -> if (s.heard.isBlank()) "Słucham…" else s.heard
                            VoiceCommands.Phase.THINKING -> s.reply.ifBlank { s.heard.ifBlank { "Chwila…" } }
                            else -> s.reply
                        },
                        fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    val sub = when (s.phase) {
                        VoiceCommands.Phase.LISTENING -> if (s.heard.isBlank()) "np. „jedź do domu”, „następna piosenka”, „zadzwoń do Mamy”, „ciszej”" else ""
                        VoiceCommands.Phase.DONE, VoiceCommands.Phase.ERROR -> if (s.heard.isNotBlank()) "„${s.heard}”" else ""
                        else -> ""
                    }
                    if (sub.isNotBlank()) Text(sub, fontSize = 16.sp, color = CarColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(Modifier.size(48.dp).clip(CircleShape).carSurface(CircleShape, CarColors.Raised).clickable { VoiceCommands.close() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Close, "Zamknij", tint = CarColors.Text, modifier = Modifier.size(26.dp))
                }
            }
        }
    }
}
