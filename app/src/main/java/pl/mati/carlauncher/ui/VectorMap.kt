package pl.mati.carlauncher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
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
import org.osmdroid.util.GeoPoint
import pl.mati.carlauncher.data.LocationRepository
import pl.mati.carlauncher.data.Prefs
import pl.mati.carlauncher.data.RouteRepository

/** MapView MapLibre + stan (styl gotowy, śledzenie auta). */
private class VectorHolder(ctx: Context) {
    val view: MapView
    var map: MapLibreMap? = null
    var style: Style? = null
    var ready by mutableStateOf(false)
    private val density = ctx.resources.displayMetrics.density

    init {
        MapLibre.getInstance(ctx)
        val opts = MapLibreMapOptions.createFromAttributes(ctx, null).textureMode(true)
        view = MapView(ctx, opts)
        view.onCreate(null)
        view.getMapAsync { m ->
            map = m
            m.uiSettings.apply {
                // pochylenie tylko przyciskiem 3D/2D – przypadkowy ruch dwoma palcami nie spłaszczy mapy
                isTiltGesturesEnabled = false
                isLogoEnabled = false
                isCompassEnabled = false
                isAttributionEnabled = true
                attributionGravity = Gravity.BOTTOM or Gravity.START
                setAttributionMargins((18 * density).toInt(), 0, 0, (98 * density).toInt())
            }
            // od pierwszej klatki widok jak przy jeździe (3D, pochylony, za autem)
            LocationRepository.location.value?.let { m.cameraPosition = cameraFor(it) }
            m.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                    // ktoś przesuwa / przybliża mapę palcem – puść auto, po chwili wróć samo
                    following = false
                    main.removeCallbacks(autoReturn)
                    if (!previewing) main.postDelayed(autoReturn, AUTO_RETURN_MS)
                }
            }
            m.setStyle(Style.Builder().fromUri(CarMapStyle.STYLE_URL)) { st -> onStyle(ctx, m, st) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun onStyle(ctx: Context, m: MapLibreMap, st: Style) {
        CarMapStyle.applyDark(st)
        st.addImage(IMG_EMPTY, android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888))
        val below = CarMapStyle.firstSymbolId(st)
        CarMapStyle.add3dBuildings(st, below)
        st.addSource(GeoJsonSource(SRC_ALTS))
        st.addSource(GeoJsonSource(SRC_ROUTE))
        st.addSource(GeoJsonSource(SRC_DEST))
        fun add(l: org.maplibre.android.style.layers.Layer) { if (below != null) st.addLayerBelow(l, below) else st.addLayer(l) }
        add(
            LineLayer(L_ALTS, SRC_ALTS).withProperties(
                PropertyFactory.lineColor(android.graphics.Color.parseColor("#66717F")),
                PropertyFactory.lineWidth(7f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        add(
            LineLayer(L_CASING, SRC_ROUTE).withProperties(
                PropertyFactory.lineColor(android.graphics.Color.parseColor("#05070A")),
                PropertyFactory.lineWidth(14f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        add(
            LineLayer(L_ROUTE, SRC_ROUTE).withProperties(
                PropertyFactory.lineColor(Prefs.accent.value),
                PropertyFactory.lineWidth(8.5f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        add(
            CircleLayer(L_DEST, SRC_DEST).withProperties(
                PropertyFactory.circleRadius(9f),
                PropertyFactory.circleColor(android.graphics.Color.WHITE),
                PropertyFactory.circleStrokeColor(Prefs.accent.value),
                PropertyFactory.circleStrokeWidth(4f),
            ),
        )
        // auto 3D na wierzchu (zawsze przodem do kamery – wybierana klatka z modelu)
        st.addSource(GeoJsonSource(SRC_ME))
        st.addLayer(
            org.maplibre.android.style.layers.SymbolLayer(L_ME, SRC_ME).withProperties(
                PropertyFactory.iconImage(org.maplibre.android.style.expressions.Expression.get("img")),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_VIEWPORT),
            ),
        )
        m.addOnCameraMoveListener { updateMe() }
        CarMapStyle.set3d(st, Prefs.map3d.value)
        try {
            val lc = m.locationComponent
            lc.activateLocationComponent(
                LocationComponentActivationOptions.builder(ctx, st)
                    .useDefaultLocationEngine(false)
                    .locationComponentOptions(locOptions(ctx, Prefs.accent.value))
                    .build(),
            )
            lc.isLocationComponentEnabled = true
            lc.renderMode = RenderMode.GPS
            LocationRepository.location.value?.let { lc.forceLocationUpdate(it) }
        } catch (_: Exception) {
        }
        style = st
        if (CarColors.isLight) applyTheme(true)
        ready = true
        renderCar()
        follow()
    }

    /** Ikona auta (typ nadwozia + kolor z ustawień) zamiast kropki pozycji. */
    fun locOptions(ctx: Context, @Suppress("UNUSED_PARAMETER") accent: Int = 0): LocationComponentOptions =
        LocationComponentOptions.builder(ctx)
            .gpsName(IMG_EMPTY)
            .foregroundName(IMG_EMPTY)
            .foregroundStaleName(IMG_EMPTY)
            .backgroundName(IMG_EMPTY)
            .backgroundStaleName(IMG_EMPTY)
            .bearingName(IMG_EMPTY)
            .enableStaleState(false)
            .elevation(0f)
            .accuracyAlpha(0f)
            .minZoomIconScale(0.75f)
            .maxZoomIconScale(1.0f)
            .build()

    // ---------- auto 3D ----------
    private val renderer = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var framesReady = false
    private var renderGen = 0
    private var lastBearing = 0f

    /** Renderuje model auta z 36 kątów (co 10°) dla bieżącego widoku 2D/3D – w tle. */
    fun renderCar() {
        val body = Prefs.carBody.value
        val paint = Prefs.carPaint.value
        val is3d = Prefs.map3d.value
        val elev = if (is3d) 32.0 else 90.0
        val size = ((if (is3d) 92 else 70) * density).toInt()
        val gen = ++renderGen
        renderer.execute {
            val faces = Model3D.car(body, paint)
            val frames = (0 until 36).map { i -> Model3D.carIcon(body, paint, i * 10.0, elev, size, faces) }
            main.post {
                val st = style ?: return@post
                if (gen != renderGen) return@post
                frames.forEachIndexed { i, b -> st.addImage("car3d_$i", b) }
                framesReady = true
                updateMe()
            }
        }
    }

    /** Ustawia auto na mapie: pozycja (tam gdzie kamera śledzi auto) i klatka zgodna z kierunkiem jazdy. */
    fun updateMe() {
        val m = map ?: return
        val st = style ?: return
        if (!framesReady) return
        val loc = LocationRepository.location.value ?: return
        val target = if (following) m.cameraPosition.target else null
        val lat = target?.latitude ?: loc.latitude
        val lon = target?.longitude ?: loc.longitude
        takeBearing(loc)
        val rel = ((lastBearing - m.cameraPosition.bearing) % 360 + 360) % 360
        val idx = Math.round(rel / 10.0).toInt() % 36
        val f = Feature.fromGeometry(Point.fromLngLat(lon, lat))
        f.addStringProperty("img", "car3d_$idx")
        st.getSourceAs<GeoJsonSource>(SRC_ME)?.setGeoJson(f)
    }

    // ---------- kamera za autem ----------
    // Własne śledzenie zamiast LocationComponent: przy każdym odczycie GPS kamera płynnie
    // jedzie do auta i ZAWSZE wymusza pochylenie 3D, przybliżenie i kierunek – nic tego nie rozjedzie.
    var following = false
        private set
    private var previewing = false
    private var lastFix = 0L
    private val autoReturn = Runnable { if (!following && !previewing) follow() }

    private fun takeBearing(l: android.location.Location) {
        if (l.hasBearing() && (!l.hasSpeed() || l.speed > 1.5f)) lastBearing = l.bearing
    }

    /**
     * Przybliżenie zależne od prędkości: wolno = kamera blisko auta, im szybciej tym dalej
     * (widać więcej drogi przed autem). Prędkość wygładzona, żeby mapa nie „pompowała”.
     */
    private var smoothKmh = 0.0

    private fun followZoom(l: android.location.Location): Double {
        val kmh = if (l.hasSpeed()) (l.speed * 3.6).coerceIn(0.0, 200.0) else 0.0
        smoothKmh += (kmh - smoothKmh) * 0.35
        val z = zoomForSpeed(smoothKmh)
        return if (Prefs.map3d.value) z else z - 0.6
    }

    private fun zoomForSpeed(kmh: Double): Double {
        // km/h → zoom (3D); między punktami płynnie
        val pts = arrayOf(0.0 to 17.6, 15.0 to 17.5, 30.0 to 17.0, 50.0 to 16.5, 70.0 to 16.0, 90.0 to 15.6, 120.0 to 15.1, 150.0 to 14.8)
        if (kmh <= pts.first().first) return pts.first().second
        for (i in 1 until pts.size) {
            val (k1, z1) = pts[i]
            if (kmh <= k1) {
                val (k0, z0) = pts[i - 1]
                return z0 + (z1 - z0) * (kmh - k0) / (k1 - k0)
            }
        }
        return pts.last().second
    }

    fun cameraFor(l: android.location.Location): CameraPosition {
        takeBearing(l)
        val is3d = Prefs.map3d.value
        val bearing = if (Prefs.mapHeadingUp.value) lastBearing.toDouble() else 0.0
        return CameraPosition.Builder()
            .target(LatLng(l.latitude, l.longitude))
            .zoom(followZoom(l))
            .tilt(if (is3d) 58.0 else 0.0)
            .bearing(bearing)
            .build()
    }

    /** Kamera jedzie za autem (3D: pochylona, opcjonalnie obrót z kierunkiem jazdy). */
    fun follow() {
        val m = map ?: return
        following = true
        previewing = false
        main.removeCallbacks(autoReturn)
        try { m.locationComponent.cameraMode = CameraMode.NONE } catch (_: Exception) {}
        val l = LocationRepository.location.value ?: return
        m.animateCamera(CameraUpdateFactory.newCameraPosition(cameraFor(l)), 800)
    }

    /** Nowa pozycja GPS: kamera płynnie (liniowo, przez czas między odczytami) jedzie za autem. */
    fun onLocation(l: android.location.Location) {
        val m = map ?: return
        if (!ready || !following) return
        val now = android.os.SystemClock.uptimeMillis()
        val dt = if (lastFix == 0L) 1000L else (now - lastFix).coerceIn(300L, 1500L)
        lastFix = now
        m.easeCamera(CameraUpdateFactory.newCameraPosition(cameraFor(l)), dt.toInt(), false)
    }

    /** Dzień / noc: kolory mapy, budynków i obwódki trasy. */
    fun applyTheme(light: Boolean) {
        val st = style ?: return
        CarMapStyle.apply(st, light)
        st.getLayer(L_CASING)?.setProperties(PropertyFactory.lineColor(android.graphics.Color.parseColor(if (light) "#FFFFFF" else "#05070A")))
        st.getLayer(L_ALTS)?.setProperties(PropertyFactory.lineColor(android.graphics.Color.parseColor(if (light) "#9AA3AE" else "#66717F")))
    }

    fun stopFollow() {
        following = false
        main.removeCallbacks(autoReturn)
    }

    fun showBounds(points: List<GeoPoint>) {
        val m = map ?: return
        if (points.size < 2) return
        stopFollow()
        previewing = true
        val b = LatLngBounds.Builder()
        points.forEach { b.include(LatLng(it.latitude, it.longitude)) }
        try {
            m.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 0.0, 0.0, (70 * density).toInt()), 1000)
        } catch (_: Exception) {
        }
    }

    fun setLine(src: String, lines: List<List<GeoPoint>>) {
        val s = style?.getSourceAs<GeoJsonSource>(src) ?: return
        s.setGeoJson(FeatureCollection.fromFeatures(lines.filter { it.size >= 2 }.map { pts ->
            Feature.fromGeometry(LineString.fromLngLats(pts.map { Point.fromLngLat(it.longitude, it.latitude) }))
        }))
    }

    fun setDest(p: GeoPoint?) {
        val s = style?.getSourceAs<GeoJsonSource>(SRC_DEST) ?: return
        s.setGeoJson(FeatureCollection.fromFeatures(listOfNotNull(p?.let { Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)) })))
    }

    companion object {
        const val SRC_ALTS = "caros-alts"
        const val SRC_ROUTE = "caros-route"
        const val SRC_DEST = "caros-dest"
        const val L_ALTS = "caros-alts-line"
        const val L_CASING = "caros-route-casing"
        const val L_ROUTE = "caros-route-line"
        const val L_DEST = "caros-dest-dot"
        const val SRC_ME = "caros-me"
        const val L_ME = "caros-me-car"
        const val IMG_EMPTY = "caros-empty"
        const val AUTO_RETURN_MS = 12_000L
    }
}

/** Mapa wektorowa 3D (OpenFreeMap): ciemny styl CAR OS, budynki 3D, trasa i warianty. */
@Composable
fun VectorMap(modifier: Modifier) {
    val ctx = LocalContext.current
    val lifecycle = (ctx as? ComponentActivity)?.lifecycle
    val accent = LocalAccent.current
    val route by RouteRepository.route.collectAsState()
    val plan by RouteRepository.plan.collectAsState()
    val loc by LocationRepository.location.collectAsState()
    val is3d by Prefs.map3d.collectAsState()
    val headingUp by Prefs.mapHeadingUp.collectAsState()
    val h = remember { VectorHolder(ctx) }
    val ready = h.ready

    // start: pierwsza pozycja → kamera jedzie za autem (jak po dotknięciu „moja pozycja”)
    var followedOnce by remember { mutableStateOf(false) }
    LaunchedEffect(loc != null, ready) {
        if (!ready || loc == null || followedOnce) return@LaunchedEffect
        followedOnce = true
        if (route == null && plan == null) {
            delay(400)
            h.follow()
        }
    }

    // pozycja auta z GPS radia
    LaunchedEffect(loc, ready) {
        val l = loc ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        try { h.map?.locationComponent?.forceLocationUpdate(l) } catch (_: Exception) {}
        h.onLocation(l)
        h.updateMe()
    }

    // kolor akcentu: trasa, cel, strzałka auta
    LaunchedEffect(accent, ready) {
        if (!ready) return@LaunchedEffect
        val a = accent.toArgb()
        h.style?.getLayer(VectorHolder.L_ROUTE)?.setProperties(PropertyFactory.lineColor(a))
        h.style?.getLayer(VectorHolder.L_DEST)?.setProperties(PropertyFactory.circleStrokeColor(a))
    }

    // ikona auta: typ nadwozia i kolor
    val carBody by Prefs.carBody.collectAsState()
    val carPaint by Prefs.carPaint.collectAsState()
    LaunchedEffect(carBody, carPaint, is3d, ready) {
        if (ready) h.renderCar()
    }

    // dzień / noc
    val lightMap = CarColors.isLight
    LaunchedEffect(lightMap, ready) {
        if (ready) h.applyTheme(lightMap)
    }

    // 2D / 3D i obrót mapy
    LaunchedEffect(is3d, headingUp, ready) {
        if (!ready) return@LaunchedEffect
        h.style?.let { CarMapStyle.set3d(it, is3d) }
        if (route == null && plan == null) h.follow()
    }

    // trasa: rysowanie, podgląd całej trasy, po 6 s powrót do auta
    LaunchedEffect(route?.id, ready) {
        if (!ready || plan != null) return@LaunchedEffect
        val r = route
        if (r == null || r.points.size < 2) {
            h.setLine(VectorHolder.SRC_ROUTE, emptyList())
            h.setDest(null)
            h.follow()
            return@LaunchedEffect
        }
        h.setLine(VectorHolder.SRC_ROUTE, listOf(r.points))
        h.setDest(r.dest.point)
        h.showBounds(r.points)
        delay(6000)
        h.follow()
    }

    // planowanie: wybrany wariant w kolorze akcentu, pozostałe szare
    val planKey = plan?.let { p -> p.alternatives.map { it.id } to p.selected }
    LaunchedEffect(planKey, ready) {
        if (!ready) return@LaunchedEffect
        val p = plan
        if (p == null || p.alternatives.isEmpty()) {
            h.setLine(VectorHolder.SRC_ALTS, emptyList())
            val r = route
            h.setLine(VectorHolder.SRC_ROUTE, if (r != null) listOf(r.points) else emptyList())
            h.setDest(r?.dest?.point)
            if (r == null && p == null) h.follow()
            return@LaunchedEffect
        }
        h.setLine(VectorHolder.SRC_ALTS, p.alternatives.filterIndexed { i, _ -> i != p.selected }.map { it.points })
        h.setLine(VectorHolder.SRC_ROUTE, listOfNotNull(p.chosen?.points))
        h.setDest(p.dest.point)
        h.showBounds(p.alternatives.flatMap { it.points })
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> h.view.onStart()
                Lifecycle.Event.ON_RESUME -> h.view.onResume()
                Lifecycle.Event.ON_PAUSE -> h.view.onPause()
                Lifecycle.Event.ON_STOP -> h.view.onStop()
                else -> Unit
            }
        }
        val st = lifecycle?.currentState
        if (st?.isAtLeast(Lifecycle.State.STARTED) == true) h.view.onStart()
        if (st?.isAtLeast(Lifecycle.State.RESUMED) == true) h.view.onResume()
        lifecycle?.addObserver(observer)
        onDispose {
            lifecycle?.removeObserver(observer)
            try {
                h.view.onPause()
                h.view.onStop()
                h.view.onDestroy()
            } catch (_: Exception) {
            }
        }
    }

    Box(modifier) {
        AndroidView(factory = { h.view }, modifier = Modifier.fillMaxSize())
        Column(
            Modifier.align(Alignment.CenterEnd).padding(end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 2D / 3D
            Box(
                Modifier.size(52.dp).clip(CircleShape).background(CarColors.Overlay).clickable { Prefs.setMap3d(!is3d) },
                contentAlignment = Alignment.Center,
            ) { Text(if (is3d) "3D" else "2D", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = if (is3d) accent else CarColors.Text) }
            // obrót z kierunkiem jazdy / północ u góry
            Box(
                Modifier.size(52.dp).clip(CircleShape).background(CarColors.Overlay).clickable { Prefs.setMapHeadingUp(!headingUp) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (headingUp) Icons.Rounded.Navigation else Icons.Rounded.Explore,
                    if (headingUp) "Mapa obraca się z autem" else "Północ u góry",
                    tint = if (headingUp) accent else CarColors.Text, modifier = Modifier.size(26.dp),
                )
            }
            // wróć do auta
            Box(
                Modifier.size(52.dp).clip(CircleShape).background(CarColors.Overlay).clickable { h.follow() },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.MyLocation, "Moja pozycja", tint = CarColors.Text, modifier = Modifier.size(26.dp)) }
        }
    }
}
