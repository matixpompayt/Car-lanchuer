package pl.mati.carlauncher.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.rounded.EventAvailable
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.TrendingDown
import androidx.compose.material.icons.rounded.TrendingFlat
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.mati.carlauncher.data.FuelPrices
import pl.mati.carlauncher.data.FuelPrices.Fuel
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

private val DAY_LONG = DateTimeFormatter.ofPattern("EEEE d MMMM", PL)
private val DAY_SHORT = DateTimeFormatter.ofPattern("EEE d.MM", PL)
private val DAY_AXIS = DateTimeFormatter.ofPattern("d.MM", PL)

fun zl(v: Double): String = String.format(PL, "%.2f", v)
private fun zlSigned(v: Double): String = (if (v > 0) "+" else if (v < 0) "−" else "") + String.format(PL, "%.2f", abs(v))

@Composable
fun FuelPricesScreen() {
    val snap by FuelPrices.data.collectAsState()
    val err by FuelPrices.error.collectAsState()
    val loading by FuelPrices.loading.collectAsState()
    val fav by FuelPrices.fav.collectAsState()
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { FuelPrices.start(ctx); FuelPrices.refreshIfStale() }

    val s = snap
    if (s == null) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
            if (err != null && !loading) {
                Text(err ?: "", fontSize = 20.sp, color = CarColors.Orange)
                SecondaryButton("Spróbuj ponownie", { FuelPrices.refreshNow() }, icon = Icons.Rounded.Refresh)
            } else {
                Spinner(40.dp)
                Text("Pobieram ceny paliw…", fontSize = 19.sp, color = CarColors.Muted)
            }
        }
        return
    }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        // ---- lewa: ceny dziś ----
        Column(Modifier.width(540.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Ceny paliw", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Maksymalne ceny · " + LocalDate.now().format(DAY_LONG),
                        fontSize = 16.sp, color = CarColors.Muted, maxLines = 1,
                    )
                }
                if (loading) Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) { Spinner(26.dp) }
                else RoundIconButton(Icons.Rounded.Refresh, "Odśwież", 52.dp, { FuelPrices.refreshNow() }, iconSize = 28.dp)
            }
            val order = listOf(Fuel.ON, Fuel.PB95, Fuel.PB98, Fuel.LPG)
            order.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { f ->
                        PriceCard(f, s, f == fav, { FuelPrices.setFavorite(ctx, f) }, Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
            TomorrowLine(s)
        }

        // ---- prawa: historia ----
        CarCard(Modifier.weight(1f).fillMaxHeight()) {
            HistoryPanel(s)
        }
    }
}

