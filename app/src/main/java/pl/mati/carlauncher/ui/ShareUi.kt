package pl.mati.carlauncher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.mati.carlauncher.data.ConnectRepository
import pl.mati.carlauncher.data.ParkingRepository
import pl.mati.carlauncher.data.RouteRepository
import pl.mati.carlauncher.data.TripShare
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Przycisk w nawigacji: udostępnij jazdę na żywo / udostępniasz (otwiera okno z kodem QR). */
@Composable
fun ShareButton(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val s by TripShare.session.collectAsState()
    SecondaryButton(
        if (s != null) "Udostępniasz jazdę – kod QR" else "Udostępnij jazdę na żywo",
        onOpen, modifier, icon = Icons.Rounded.Share,
    )
}

/**
 * Panel udostępniania: bez sesji – wybór (do celu / 1 h / 4 h); z sesją – kod QR linku
 * (zeskanuj telefonem pasażera albo wyślij z CAR OS Connect) i „Zakończ”.
 */
@Composable
fun ShareDialog(onDismiss: () -> Unit) {
    val accent = LocalAccent.current
    val s by TripShare.session.collectAsState()
    val route by RouteRepository.route.collectAsState()
    val paired by ConnectRepository.paired.collectAsState()
    val cur = s
    CarPanel(
        onDismiss, "Jazda na żywo",
        if (cur == null) "Bliscy zobaczą w przeglądarce auto na mapie, trasę i godzinę przyjazdu – bez instalowania aplikacji"
        else "Udostępniasz – link jest zaszyfrowany, kto go nie ma, nic nie zobaczy",
        Icons.Rounded.Share,
        actions = { ctl ->
            if (cur == null) PanelButton("Anuluj", { ctl.dismiss() }, Modifier.weight(1f))
            else {
                PanelButton(
                    "Zakończ udostępnianie", { TripShare.stop("stopped"); Toaster.show("Udostępnianie jazdy zakończone"); ctl.dismiss() },
                    Modifier.weight(1.3f), icon = Icons.Rounded.Close, danger = true,
                )
                PanelButton("Gotowe", { ctl.dismiss() }, Modifier.weight(1f), primary = true)
            }
        },
    ) {
        if (cur == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (route != null) ShareOption(Icons.Rounded.Flag, "Do celu", "Kończy się samo po dojechaniu", true, Modifier.weight(1f)) { TripShare.start(minutes = null) }
                ShareOption(Icons.Rounded.Timer, "Na 1 h", "Potem link wygaśnie", route == null, Modifier.weight(1f)) { TripShare.start(minutes = 60) }
                ShareOption(Icons.Rounded.Schedule, "Na 4 h", "Na dłuższą trasę", false, Modifier.weight(1f)) { TripShare.start(minutes = 240) }
            }
            Text("Po wybraniu pokaże się kod QR – zeskanuj go telefonem i wyślij link komu chcesz.", fontSize = 16.sp, color = CarColors.Muted)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                val qr = remember(cur.link) { ParkingRepository.qr(cur.link, 420)?.asImageBitmap() }
                if (qr != null) Box(Modifier.size(250.dp).clip(RoundedCornerShape(22.dp)).background(Color.White).padding(14.dp)) {
                    Image(qr, "Kod QR linku", modifier = Modifier.fillMaxSize())
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val until = SimpleDateFormat("H:mm", Locale("pl")).format(Date(cur.until))
                    Text(if (cur.toDest) "Do dojechania do celu" else "Do $until", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = accent)
                    Text("Zeskanuj aparatem telefonu i wyślij link komu chcesz (SMS, WhatsApp…).", fontSize = 18.sp, color = CarColors.Text2)
                    if (paired) Text("Link jest też w CAR OS Connect: zakładka Auto → Jazda na żywo.", fontSize = 16.sp, color = CarColors.Muted)
                    Text("Pozycja co 2 min, między nimi strona przesuwa auto po trasie.", fontSize = 15.sp, color = CarColors.Muted)
                }
            }
        }
    }
}

/** Duży kafel wyboru w panelu (ikona, tytuł, podpis). */
@Composable
private fun ShareOption(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, main: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(24.dp)
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Column(
        modifier.pressScale(src).clip(shape)
            .background(if (main) accent.copy(alpha = 0.16f) else CarColors.Card)
            .border(2.dp, if (main) accent else CarColors.Border, shape)
            .clickable(interactionSource = src, indication = androidx.compose.foundation.LocalIndication.current, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        androidx.compose.material3.Icon(icon, null, tint = accent, modifier = Modifier.size(36.dp))
        Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = CarColors.Text)
        Text(sub, fontSize = 16.sp, color = CarColors.Muted, maxLines = 2)
    }
}
