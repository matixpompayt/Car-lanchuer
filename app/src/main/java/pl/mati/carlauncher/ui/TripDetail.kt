package pl.mati.carlauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import pl.mati.carlauncher.data.FuelRepository
import pl.mati.carlauncher.data.Polyline
import pl.mati.carlauncher.data.TripRepository
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.roundToInt

/** Szacowane paliwo (l) i koszt (zł) przejazdu ze średniego spalania i cen z tankowań. */
fun tripFuel(distanceM: Double): Pair<Double?, Double?> {
    val s = FuelRepository.stats.value
    val km = distanceM / 1000.0
    val liters = s.avgL100?.let { km * it / 100.0 }
    val cost = s.costPerKm?.let { km * it } ?: liters?.let { l -> s.lastPrice?.let { l * it } }
    return liters to cost
}

private val DETAIL_FMT = SimpleDateFormat("EEEE, d MMMM yyyy", PL)

/** Szczegóły jazdy: mapa przejechanej trasy + statystyki. */
@Composable
fun TripDetailDialog(t: TripRepository.Trip, onDismiss: () -> Unit) {
    val accent = LocalAccent.current
    val path = remember(t.start) { TripRepository.pathOf(t)?.let { try { Polyline.decode(it) } catch (_: Exception) { null } }.orEmpty() }
    val fuel = remember(t.start) { tripFuel(t.distanceM) }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.fillMaxWidth().fillMaxHeight(0.92f).clip(RoundedCornerShape(26.dp)).background(CarColors.Panel)
                .border(1.dp, CarColors.Border, RoundedCornerShape(26.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        ) {
            Box(Modifier.weight(1.4f).fillMaxHeight().clip(RoundedCornerShape(topStart = 26.dp, bottomStart = 26.dp))) {
                if (path.size >= 2) TripPathMap(path, accent.toArgb(), Modifier.fillMaxSize())
                else Box(Modifier.fillMaxSize().background(CarColors.Card), contentAlignment = Alignment.Center) {
                    Text("Brak zapisanego śladu GPS dla tej jazdy", fontSize = 18.sp, color = CarColors.Muted)
                }
            }
            Column(Modifier.width(380.dp).fillMaxHeight().padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(DETAIL_FMT.format(Date(t.start)).replaceFirstChar { it.uppercase() }, fontSize = 16.sp, color = CarColors.Muted)
                Text("${formatTime(t.start)} – ${formatTime(t.end)}", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                ValueText((t.distanceM / 1000).toFloat().pl1(), "km", 44.sp)
                DetailLine("Czas", formatDurationLong(t.durationS) + " (w ruchu " + formatDurationLong(t.movingS) + ")")
                DetailLine("Średnia / maks.", "${t.avgKmh.roundToInt()} / ${t.maxSpeed} km/h")
                t.maxCoolant?.let { DetailLine("Płyn chłodniczy maks.", "$it °C") }
                fuel.first?.let { DetailLine("Paliwo (szac.)", String.format(PL, "%.1f l", it)) }
                fuel.second?.let { DetailLine("Koszt (szac.)", String.format(PL, "%.2f zł", it)) }
                if (t.distanceM >= 1000) DetailLine("Płynność jazdy", "${t.score} / 100 · ${t.accel} przysp. · ${t.brake} ham.")
                Box(Modifier.weight(1f))
                if (fuel.first == null) Text("Spalanie i koszt pokażą się po dwóch tankowaniach do pełna (zakładka Paliwo).", fontSize = 14.sp, color = CarColors.Muted)
                SecondaryButton("Zamknij", onDismiss, Modifier.fillMaxWidth(), height = 56.dp)
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 16.sp, color = CarColors.Muted, modifier = Modifier.weight(1f))
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Mała mapa MapLibre z narysowanym śladem i punktami start / koniec. */
@Composable
private fun TripPathMap(path: List<DoubleArray>, accent: Int, modifier: Modifier) {
    val ctx = LocalContext.current
    val light = CarColors.isLight
    val view = remember {
        MapLibre.getInstance(ctx)
        MapView(ctx, MapLibreMapOptions.createFromAttributes(ctx, null).textureMode(true)).also { v ->
            v.onCreate(null)
            v.getMapAsync { m ->
                m.uiSettings.isLogoEnabled = false
                m.uiSettings.isCompassEnabled = false
                m.uiSettings.isTiltGesturesEnabled = false
                m.setStyle(Style.Builder().fromUri(CarMapStyle.STYLE_URL)) { st ->
                    CarMapStyle.apply(st, light)
                    val pts = path.map { Point.fromLngLat(it[1], it[0]) }
                    st.addSource(GeoJsonSource("trip-line", FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(pts)))))
                    st.addSource(GeoJsonSource("trip-ends", FeatureCollection.fromFeatures(listOf(Feature.fromGeometry(pts.first()), Feature.fromGeometry(pts.last())))))
                    st.addLayer(LineLayer("trip-casing", "trip-line").withProperties(
                        PropertyFactory.lineColor(android.graphics.Color.parseColor("#05070A")), PropertyFactory.lineWidth(10f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    ))
                    st.addLayer(LineLayer("trip-path", "trip-line").withProperties(
                        PropertyFactory.lineColor(accent), PropertyFactory.lineWidth(6f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    ))
                    st.addLayer(CircleLayer("trip-end-dots", "trip-ends").withProperties(
                        PropertyFactory.circleRadius(8f), PropertyFactory.circleColor(android.graphics.Color.WHITE),
                        PropertyFactory.circleStrokeColor(accent), PropertyFactory.circleStrokeWidth(4f),
                    ))
                    val b = LatLngBounds.Builder()
                    path.forEach { b.include(LatLng(it[0], it[1])) }
                    val pad = (48 * ctx.resources.displayMetrics.density).toInt()
                    v.post {
                        try { m.moveCamera(CameraUpdateFactory.newLatLngBounds(b.build(), pad)) } catch (_: Exception) {}
                    }
                }
            }
            v.onStart(); v.onResume()
        }
    }
    DisposableEffect(Unit) {
        onDispose { try { view.onPause(); view.onStop(); view.onDestroy() } catch (_: Exception) {} }
    }
    AndroidView(factory = { view }, modifier = modifier)
}