@Composable
private fun PriceCard(f: Fuel, s: FuelPrices.Snapshot, fav: Boolean, onFav: () -> Unit, modifier: Modifier) {
    val accent = LocalAccent.current
    val c = Color(f.color)
    val p = s.today[f]
    val ch = s.change[f]
    val tm = s.tomorrow[f]
    val shape = RoundedCornerShape(CarColors.theme.radius(22.dp))
    CarCard(modifier.then(if (fav) Modifier.border(2.dp, accent.copy(alpha = 0.75f), shape) else Modifier), radius = 22.dp) {
        Column(Modifier.fillMaxSize().padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 12.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(c))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(f.short, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(f.label, fontSize = 14.sp, color = CarColors.Muted, maxLines = 1)
                }
                Icon(
                    if (fav) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                    contentDescription = "Ulubione paliwo",
                    tint = if (fav) Color(0xFFFBBF24) else CarColors.Dim,
                    modifier = Modifier.size(56.dp).clip(CircleShape).clickable(onClick = onFav).padding(13.dp),
                )
            }
            ValueText(p?.let { zl(it) } ?: "--", "zł/l", 50.sp, Modifier.fillMaxWidth().padding(end = 12.dp))
            Row(Modifier.padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                ChangeBadge(ch, if (f == Fuel.LPG) "7 dni" else null)
                Spacer(Modifier.weight(1f))
                if (tm != null && !s.tomorrowSame && p != null) {
                    val d = tm - p
                    Text(
                        "jutro ${zl(tm)}",
                        fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        color = when { abs(d) < 0.005 -> CarColors.Text2; d > 0 -> CarColors.Red; else -> CarColors.Green },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChangeBadge(ch: Double?, suffix: String?) {
    if (ch == null) {
        Text("—", fontSize = 15.sp, color = CarColors.Dim)
        return
    }
    val flat = abs(ch) < 0.005
    val col = when { flat -> CarColors.Muted; ch > 0 -> CarColors.Red; else -> CarColors.Green }
    Row(
        Modifier.clip(RoundedCornerShape(14.dp)).background(col.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            when { flat -> Icons.Rounded.TrendingFlat; ch > 0 -> Icons.Rounded.TrendingUp; else -> Icons.Rounded.TrendingDown },
            null, tint = col, modifier = Modifier.size(18.dp),
        )
        Text(
            (if (flat) "bez zmian" else zlSigned(ch) + " zł") + (suffix?.let { " · $it" } ?: ""),
            fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = col, maxLines = 1,
        )
    }
}

@Composable
private fun TomorrowLine(s: FuelPrices.Snapshot) {
    val accent = LocalAccent.current
    val tomorrow = LocalDate.now().plusDays(1)
    val (icon, color, text) = when {
        s.noCurrent -> Triple(
            Icons.Rounded.Warning, CarColors.Orange,
            "Brak aktualnego obwieszczenia" + (s.latestFrom?.let { " – ostatnie ceny maksymalne od ${it.format(DAY_AXIS)}" } ?: ""),
        )
        s.tomorrow.isNotEmpty() && s.tomorrowSame -> Triple(
            Icons.Rounded.EventAvailable, CarColors.Text2,
            "Jutro (${tomorrow.format(DAY_SHORT)}) te same ceny – obwieszczenie obowiązuje cały weekend",
        )
        s.tomorrow.isNotEmpty() -> Triple(
            Icons.Rounded.EventAvailable, accent,
            "Są już ceny na jutro (${tomorrow.format(DAY_SHORT)}) – zobacz na kartach",
        )
        else -> Triple(
            Icons.Rounded.Schedule, CarColors.Muted,
            "Ceny na jutro jeszcze nieopublikowane (zwykle w dni robocze po południu)",
        )
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(CarColors.theme.radius(18.dp))).carSurface(RoundedCornerShape(CarColors.theme.radius(18.dp)), CarColors.Card)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(26.dp))
        Text(text, fontSize = 16.sp, color = if (color == CarColors.Muted) CarColors.Text2 else CarColors.Text, maxLines = 2, lineHeight = 20.sp)
    }
}

@Composable
private fun HistoryPanel(s: FuelPrices.Snapshot) {
    var range by rememberSaveable { mutableStateOf(30) }
    var hidden by rememberSaveable { mutableStateOf("lpg") }
    val show = Fuel.entries.filter { it.key !in hidden.split(",") }.toSet()
    val days = remember(s, range) { s.days.takeLast(range + (if (s.days.lastOrNull()?.date?.isAfter(LocalDate.now()) == true) 1 else 0)) }

    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Caption("Historia cen", Modifier.weight(1f), size = 15.sp)
            listOf(14 to "2 tyg.", 30 to "30 dni", 90 to "3 mies.").forEach { (d, l) ->
                CarChip(l, range == d, { range = d })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Fuel.entries.forEach { f ->
                val on = f in show
                val c = Color(f.color)
                Row(
                    Modifier.height(44.dp).clip(RoundedCornerShape(22.dp))
                        .background(if (on) c.copy(alpha = 0.16f) else Color.Transparent)
                        .border(1.dp, if (on) c.copy(alpha = 0.5f) else CarColors.Border, RoundedCornerShape(22.dp))
                        .clickable {
                            val h = hidden.split(",").filter { it.isNotBlank() }.toMutableSet()
                            if (on) { if (show.size > 1) h += f.key } else h -= f.key
                            hidden = h.joinToString(",")
                        }
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (on) c else CarColors.Dim))
                    Text(f.short, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = if (on) CarColors.Text else CarColors.Muted)
                }
            }
        }
        if (days.count { d -> show.any { d.prices[it] != null } } < 2) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    if (Fuel.LPG in show && show.size == 1) "Historia LPG dopiero się zbiera – wróć za kilka dni" else "Za mało danych do wykresu",
                    fontSize = 17.sp, color = CarColors.Muted,
                )
            }
        } else {
            PriceChart(days, show, Modifier.fillMaxWidth().weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.Info, null, tint = CarColors.Dim, modifier = Modifier.size(16.dp))
            val upd = try {
                OffsetDateTime.parse(s.updated).atZoneSameInstant(ZoneId.systemDefault()).let { formatTime(it.toInstant().toEpochMilli()) }
            } catch (_: Exception) { null }
            Text(
                "ON/Pb: obwieszczenia Ministra Energii · LPG: średnia rynkowa" + (upd?.let { " · aktualizacja $it" } ?: ""),
                fontSize = 13.sp, color = CarColors.Dim, maxLines = 1,
            )
        }
    }
}

