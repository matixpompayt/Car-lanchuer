package pl.mati.carlauncher.ui

import android.content.Intent
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Restore
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.mati.carlauncher.data.ConnectRepository
import pl.mati.carlauncher.data.ParkingRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Okno: kopia ustawień przysłana z telefonu (CAR OS Connect) – przywrócić? */
@Composable
fun ConnectRestoreOverlay() {
    val r by ConnectRepository.pendingRestore.collectAsState()
    val p = r ?: return
    val ctx = LocalContext.current
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)).clickable(enabled = true, onClick = {}),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(620.dp).clip(RoundedCornerShape(28.dp)).background(CarColors.Panel)
                .border(1.dp, CarColors.Border, RoundedCornerShape(28.dp)).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                IconBadge(Icons.Rounded.Restore, 56.dp)
                Column {
                    Text("Kopia ustawień z telefonu", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text(
                        listOf(p.car, if (p.created > 0) "z " + SimpleDateFormat("d.MM.yyyy HH:mm", Locale("pl")).format(Date(p.created)) else "")
                            .filter { it.isNotBlank() }.joinToString(" · "),
                        fontSize = 17.sp, color = CarColors.Muted,
                    )
                }
            }
            Text(
                "CAR OS Connect przysłał kopię: profil, wygląd, serwis, dziennik tras, ulubione. Obecne ustawienia zostaną zastąpione, połączenie z telefonem zostaje.",
                fontSize = 18.sp, color = CarColors.Text2,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                SecondaryButton("Anuluj", { ConnectRepository.pendingRestore.value = null }, Modifier.weight(1f), icon = Icons.Rounded.Close)
                PrimaryButton("Przywróć", {
                    if (ConnectRepository.applyRestore()) {
                        Toaster.show("Przywrócono – uruchamiam ponownie")
                        val i = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
                        if (i != null) {
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            ctx.startActivity(i)
                        }
                        Runtime.getRuntime().exit(0)
                    } else {
                        Toaster.show("Nie udało się przywrócić kopii")
                    }
                }, Modifier.weight(1f), icon = Icons.Rounded.Restore)
            }
        }
    }
}

/** Kreator: przywrócenie ustawień z telefonu po wymianie / resecie radia. */
@Composable
fun ConnectRestoreCard() {
    var open by remember { mutableStateOf(false) }
    val accent = LocalAccent.current
    Column(
        Modifier.widthIn(max = 860.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(CarColors.Card)
            .border(1.dp, CarColors.Border, RoundedCornerShape(20.dp)).clickable { open = !open }.padding(horizontal = 22.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            IconBadge(Icons.Rounded.PhoneAndroid, 52.dp)
            Column(Modifier.weight(1f)) {
                Text("Przywróć ustawienia z telefonu", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("Masz CAR OS Connect z kopią ze starego radia? Połącz telefon kodem QR", fontSize = 16.sp, color = CarColors.Muted)
            }
            Text(if (open) "Ukryj" else "Pokaż kod", fontSize = 17.sp, color = accent, fontWeight = FontWeight.SemiBold)
        }
        if (open) {
            val qr = remember { ParkingRepository.qr(ConnectRepository.payload(), 480)?.asImageBitmap() }
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(230.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(10.dp)) {
                    if (qr != null) Image(qr, "Kod QR", modifier = Modifier.fillMaxSize())
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("1. W CAR OS Connect: Ustawienia → Połączenie z CAR OS → zeskanuj ten kod", fontSize = 17.sp, color = CarColors.Text2)
                    Text("2. Potem: Ustawienia → Kopia ustawień → „Przywróć w aucie”", fontSize = 17.sp, color = CarColors.Text2)
                    Text("3. Potwierdź przywrócenie na ekranie radia", fontSize = 17.sp, color = CarColors.Text2)
                }
            }
        }
    }
}
