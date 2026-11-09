package pl.mati.carlauncher.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backspace
import androidx.compose.material.icons.rounded.CallMade
import androidx.compose.material.icons.rounded.CallMissed
import androidx.compose.material.icons.rounded.CallReceived
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.mati.carlauncher.data.PhoneBook
import pl.mati.carlauncher.data.PhoneStatusRepository
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date

private val KEYS = listOf("1" to "", "2" to "ABC", "3" to "DEF", "4" to "GHI", "5" to "JKL", "6" to "MNO", "7" to "PQRS", "8" to "TUV", "9" to "WXYZ", "*" to "", "0" to "+", "#" to "")

private val AVATAR_COLORS = listOf(
    Color(0xFF3B82F6), Color(0xFF10B981), Color(0xFFF59E0B), Color(0xFFEF4444), Color(0xFF8B5CF6),
    Color(0xFF14B8A6), Color(0xFFEC4899), Color(0xFF6366F1), Color(0xFF84CC16), Color(0xFFF97316),
)

private fun callNow(ctx: Context, n: String) {
    val ok = Launch.start(ctx, Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(n))))
    if (!ok) Launch.dialer(ctx, n)
}

/** Dzwoni od razu (przez telefon połączony po Bluetooth); o uprawnienie pyta tylko za pierwszym razem. */
@Composable
private fun rememberCaller(): (String) -> Unit {
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf<String?>(null) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        pending?.let { if (ok) callNow(ctx, it) else Launch.dialer(ctx, it) }
        pending = null
    }
    return { n ->
        if (n.isBlank()) Toaster.show("Wpisz numer")
        else if (PhoneBook.granted(ctx, Manifest.permission.CALL_PHONE)) callNow(ctx, n)
        else { pending = n; perm.launch(Manifest.permission.CALL_PHONE) }
    }
}

@Composable
fun PhoneScreen() {
    val ctx = LocalContext.current
    val call = rememberCaller()
    val contacts by PhoneBook.contacts.collectAsState()
    val calls by PhoneBook.calls.collectAsState()
    val info by PhoneBook.info.collectAsState()
    val loading by PhoneBook.loading.collectAsState()
    var tab by rememberSaveable { mutableStateOf("recent") }
    var number by rememberSaveable { mutableStateOf("") }
    var chooser by remember { mutableStateOf<PhoneBook.Contact?>(null) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { PhoneBook.start(ctx) }
    LaunchedEffect(Unit) {
        PhoneBook.start(ctx)
        if (!PhoneBook.granted(ctx, Manifest.permission.READ_CONTACTS) || !PhoneBook.granted(ctx, Manifest.permission.READ_CALL_LOG)) {
            permLauncher.launch(PhoneBook.permissions)
        }
    }
    fun dial(c: PhoneBook.Contact) {
        if (c.numbers.size == 1) call(c.numbers[0].number) else chooser = c
    }

    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            // ---------- lewa: historia / kontakty ----------
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CarChip("Ostatnie", tab == "recent", { tab = "recent" }, Icons.Rounded.CallReceived)
                    CarChip("Kontakty", tab == "contacts", { tab = "contacts" }, Icons.Rounded.Phone)
                    CarChip("Ulubione", tab == "fav", { tab = "fav" }, Icons.Rounded.Star)
                    Spacer(Modifier.weight(1f))
                    PhoneLine()
                    if (loading) Spinner(26.dp)
                    else RoundIconButton(Icons.Rounded.Refresh, "Odśwież", 48.dp, { PhoneBook.reload() }, iconSize = 26.dp, tint = CarColors.Muted)
                }
                val needPerm = !info.canContacts || !info.canCalls
                when {
                    needPerm && contacts.isEmpty() && calls.isEmpty() -> PermissionCard { permLauncher.launch(PhoneBook.permissions) }
                    tab == "recent" -> RecentList(calls, info, Modifier.weight(1f)) { c ->
                        val ct = PhoneBook.contactFor(c.number)
                        call(c.number.ifBlank { ct?.numbers?.firstOrNull()?.number.orEmpty() })
                    }
                    tab == "fav" -> ContactList(contacts.filter { it.starred }, info, Modifier.weight(1f), favorites = true, onCall = ::dial)
                    else -> ContactList(contacts, info, Modifier.weight(1f), favorites = false, onCall = ::dial)
                }
            }
            // ---------- prawa: klawiatura ----------
            Dialpad(number, { number = it }, contacts, Modifier.width(380.dp).fillMaxHeight(), call)
        }
        chooser?.let { c -> NumberChooser(c, onPick = { chooser = null; call(it) }, onClose = { chooser = null }) }
    }
}

