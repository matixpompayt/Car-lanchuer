package pl.mati.carlauncher.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.mati.carlauncher.data.LocationRepository
import pl.mati.carlauncher.data.ObdManager
import pl.mati.carlauncher.data.WeatherRepository

private const val RPM_MAX = 5000
private const val RPM_RED = 4200

@Composable
fun VehicleScreen() {
    val d by ObdManager.data.collectAsState()
    val status by ObdManager.status.collectAsState()
    val message by ObdManager.message.collectAsState()
    val gps by LocationRepository.gpsSpeed.collectAsState()
    val trip by LocationRepository.tripMeters.collectAsState()
    val temp by WeatherRepository.temperature.collectAsState()
    val now by rememberNow()
    val accent = LocalAccent.current

    Column(Modifier.fillMaxSize()) {
        // Pasek górny
        Row(
            Modifier.fillMaxWidth().height(66.dp).padding(horizontal = 26.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(obdColor(status)))
                Text(
                    when (status) {
                        ObdManager.Status.CONNECTED -> "ELM327 · 2.0 TDI"
                        ObdManager.Status.CONNECTING -> "Łączenie z ELM327…"
                        ObdManager.Status.ERROR -> message ?: "Błąd OBD"
                        ObdManager.Status.OFF -> "OBD wyłączone · prędkość z GPS"
                    },
                    fontSize = 19.sp, color = CarColors.Muted, maxLines = 1,
                )
            }
            Text(formatTime(now), fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            Text(
                listOfNotNull(temp?.let { "$it°C" }, "Trasa ${(trip / 1000f).pl1()} km").joinToString(" · "),
                fontSize = 19.sp, color = CarColors.Muted,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        }

        Row(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = 26.dp, end = 26.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Column(Modifier.width(300.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                val c = d.coolant
                GaugeTile(
                    "Płyn chłodzący", c?.toString() ?: "--", "°C",
                    fraction = c?.let { (it - 40) / 80f } ?: 0f,
                    color = if (c != null && c >= 105) CarColors.Red else accent,
                    modifier = Modifier.weight(1f),
                )
                val b = d.boostBar
                GaugeTile(
                    "Doładowanie", b?.pl1() ?: "--", "bar",
                    fraction = (b ?: 0f) / 2f,
                    color = accent,
                    modifier = Modifier.weight(1f),
                )
            }

            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                RpmGauge(d.rpm, Modifier.size(440.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        (d.speed ?: gps)?.toString() ?: "--",
                        fontFamily = Condensed, fontWeight = FontWeight.Bold,
                        fontSize = 156.sp, lineHeight = 150.sp,
                    )
                    Text("km/h", fontSize = 21.sp, color = CarColors.Muted)
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(d.rpm?.toString() ?: "--") }
                            withStyle(SpanStyle(color = CarColors.Muted)) { append(" obr/min") }
                        },
                        fontSize = 24.sp,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }

            Column(Modifier.width(300.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                val v = d.voltage
                GaugeTile(
                    "Napięcie", v?.pl1() ?: "--", "V",
                    fraction = v?.let { (it - 11f) / 4f } ?: 0f,
                    color = if (v == null || v in 12.2f..14.9f) CarColors.Green else CarColors.Red,
                    modifier = Modifier.weight(1f),
                )
                val l = d.load
                GaugeTile(
                    "Obciążenie", l?.toString() ?: "--", "%",
                    fraction = (l ?: 0) / 100f,
                    color = accent,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        MiniPlayer(
            Modifier
                .fillMaxWidth()
                .height(84.dp)
                .padding(start = 26.dp, end = 26.dp),
        )
        Box(Modifier.height(18.dp))
    }
}

@Composable
private fun GaugeTile(
    label: String,
    value: String,
    unit: String,
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
    CarCard(modifier.fillMaxWidth(), color = CarColors.Card2, radius = 20.dp) {
        Column(
            Modifier.fillMaxSize().padding(18.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Caption(label, size = 14.sp)
            ValueText(value, unit, 62.sp)
            ProgressBar(fraction, color, height = 7.dp)
        }
    }
}

/** Łuk obrotomierza 270°, czerwone pole od RPM_RED. */
@Composable
private fun RpmGauge(rpm: Int?, modifier: Modifier) {
    val accent = LocalAccent.current
    val target = (rpm ?: 0).coerceIn(0, RPM_MAX).toFloat() / RPM_MAX
    val anim by animateFloatAsState(target, tween(250), label = "rpm")
    val arcColor = if ((rpm ?: 0) >= RPM_RED) CarColors.Red else accent

    Canvas(modifier) {
        val stroke = size.minDimension * 0.047f
        val inset = stroke / 2f + size.minDimension * 0.02f
        val topLeft = Offset(inset, inset)
        val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
        val redStart = 135f + 270f * RPM_RED / RPM_MAX
        val redSweep = 270f * (RPM_MAX - RPM_RED) / RPM_MAX

        drawArc(CarColors.Track.copy(alpha = 0.6f), 135f, 270f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        drawArc(CarColors.RedZone, redStart, redSweep, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
        if (anim > 0.003f) {
            drawArc(arcColor, 135f, 270f * anim, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
    }
}
