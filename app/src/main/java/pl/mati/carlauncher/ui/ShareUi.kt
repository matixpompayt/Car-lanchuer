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
 * Okno udostępniania: bez sesji – wybór (do celu / 1 h / 4 h); z sesją – kod QR linku
 * (zeskanuj telefonem pasażera albo wyślij z CAR OS Connect) i „Zakończ”.
 */
@Composable
fun ShareDialog(onDismiss: () -> Unit) {
    val accent = LocalAccent.current
    val s by TripShare.session.collectAsState()
    val route by RouteRepository.route.collectAsState()
    val paired by ConnectRepository.paired.collectAsState()
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(760.dp).clip(RoundedCornerShape(26.dp)).carSurface(RoundedCornerShape(26.dp), CarColors.Panel, heavy = true)
                .border(1.dp, CarColors.Border, RoundedCornerShape(26.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Jazda na żywo", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = CarColors.Text, modifier = Modifier.weight(1f))
                RoundIconButton(Icons.Rounded.Close, "Zamknij", 44.dp, onDismiss)
            }
            val cur = s
            if (cur == null) {
                Text(
                    "Bliscy zobaczą w przeglądarce, gdzie jest auto, trasę i godzinę przyjazdu – bez instalowania aplikacji. " +
                        "Link jest zaszyfrowany: kto go nie ma, nic nie zobaczy.",
                    fontSize = 17.sp, color = CarColors.Text2,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (route != null) PrimaryButton("Do celu", { TripShare.start(minutes = null) }, Modifier.weight(1f))
                    SecondaryButton("Na 1 h", { TripShare.start(minutes = 60) }, Modifier.weight(1f))
                    SecondaryButton("Na 4 h", { TripShare.start(minutes = 240) }, Modifier.weight(1f))
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    val qr = remember(cur.link) { ParkingRepository.qr(cur.link, 420)?.asImageBitmap() }
                    if (qr != null) Box(Modifier.size(230.dp).clip(RoundedCornerShape(18.dp)).background(Color.White).padding(12.dp)) {
                        Image(qr, "Kod QR linku", modifier = Modifier.fillMaxSize())
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val until = SimpleDateFormat("H:mm", Locale("pl")).format(Date(cur.until))
                        Text(if (cur.toDest) "Do dojechania do celu" else "Do $until", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = accent)
                        Text("Zeskanuj aparatem telefonu i wyślij link komu chcesz (SMS, WhatsApp…).", fontSize = 17.sp, color = CarColors.Text2)
                        if (paired) Text("Link jest też w CAR OS Connect: zakładka Auto → Jazda na żywo.", fontSize = 16.sp, color = CarColors.Muted)
                        Text("Pozycja co 2 min, między nimi strona przesuwa auto po trasie.", fontSize = 15.sp, color = CarColors.Muted)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton("Zakończ udostępnianie", { TripShare.stop("stopped"); Toaster.show("Udostępnianie jazdy zakończone") }, Modifier.weight(1f), icon = Icons.Rounded.Close)
                    PrimaryButton("Gotowe", onDismiss, Modifier.weight(1f))
                }
            }
        }
    }
}