/** Operator i zasięg telefonu połączonego z radiem. */
@Composable
private fun PhoneLine() {
    val st by PhoneStatusRepository.status.collectAsState()
    val op = st.operator ?: return
    Text(
        op + (st.signal?.let { " · " + "▂▄▆█".take((it.coerceIn(0, 5) * 4 + 4) / 5).ifEmpty { "–" } } ?: "") + (st.battery?.let { " · 🔋${it * 20}%" } ?: ""),
        fontSize = 15.sp, color = CarColors.Muted, maxLines = 1,
    )
}

@Composable
private fun PermissionCard(onAsk: () -> Unit) {
    CarCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Dostęp do kontaktów i historii", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text("CAR OS pokaże tu kontakty i ostatnie połączenia z telefonu połączonego po Bluetooth. Potrzebuje do tego zgody na odczyt kontaktów i rejestru połączeń.", fontSize = 16.sp, color = CarColors.Muted)
            PrimaryButton("Zezwól", onAsk)
        }
    }
}

/** Co zrobić, gdy lista jest pusta: zgoda na telefonie + diagnostyka (co widzi radio). */
@Composable
private fun EmptyHelp(what: String, info: PhoneBook.Info) {
    var more by remember { mutableStateOf(false) }
    CarCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Brak $what z telefonu", fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Na telefonie: Ustawienia → Bluetooth → to radio → włącz „Kontakty i historia połączeń” (albo „Udostępnianie kontaktów”). " +
                    "Potem rozłącz i połącz telefon ponownie – radio pobierze książkę w ciągu minuty, a lista odświeży się sama.",
                fontSize = 16.sp, color = CarColors.Muted,
            )
            Text(if (more) "Ukryj szczegóły" else "Szczegóły dla twórcy", fontSize = 15.sp, color = LocalAccent.current, modifier = Modifier.clickable { more = !more })
            if (more) {
                Text(
                    buildString {
                        append("Kontakty w radiu: ${info.contacts}, historia: ${info.calls}\n")
                        append("Konta: ").append(info.accounts.entries.joinToString { "${it.key} (${it.value})" }.ifEmpty { "brak" }).append('\n')
                        append("Zgody: kontakty ${if (info.canContacts) "tak" else "nie"}, historia ${if (info.canCalls) "tak" else "nie"}\n")
                        info.error?.let { append("Błąd: $it\n") }
                        info.providers.forEach { append(it).append('\n') }
                    },
                    fontSize = 13.sp, color = CarColors.Text2,
                )
            }
        }
    }
}

private val HM = SimpleDateFormat("HH:mm", PL)
private val DAY = SimpleDateFormat("EEE HH:mm", PL)
private val DATE = SimpleDateFormat("d MMM", PL)

private fun whenText(t: Long): String {
    val c = Calendar.getInstance()
    val today = c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
    c.timeInMillis = t
    val d = c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
    return when {
        today == d -> HM.format(Date(t))
        today - d == 1 -> "wczoraj " + HM.format(Date(t))
        today - d in 2..6 -> DAY.format(Date(t))
        else -> DATE.format(Date(t))
    }
}

@Composable
private fun RecentList(calls: List<PhoneBook.Call>, info: PhoneBook.Info, modifier: Modifier, onCall: (PhoneBook.Call) -> Unit) {
    if (calls.isEmpty()) { EmptyHelp("historii połączeń", info); return }
    LazyColumn(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(calls) { c ->
            val (icon, tint) = when {
                c.missed -> Icons.Rounded.CallMissed to CarColors.Red
                c.outgoing -> Icons.Rounded.CallMade to CarColors.Muted
                else -> Icons.Rounded.CallReceived to CarColors.Green
            }
            val title = c.name ?: c.number.ifBlank { "Numer prywatny" }
            val label = PhoneBook.contactFor(c.number)?.numbers?.firstOrNull { PhoneBook.key(it.number) == PhoneBook.key(c.number) }?.label
            PersonRow(
                title + if (c.count > 1) " (${c.count})" else "",
                listOfNotNull(label?.takeIf { c.name != null }, if (c.name == null) null else c.number, whenText(c.date)).joinToString(" · "),
                c.photo, title, titleColor = if (c.missed) CarColors.Red else CarColors.Text,
                badge = { Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp)) },
            ) { onCall(c) }
        }
    }
}