@Composable
private fun PriceChart(days: List<FuelPrices.Day>, show: Set<Fuel>, modifier: Modifier) {
    var sel by remember(days) { mutableStateOf<Int?>(null) }
    val muted = CarColors.Muted.toArgb()
    val textCol = CarColors.Text.toArgb()
    val grid = CarColors.Border
    val tipBg = CarColors.Raised
    val today = LocalDate.now()
    val todayIdx = days.indexOfFirst { it.date == today }
    val n = days.size

    // marginesy wykresu (px liczone w draw/pointer)
    val leftDp = 54.dp; val rightDp = 14.dp; val topDp = 10.dp; val bottomDp = 26.dp

    fun idxAt(x: Float, w: Float, left: Float, right: Float): Int =
        if (n <= 1) 0 else (((x - left) / (w - left - right)) * (n - 1)).roundToInt().coerceIn(0, n - 1)

    Canvas(
        modifier
            .pointerInput(days) {
                detectTapGestures { o ->
                    val i = idxAt(o.x, size.width.toFloat(), leftDp.toPx(), rightDp.toPx())
                    sel = if (sel == i) null else i
                }
            }
            .pointerInput(days) {
                detectHorizontalDragGestures { ch, _ ->
                    sel = idxAt(ch.position.x, size.width.toFloat(), leftDp.toPx(), rightDp.toPx())
                }
            },
    ) {
        val left = leftDp.toPx(); val right = rightDp.toPx(); val top = topDp.toPx(); val bottom = bottomDp.toPx()
        val w = size.width - left - right
        val h = size.height - top - bottom
        val vals = days.flatMap { d -> show.mapNotNull { d.prices[it] } }
        if (vals.isEmpty()) return@Canvas
        var lo = vals.min(); var hi = vals.max()
        if (hi - lo < 0.2) { val m = (hi + lo) / 2; lo = m - 0.1; hi = m + 0.1 }
        val rawStep = (hi - lo) / 4
        val step = listOf(0.05, 0.1, 0.2, 0.25, 0.5, 1.0, 2.0).firstOrNull { it >= rawStep } ?: 2.0
        lo = floor(lo / step) * step; hi = ceil(hi / step) * step
        fun x(i: Int) = left + if (n <= 1) w / 2 else w * i / (n - 1)
        fun y(v: Double) = top + h * (1 - ((v - lo) / (hi - lo))).toFloat()

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13.sp.toPx(); color = muted }
        // siatka + opisy osi Y
        var g = lo
        while (g <= hi + 1e-6) {
            val yy = y(g)
            drawLine(grid, Offset(left, yy), Offset(left + w, yy), strokeWidth = 1.dp.toPx())
            paint.textAlign = Paint.Align.RIGHT
            drawContext.canvas.nativeCanvas.drawText(zl(g), left - 8.dp.toPx(), yy + 4.dp.toPx(), paint)
            g += step
        }
        // oś X: ~5 dat
        paint.textAlign = Paint.Align.CENTER
        val labels = 5.coerceAtMost(n)
        for (k in 0 until labels) {
            val i = if (labels == 1) 0 else ((n - 1) * k.toFloat() / (labels - 1)).roundToInt()
            val d = days[i].date
            val t = when { d == today -> "dziś"; d == today.plusDays(1) -> "jutro"; else -> d.format(DAY_AXIS) }
            drawContext.canvas.nativeCanvas.drawText(t, x(i).coerceIn(left + 16.dp.toPx(), left + w - 16.dp.toPx()), size.height - 6.dp.toPx(), paint)
        }
        // „dziś” – pionowa przerywana
        if (todayIdx in 0 until n - 1) {
            drawLine(
                grid.copy(alpha = 1f), Offset(x(todayIdx), top), Offset(x(todayIdx), top + h),
                strokeWidth = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
            )
        }
        // linie cen (przerwa tam, gdzie brak cen)
        val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        for (f in Fuel.entries.filter { it in show }) {
            val col = Color(f.color)
            val path = Path()
            var open = false
            var lastI = -1
            days.forEachIndexed { i, d ->
                val v = d.prices[f]
                if (v == null) { open = false; return@forEachIndexed }
                if (!open) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v))
                open = true; lastI = i
            }
            drawPath(path, col, style = stroke)
            if (lastI >= 0) {
                val v = days[lastI].prices[f]!!
                drawCircle(col, 5.dp.toPx(), Offset(x(lastI), y(v)))
            }
        }
        // wybrany dzień – podpowiedź
        val si = sel
        if (si != null && si in 0 until n) {
            val sx = x(si)
            drawLine(CarColors.Text.copy(alpha = 0.5f), Offset(sx, top), Offset(sx, top + h), strokeWidth = 1.5.dp.toPx())
            val d = days[si]
            val rows = Fuel.entries.filter { it in show && d.prices[it] != null }
            rows.forEach { f -> drawCircle(Color(f.color), 6.dp.toPx(), Offset(sx, y(d.prices[f]!!))) }
            val lineH = 20.dp.toPx()
            val boxW = 150.dp.toPx()
            val boxH = lineH * (rows.size + 1) + 12.dp.toPx()
            val bx = if (sx + 12.dp.toPx() + boxW > left + w) sx - 12.dp.toPx() - boxW else sx + 12.dp.toPx()
            val by = top + 4.dp.toPx()
            drawRoundRect(tipBg, Offset(bx, by), Size(boxW, boxH), CornerRadius(12.dp.toPx()))
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 14.sp.toPx(); color = textCol; isFakeBoldText = true }
            val nc = drawContext.canvas.nativeCanvas
            val title = when (d.date) { today -> "Dziś"; today.plusDays(1) -> "Jutro"; else -> d.date.format(DAY_SHORT) }
            nc.drawText(title, bx + 12.dp.toPx(), by + lineH, tp)
            rows.forEachIndexed { k, f ->
                val yy = by + lineH * (k + 2)
                drawCircle(Color(f.color), 4.dp.toPx(), Offset(bx + 16.dp.toPx(), yy - 5.dp.toPx()))
                tp.isFakeBoldText = false
                tp.textAlign = Paint.Align.LEFT
                nc.drawText(f.short, bx + 26.dp.toPx(), yy, tp)
                tp.textAlign = Paint.Align.RIGHT
                nc.drawText(zl(d.prices[f]!!) + " zł", bx + boxW - 12.dp.toPx(), yy, tp)
                tp.textAlign = Paint.Align.LEFT
            }
        }
    }
}
