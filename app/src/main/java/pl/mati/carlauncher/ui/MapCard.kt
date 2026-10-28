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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.TilesOverlay
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import pl.mati.carlauncher.data.LocationRepository
import pl.mati.carlauncher.data.NavRepository

@Composable
fun MapCard(modifier: Modifier) {
    val nav by NavRepository.state.collectAsState()
    val ctx = LocalContext.current
    val accent = LocalAccent.current

    CarCard(modifier, color = CarColors.MapBg) {
        OsmMap(Modifier.fillMaxSize())

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

        // Dolny pasek: przyjazd albo szukaj
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(CarColors.Overlay)
                .clickable { Launch.navigation(ctx) }
                .padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val extra = nav?.extra
            if (extra != null) {
                Text(extra, fontSize = 20.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                Icon(Icons.Rounded.Search, null, tint = CarColors.Muted, modifier = Modifier.size(26.dp))
                Text("Dokąd jedziemy?", fontSize = 20.sp, color = CarColors.Muted, modifier = Modifier.weight(1f))
            }
            Icon(Icons.Rounded.Navigation, "Nawigacja", tint = accent, modifier = Modifier.size(28.dp))
        }
    }
}

/** Mapa OSM (osmdroid), ciemna przez odwrócenie kolorów, śledzi pozycję auta. */
@Composable
private fun OsmMap(modifier: Modifier) {
    val ctx = LocalContext.current
    val lifecycle = (ctx as? ComponentActivity)?.lifecycle

    val mapView = remember {
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            isTilesScaledToDpi = true
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            overlayManager.tilesOverlay.setColorFilter(TilesOverlay.INVERT_COLORS)
            overlayManager.tilesOverlay.setLoadingBackgroundColor(android.graphics.Color.rgb(17, 22, 28))
            overlayManager.tilesOverlay.setLoadingLineColor(android.graphics.Color.rgb(29, 36, 45))
            controller.setZoom(16.0)
            LocationRepository.location.value?.let {
                controller.setCenter(GeoPoint(it.latitude, it.longitude))
            }
            val me = MyLocationNewOverlay(GpsMyLocationProvider(ctx), this)
            me.enableMyLocation()
            me.enableFollowLocation()
            overlays.add(me)
        }
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycle?.addObserver(observer)
        if (lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true) mapView.onResume()
        onDispose {
            lifecycle?.removeObserver(observer)
            mapView.onPause()
            mapView.onDetach()
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}