@Composable
private fun ContactList(list: List<PhoneBook.Contact>, info: PhoneBook.Info, modifier: Modifier, favorites: Boolean, onCall: (PhoneBook.Contact) -> Unit) {
    var q by rememberSaveable { mutableStateOf("") }
    val shown = remember(list, q) {
        val t = q.trim().lowercase(PL)
        if (t.isEmpty()) list else {
            val digits = t.filter { it.isDigit() }
            list.filter { c -> c.name.lowercase(PL).split(' ').any { it.startsWith(t) } || c.name.lowercase(PL).contains(t) || (digits.length >= 3 && c.numbers.any { it.number.filter { ch -> ch.isDigit() }.contains(digits) }) }
        }
    }
    if (list.isEmpty()) {
        if (favorites) {
            CarCard(Modifier.fillMaxWidth()) {
                Text("Brak ulubionych – oznacz kontakty gwiazdką w telefonie, a pojawią się tutaj po następnym połączeniu.", fontSize = 17.sp, color = CarColors.Muted, modifier = Modifier.padding(22.dp))
            }
        } else EmptyHelp("kontaktów", info)
        return
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // indeks liter (A–Ż) do szybkiego przewijania
    val letters = remember(shown) {
        val m = LinkedHashMap<String, Int>()
        shown.forEachIndexed { i, c -> val l = c.name.firstOrNull()?.uppercase(PL)?.takeIf { it.first().isLetter() } ?: "#"; m.putIfAbsent(l, i) }
        m
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!favorites) CarTextField(q, { q = it }, "Szukaj kontaktu (${list.size})", Modifier.fillMaxWidth())
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LazyColumn(Modifier.weight(1f).fillMaxHeight(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(shown, key = { it.id }) { c ->
                    val sub = if (c.numbers.size == 1) listOf(c.numbers[0].label, c.numbers[0].number).filter { it.isNotBlank() }.joinToString(" · ")
                    else "${c.numbers.size} numery · " + c.numbers.joinToString(", ") { it.label.ifBlank { it.number } }
                    PersonRow(c.name, sub, c.photo, c.name, badge = if (c.starred) ({ Icon(Icons.Rounded.Star, null, tint = Color(0xFFFFC83D), modifier = Modifier.size(18.dp)) }) else null) { onCall(c) }
                }
            }
            if (q.isEmpty() && letters.size > 4) {
                Column(Modifier.width(34.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally) {
                    letters.forEach { (l, idx) ->
                        Text(
                            l, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = LocalAccent.current,
                            modifier = Modifier.clip(CircleShape).clickable { scope.launch { listState.scrollToItem(idx) } }.padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Wiersz osoby: zdjęcie / inicjały, imię, opis, zielona słuchawka. Cały wiersz dzwoni. */
@Composable
private fun PersonRow(
    title: String, sub: String, photo: String?, avatarName: String,
    titleColor: Color = CarColors.Text, badge: (@Composable () -> Unit)? = null, onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(CarColors.theme.radius(18.dp))
    Row(
        Modifier.fillMaxWidth().height(76.dp).clip(shape).carSurface(shape, CarColors.Card).clickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Avatar(avatarName, photo, 50.dp)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                badge?.invoke()
                Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (sub.isNotBlank()) Text(sub, fontSize = 15.sp, color = CarColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.size(52.dp).clip(CircleShape).background(CarColors.CallGreen), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Phone, "Zadzwoń", tint = Color.White, modifier = Modifier.size(26.dp))
        }
    }
}

@Composable
private fun Avatar(name: String, photo: String?, size: Dp) {
    val ctx = LocalContext.current
    val img by produceState<ImageBitmap?>(null, photo) {
        value = photo?.let { p ->
            withContext(Dispatchers.IO) {
                try { ctx.contentResolver.openInputStream(Uri.parse(p))?.use { BitmapFactory.decodeStream(it)?.asImageBitmap() } } catch (_: Exception) { null }
            }
        }
    }
    val initials = name.split(' ', '-').filter { it.isNotBlank() && it.first().isLetterOrDigit() }.take(2).joinToString("") { it.first().uppercase(PL) }.ifBlank { "#" }
    val col = AVATAR_COLORS[(name.hashCode() and 0x7fffffff) % AVATAR_COLORS.size]
    Box(Modifier.size(size).clip(CircleShape).background(col.copy(alpha = 0.85f)), contentAlignment = Alignment.Center) {
        val i = img
        if (i != null) Image(i, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Text(initials, fontSize = (size.value * 0.36f).sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

@Composable
private fun Dialpad(number: String, onNumber: (String) -> Unit, contacts: List<PhoneBook.Contact>, modifier: Modifier, call: (String) -> Unit) {
    // podpowiedzi: kontakty, których numer zawiera wpisane cyfry
    val hits = remember(number, contacts) {
        val d = number.filter { it.isDigit() }
        if (d.length < 3) emptyList()
        else contacts.mapNotNull { c -> c.numbers.firstOrNull { it.number.filter { ch -> ch.isDigit() }.contains(d) }?.let { c to it } }.take(2)
    }
    val match = PhoneBook.contactFor(number)?.takeIf { PhoneBook.key(number).length >= 9 }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.height(50.dp), contentAlignment = Alignment.Center) {
            Text(
                number.ifEmpty { "Wpisz numer" },
                fontSize = if (number.isEmpty()) 26.sp else 36.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp,
                color = if (number.isEmpty()) CarColors.Muted else CarColors.Text, maxLines = 1,
            )
        }
        Box(Modifier.height(40.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                match != null -> Text(match.name, fontSize = 18.sp, color = LocalAccent.current, fontWeight = FontWeight.SemiBold, maxLines = 1)
                hits.isNotEmpty() -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    hits.forEach { (c, n) ->
                        val s = RoundedCornerShape(20.dp)
                        Text(
                            c.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.width(170.dp).clip(s).carSurface(s, CarColors.Card2).clickable { onNumber(n.number); call(n.number) }
                                .padding(horizontal = 14.dp, vertical = 9.dp),
                        )
                    }
                }
            }
        }
        KEYS.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (k, sub) ->
                    val s = RoundedCornerShape(if (GlassUi.on) 32.dp else 20.dp)
                    Column(
                        Modifier.size(100.dp, 62.dp).clip(s).carSurface(s, CarColors.Card)
                            .clickable { if (number.length < 16) onNumber(number + k) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(k, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                        if (sub.isNotEmpty()) Text(sub, fontSize = 10.sp, color = CarColors.Muted, letterSpacing = 1.sp)
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(30.dp)) {
            Spacer(Modifier.width(60.dp))
            Box(
                Modifier.size(78.dp).clip(CircleShape).background(CarColors.CallGreen).clickable { call(number) },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Phone, "Zadzwoń", tint = Color.White, modifier = Modifier.size(36.dp)) }
            Box(
                Modifier.size(60.dp).clip(CircleShape).clickable { onNumber(number.dropLast(1)) },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Backspace, "Usuń", tint = CarColors.Muted, modifier = Modifier.size(28.dp)) }
        }
    }
}

/** Kontakt z kilkoma numerami – wybór, na który zadzwonić. */
@Composable
private fun NumberChooser(c: PhoneBook.Contact, onPick: (String) -> Unit, onClose: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        val shape = RoundedCornerShape(28.dp)
        Column(
            Modifier.width(560.dp).clip(shape).carSurface(shape, CarColors.Panel, heavy = true).clickable(enabled = false) {}.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Avatar(c.name, c.photo, 56.dp)
                Text(c.name, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                RoundIconButton(Icons.Rounded.Close, "Zamknij", 52.dp, onClose)
            }
            c.numbers.forEach { n ->
                val s = RoundedCornerShape(18.dp)
                Row(
                    Modifier.fillMaxWidth().height(70.dp).clip(s).carSurface(s, CarColors.Card).clickable { onPick(n.number) }.padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(n.number, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                        if (n.label.isNotBlank()) Text(n.label, fontSize = 15.sp, color = CarColors.Muted)
                    }
                    Box(Modifier.size(50.dp).clip(CircleShape).background(CarColors.CallGreen), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Phone, "Zadzwoń", tint = Color.White, modifier = Modifier.size(24.dp))
                    }
                }
            }
        }
    }
}
