package pl.mati.carlauncher.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backspace
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

private val KEYS = listOf("1" to "", "2" to "ABC", "3" to "DEF", "4" to "GHI", "5" to "JKL", "6" to "MNO", "7" to "PQRS", "8" to "TUV", "9" to "WXYZ", "*" to "", "0" to "+", "#" to "")

@Composable
fun PhoneScreen() {
    val ctx = LocalContext.current
    var number by remember { mutableStateOf("") }

    fun callNow(n: String) {
        val ok = Launch.start(ctx, Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(n))))
        if (!ok) Launch.dialer(ctx, n)
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) callNow(number) else Launch.dialer(ctx, number)
    }

    fun call() {
        if (number.isBlank()) { Toaster.show("Wpisz numer"); return }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) callNow(number)
        else permission.launch(Manifest.permission.CALL_PHONE)
    }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Telefon", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
            Text(
                "Połączenia idą przez telefon sparowany z radiem po Bluetooth. Kontakty i historia są w aplikacji telefonu radia.",
                fontSize = 18.sp, color = CarColors.Muted,
            )
            SettingRow("Kontakty", "Lista kontaktów z telefonu", leading = Icons.Rounded.Contacts, onClick = {
                if (!Launch.start(ctx, Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI))) Launch.dialer(ctx)
            })
            SettingRow("Ostatnie połączenia", "Historia z aplikacji telefonu", leading = Icons.Rounded.History, onClick = {
                if (!Launch.start(ctx, Intent(Intent.ACTION_VIEW).setType("vnd.android.cursor.dir/calls"))) Launch.dialer(ctx)
            })
            SettingRow("Aplikacja telefonu", "Otwórz pełną aplikację", leading = Icons.Rounded.Phone, onClick = { Launch.dialer(ctx) })
        }
        Column(Modifier.width(520.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.height(60.dp), contentAlignment = Alignment.Center) {
                Text(
                    number.ifEmpty { "Wpisz numer" },
                    fontSize = if (number.isEmpty()) 30.sp else 40.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp,
                    color = if (number.isEmpty()) CarColors.Muted else CarColors.Text, maxLines = 1,
                )
            }
            KEYS.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    row.forEach { (k, sub) ->
                        Column(
                            Modifier
                                .size(96.dp, 72.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(CarColors.Card)
                                .clickable { if (number.length < 16) number += k },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(k, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                            Text(sub.ifEmpty { " " }, fontSize = 11.sp, color = CarColors.Muted, letterSpacing = 1.sp)
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(30.dp)) {
                Spacer(Modifier.width(64.dp))
                Box(
                    Modifier.size(84.dp).clip(CircleShape).background(CarColors.CallGreen).clickable { call() },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Phone, "Zadzwoń", tint = CarColors.Text, modifier = Modifier.size(38.dp)) }
                Box(
                    Modifier.size(64.dp).clip(CircleShape).clickable { number = number.dropLast(1) },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Backspace, "Usuń", tint = CarColors.Muted, modifier = Modifier.size(30.dp)) }
            }
        }
    }
}
