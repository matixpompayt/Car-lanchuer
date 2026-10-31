package pl.mati.carlauncher.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import pl.mati.carlauncher.data.ForecastRepository
import pl.mati.carlauncher.data.LocationRepository
import pl.mati.carlauncher.data.NavRepository
import pl.mati.carlauncher.data.RouteRepository
import pl.mati.carlauncher.data.Prefs
import pl.mati.carlauncher.data.SpeedLimitRepository
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloat
import java.util.Calendar
import kotlin.math.roundToInt

fun formatDistance(m: Double): String =
    if (m >= 1000) "${(m / 1000).toFloat().pl1()} km" else "${(m / 10).roundToInt() * 10} m"

fun formatEta(secondsFromNow: Double): String {
    val c = Calendar.getInstance()
    c.add(Calendar.SECOND, secondsFromNow.roundToInt())
    return formatTime(c.timeInMillis)
}

@Composable
fun MapCard(modifier: Modifier) {
    val nav by NavRepository.state.collectAsState()
    val route by RouteRepository.route.collectAsState()
    val prog by RouteRepository.progress.collectAsState()
    val ctx = LocalContext.current
    val accent = LocalAccent.current

    val engine by Prefs.mapEngine.collectAsState()
    CarCard(modifier, color = CarColors.MapBg) {
        if (engine == "osm") OsmMap(Modifier.fillMaxSize()) else VectorMap(Modifier.fillMaxSize())

        // Następny manewr (z powiadomienia Map Google / Waze)
        nav?.let { n ->
            val icon = remember(n.icon) { n.icon?.asImageBitmap() }
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(16.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(CarColors.Overlay)
                    .clickable { Launch.navigation(ctx) }
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(58.dp).clip(RoundedCornerShape(14.dp)).background(accent),
                    contentAlignment = Alignment.Center,
                ) {
                    if (icon != null) {
                        Image(icon, null, modifier = Modifier.size(42.dp), colorFilter = ColorFilter.tint(CarColors.Bg))
                    } else {
                        Icon(Icons.Rounded.Navigation, null, tint = CarColors.Bg, modifier = Modifier.size(36.dp))
                    }
                }
                Column {
                    Text(n.primary, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    n.secondary?.let {
                        Text(it, fontSize = 19.sp, color = CarColors.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        // Ograniczenie prędkości (OSM) + aktualna prędkość
        SpeedLimitBadge(Modifier.align(Alignment.TopStart).padding(top = if (nav != null) 112.dp else 16.dp, start = 16.dp))

        // Pogoda na trasie
        val rw by ForecastRepository.routeWeather.collectAsState()
        rw?.let { w ->
            Row(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = if (nav != null) 112.dp else 16.dp, end = 16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (w.rain) Color(0xE615324A) else CarColors.Overlay)
                    .clickable { UiState.go(Screen.WEATHER) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(if (w.rain) weatherIcon(w.code) else Icons.Rounded.WbSunny, null, tint = if (w.rain) Color(0xFF6CC4FF) else CarColors.Green, modifier = Modifier.size(22.dp))
                Text(w.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        // Dolny pasek: cel + przyjazd albo szukaj
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(CarColors.Overlay)
                .clickable { if (route == null) UiState.go(Screen.NAV) else Launch.navigation(ctx) }
                .padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val r = route
            val p = prog
            when {
                r != null -> {
                    Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                        if (p?.arrived == true) {
                            Text("Jesteś na miejscu", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        } else {
                            val remM = p?.remainingM ?: r.distanceM
                            val remS = p?.remainingS ?: r.durationS
                            Text(
                                "${formatEta(remS)} przyjazd",
                                fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "${(remS / 60).roundToInt()} min · ${formatDistance(remM)} · ${r.dest.name}",
                                fontSize = 16.sp, color = CarColors.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).background(CarColors.Raised).clickable { RouteRepository.cancel(ctx) },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.Close, "Zakończ trasę", tint = CarColors.Text, modifier = Modifier.size(26.dp)) }
                }
                nav?.extra != null -> {
                    Text(nav?.extra ?: "", fontSize = 20.sp, modifier = Modifier.weight(1f).padding(vertical = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Rounded.Navigation, "Nawigacja", tint = accent, modifier = Modifier.padding(end = 10.dp).size(28.dp))
                }
                else -> {
                    Icon(Icons.Rounded.Search, null, tint = CarColors.Muted, modifier = Modifier.size(26.dp))
                    Text("Dokąd jedziemy?", fontSize = 20.sp, color = CarColors.Muted, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
                    Icon(Icons.Rounded.Navigation, "Nawigacja", tint = accent, modifier = Modifier.padding(end = 10.dp).size(28.dp))
                }
            }
        }
    }
}

/** Znak ograniczenia prędkości jak na drodze; pod nim prędkość auta (czerwona po przekroczeniu). */
@Composable
fun SpeedLimitBadge(modifier: Modifier) {
    val on by Prefs.speedLimits.collectAsState()
    val lim by SpeedLimitRepository.limit.collectAsState()
    val over by SpeedLimitRepository.over.collectAsState()
    val gps by LocationRepository.gpsSpeed.collectAsState()
    if (!on || lim == null) return
    val pulse = if (over) {
        val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "sl")
        val f by t.animateFloat(1f, 1.12f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(450), androidx.compose.animation.core.RepeatMode.Reverse), label = "slf")
        f
    } else 1f
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .size(74.dp)
                .graphicsLayer { scaleX = pulse; scaleY = pulse }
                .clip(CircleShape)
                .background(Color.White)
                .border(9.dp, Color(0xFFE0262B), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(lim.toString(), color = Color(0xFF111111), fontWeight = FontWeight.Bold, fontSize = if (lim!! >= 100) 26.sp else 30.sp)
        }
        gps?.let { v ->
            Box(
                Modifier.clip(RoundedCornerShape(12.dp)).background(if (over) Color(0xFFE0262B) else CarColors.Overlay).padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text("$v km/h", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            }
        }
    }
}

/** Mapa OSM (osmdroid): ciemna, śledzi auto, rysuje wyznaczoną trasę. */
@Composable
private fun OsmMap(modifier: Modifier) {
    val ctx = LocalContext.current
    val lifecycle = (ctx as? ComponentActivity)?.lifecycle
    val accent = LocalAccent.current
    val route by RouteRepository.route.collectAsState()
    val plan by RouteRepository.plan.collectAsState()

    val holder = remember {
        val map = MapView(ctx)
        val me = MyLocationNewOverlay(GpsMyLocationProvider(ctx), map)
        val casing = Polyline(map).apply {
            outlinePaint.color = android.graphics.Color.rgb(10, 12, 15)
            outlinePaint.strokeWidth = 22f
            outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
            outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
            outlinePaint.isAntiAlias = true
        }
        val line = Polyline(map).apply {
            outlinePaint.strokeWidth = 13f
            outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
            outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
            outlinePaint.isAntiAlias = true
        }
        val dest = Polyline(map).apply {
            outlinePaint.color = android.graphics.Color.WHITE
            outlinePaint.strokeWidth = 26f
            outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
        }
        map.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            isTilesScaledToDpi = true
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            overlayManager.tilesOverlay.setColorFilter(TilesOverlay.INVERT_COLORS)
            overlayManager.tilesOverlay.setLoadingBackgroundColor(android.graphics.Color.rgb(17, 22, 28))
            overlayManager.tilesOverlay.setLoadingLineColor(android.graphics.Color.rgb(29, 36, 45))
            controller.setZoom(16.0)
            LocationRepository.location.value?.let { controller.setCenter(GeoPoint(it.latitude, it.longitude)) }
            me.enableMyLocation()
            me.enableFollowLocation()
            overlays.add(casing)
            overlays.add(line)
            overlays.add(dest)
            overlays.add(me)
        }
        MapParts(map, me, casing, line, dest, ArrayList())
    }

    holder.line.outlinePaint.color = accent.toArgb()

    // Trasa: rysowanie + podgląd całej trasy, potem powrót do śledzenia auta
    LaunchedEffect(route?.id) {
        val r = route
        if (r == null || r.points.size < 2) {
            holder.casing.setPoints(emptyList())
            holder.line.setPoints(emptyList())
            holder.dest.setPoints(emptyList())
            holder.map.invalidate()
            holder.me.enableFollowLocation()
            return@LaunchedEffect
        }
        holder.casing.setPoints(r.points)
        holder.line.setPoints(r.points)
        holder.dest.setPoints(listOf(r.dest.point, GeoPoint(r.dest.lat + 0.000001, r.dest.lon)))
        holder.me.disableFollowLocation()
        holder.map.post {
            try {
                holder.map.zoomToBoundingBox(BoundingBox.fromGeoPoints(r.points).increaseByScale(1.25f), true)
            } catch (_: Exception) {
            }
        }
        holder.map.invalidate()
        delay(6000)
        holder.map.controller.setZoom(16.5)
        holder.me.enableFollowLocation()
    }

    // Planowanie: wszystkie warianty, wybrany na wierzchu w kolorze akcentu
    val planKey = plan?.let { p -> p.alternatives.map { it.id } to p.selected }
    LaunchedEffect(planKey, accent) {
        holder.alts.forEach { holder.map.overlays.remove(it) }
        holder.alts.clear()
        val p = plan
        if (p == null || p.alternatives.isEmpty()) {
            if (route == null) holder.me.enableFollowLocation()
            holder.map.invalidate()
            return@LaunchedEffect
        }
        val order = p.alternatives.indices.sortedBy { if (it == p.selected) 1 else 0 }
        for (i in order) {
            val r = p.alternatives[i]
            val sel = i == p.selected
            if (sel) {
                holder.alts += Polyline(holder.map).apply {
                    setPoints(r.points)
                    outlinePaint.color = android.graphics.Color.rgb(10, 12, 15)
                    outlinePaint.strokeWidth = 22f
                    outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                    outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
                }
            }
            holder.alts += Polyline(holder.map).apply {
                setPoints(r.points)
                outlinePaint.color = if (sel) accent.toArgb() else android.graphics.Color.rgb(96, 106, 120)
                outlinePaint.strokeWidth = if (sel) 13f else 10f
                outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
                outlinePaint.isAntiAlias = true
            }
        }
        holder.alts += Polyline(holder.map).apply {
            setPoints(listOf(p.dest.point, GeoPoint(p.dest.lat + 0.000001, p.dest.lon)))
            outlinePaint.color = android.graphics.Color.WHITE
            outlinePaint.strokeWidth = 26f
            outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
        }
        holder.alts.forEachIndexed { k, o -> holder.map.overlays.add(k, o) }
        holder.me.disableFollowLocation()
        val all = p.alternatives.flatMap { it.points }
        holder.map.post {
            try { holder.map.zoomToBoundingBox(BoundingBox.fromGeoPoints(all).increaseByScale(1.2f), true) } catch (_: Exception) {}
        }
        holder.map.invalidate()
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> holder.map.onResume()
                Lifecycle.Event.ON_PAUSE -> holder.map.onPause()
                else -> Unit
            }
        }
        lifecycle?.addObserver(observer)
        if (lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true) holder.map.onResume()
        onDispose {
            lifecycle?.removeObserver(observer)
            holder.map.onPause()
            holder.map.onDetach()
        }
    }

    Box(modifier) {
        AndroidView(factory = { holder.map }, modifier = Modifier.fillMaxSize())
        // przycisk: wróć do auta
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp)
                .size(52.dp)
                .clip(CircleShape)
                .background(CarColors.Overlay)
                .clickable {
                    holder.me.enableFollowLocation()
                    holder.map.controller.setZoom(16.5)
                },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.MyLocation, "Moja pozycja", tint = CarColors.Text, modifier = Modifier.size(26.dp)) }
    }
}

private class MapParts(
    val map: MapView,
    val me: MyLocationNewOverlay,
    val casing: Polyline,
    val line: Polyline,
    val dest: Polyline,
    val alts: MutableList<Polyline>,
)
