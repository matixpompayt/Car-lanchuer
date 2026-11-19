package pl.mati.carlauncher.ui

import android.graphics.Color
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.BackgroundLayer
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.HillshadeLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer

/**
 * Mapa wektorowa OpenFreeMap (dane OpenStreetMap, bez klucza i limitów)
 * przemalowana na ciemny styl CAR OS + własne budynki 3D.
 */
object CarMapStyle {

    const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
    const val BUILDINGS_3D = "caros-buildings-3d"
    /** Płaskie budynki (widok 2D): wypełnienie + obrys, żeby było widać kształt domów. */
    const val BUILDINGS_2D = "caros-buildings-2d"
    const val BUILDINGS_2D_LINE = "caros-buildings-2d-line"
    /** Numery domów (warstwa „housenumber” z OpenMapTiles). */
    const val HOUSENUM = "caros-housenumber"

    private fun has(id: String, vararg keys: String) = keys.any { id.contains(it) }

    private class MapColors(
        val bg: String, val water: String, val green: String, val building: String, val aero: String, val pier: String, val land: String,
        val lWater: String, val lBoundary: String, val lRail: String, val lCasing: String, val lMotorway: String, val lPrimary: String,
        val lSecondary: String, val lPath: String, val lAero: String, val lRoad: String,
        val text: String, val textPoi: String, val textWater: String, val halo: String,
        val b0: String, val b1: String, val b2: String,
        /** Płaskie budynki 2D (wypełnienie, obrys), numery domów (tekst, obwódka). */
        val f2d: String, val l2d: String, val hn: String, val hnHalo: String,
        /** Poświata pod głównymi drogami (styl Miami nocą). */
        val glow: Boolean = false,
    )

    private val DARK = MapColors(
        "#0B0E12", "#0C1A25", "#0E1914", "#171C23", "#1A1F27", "#151A21", "#10141A",
        "#15344B", "#3A404A", "#262C34", "#06080B", "#677180", "#535C69", "#434B56", "#232931", "#2B313A", "#333A44",
        "#C3C9D0", "#8C96A3", "#4E7FA3", "#0A0C0F",
        "#1B212A", "#232B36", "#2C3644",
        "#1D242E", "#36414F", "#9CA6B3", "#0A0C0F",
    )

    /** Dzień: jasna, ale stonowana (nie biała) – pasuje do jasnego interfejsu CAR OS. */
    private val LIGHT = MapColors(
        "#DADFE4", "#A9C6DC", "#C4D9C3", "#CBD0D7", "#D2D6DC", "#D4D8DE", "#D6DBE0",
        "#8FB6D3", "#A3A9B2", "#B3B8C0", "#B9BFC7", "#F1D9A0", "#F7F8FA", "#F4F5F7", "#C9CED5", "#C3C8CF", "#EEF0F3",
        "#2F3640", "#56606C", "#3C6F96", "#EEF1F4",
        "#C9CED6", "#BCC3CC", "#AEB7C2",
        "#C3C9D1", "#9DA6B2", "#4A5460", "#EEF1F4",
    )

    /** Miami – dzień (złota godzina): piasek, turkusowa woda, pastelowe budynki w ciepłym świetle. */
    private val MIAMI_DAY = MapColors(
        "#F7E2CF", "#3FC6DA", "#A9DDB4", "#F4C7BC", "#F0D6C6", "#EDD0C0", "#F5E0CC",
        "#2BB3CC", "#D597BB", "#CFAFC4", "#E79BB6", "#FF4F98", "#FF9F45", "#FFE2B8", "#E9C6BC", "#E6C2D2", "#FFFFFF",
        "#4A1F40", "#7E4372", "#11738A", "#FFF4EA",
        "#F9D0C4", "#F5AFC3", "#D3A2EA",
        "#F4C7BC", "#E596B0", "#6E3A60", "#FFF4EA",
    )

    /** Miami – wieczór: fioletowe miasto, neonowe drogi z poświatą, różowe budynki w świetle zachodu. */
    private val MIAMI_NIGHT = MapColors(
        "#22143A", "#0E3550", "#163A3A", "#3A2150", "#2A1A44", "#2A1844", "#25163D",
        "#2A7FB0", "#7A4AA8", "#4A3470", "#12081F", "#FF3D9A", "#FF9A3D", "#FFC94A", "#5A3C82", "#5E4590", "#C04FD0",
        "#FFF0FA", "#FFC2E6", "#7FE3FF", "#1A0B2A",
        "#7A4878", "#A2588A", "#C46FA8",
        "#3A2150", "#FF4FB8", "#FFD1EC", "#1A0B2A",
        glow = true,
    )

    const val THEME_STD = "std"
    const val THEME_MIAMI = "miami"
    /** Poświata neonowych dróg (pod drogami). */
    private const val GLOW = "caros-road-glow"
    /** Miami: przerywane linie środkowe na głównych drogach i neonowe obrysy budynków (wieczorem, w 3D). */
    private const val MARKS = "caros-road-marks"
    const val NEON = "caros-neon-outline"

    private fun pal(light: Boolean): MapColors = when (pl.mati.carlauncher.data.Prefs.mapTheme.value) {
        THEME_MIAMI -> if (light) MIAMI_DAY else MIAMI_NIGHT
        else -> if (light) LIGHT else DARK
    }

    /** Przemalowanie warstw stylu „Liberty”: ciemno (noc) albo jasno (dzień). */
    fun apply(style: Style, light: Boolean) {
        val p = pal(light)
        for (layer in style.layers) {
            val id = layer.id.lowercase()
            try {
                when (layer) {
                    is BackgroundLayer -> layer.setProperties(PropertyFactory.backgroundColor(Color.parseColor(p.bg)))
                    is FillLayer -> {
                        val c = when {
                            has(id, "water", "ocean", "lake", "river") -> p.water
                            has(id, "park", "wood", "forest", "grass", "wetland", "landcover", "scrub", "farmland", "meadow") -> p.green
                            has(id, "building") -> p.building
                            has(id, "aeroway", "runway") -> p.aero
                            has(id, "pier", "bridge") -> p.pier
                            else -> p.land
                        }
                        // warstwy z deseniem (mokradła, lód…) – ukryj, deseń byłby jasny
                        if (!layer.fillPattern.isNull) layer.setProperties(PropertyFactory.visibility(Property.NONE))
                        else layer.setProperties(
                            PropertyFactory.fillColor(Color.parseColor(c)),
                            PropertyFactory.fillOutlineColor(Color.parseColor(c)),
                        )
                    }
                    is LineLayer -> {
                        if (id.startsWith("caros-")) continue
                        val c = when {
                            has(id, "waterway", "water", "river", "stream", "canal") -> p.lWater
                            has(id, "boundary", "admin") -> p.lBoundary
                            has(id, "rail", "transit") -> p.lRail
                            has(id, "casing") -> p.lCasing
                            has(id, "motorway", "trunk") -> p.lMotorway
                            has(id, "primary") -> p.lPrimary
                            has(id, "secondary", "tertiary") -> p.lSecondary
                            has(id, "path", "footway", "cycle", "track", "pedestrian", "steps") -> p.lPath
                            has(id, "aeroway", "runway", "taxiway") -> p.lAero
                            else -> p.lRoad
                        }
                        layer.setProperties(PropertyFactory.lineColor(Color.parseColor(c)))
                        // szersze drogi – lepiej widać w aucie
                        if (layer.sourceLayer == "transportation") roadWidth(id)?.let { layer.setProperties(PropertyFactory.lineWidth(it)) }
                    }
                    is SymbolLayer -> {
                        if (id.startsWith("caros-")) continue
                        val poi = has(id, "poi")
                        layer.setProperties(
                            PropertyFactory.textColor(Color.parseColor(if (has(id, "water")) p.textWater else if (poi) p.textPoi else p.text)),
                            PropertyFactory.textHaloColor(Color.parseColor(p.halo)),
                            PropertyFactory.textHaloWidth(1.4f),
                            PropertyFactory.iconOpacity(if (poi) 0.55f else 0.85f),
                        )
                    }
                    is FillExtrusionLayer -> if (!layer.id.startsWith(BUILDINGS_3D)) layer.setProperties(PropertyFactory.visibility(Property.NONE))
                    is RasterLayer -> layer.setProperties(PropertyFactory.visibility(Property.NONE))
                    is HillshadeLayer -> layer.setProperties(PropertyFactory.visibility(Property.NONE))
                }
            } catch (_: Exception) {
            }
        }
        swapBuildings(style, light)
        recolorOwn(style, light)
        setGlow(style, p)
        setExtras(style, p, pl.mati.carlauncher.data.Prefs.mapTheme.value == THEME_MIAMI, light, pl.mati.carlauncher.data.Prefs.map3d.value)
    }

    /** Światło brył (ciepły zachód słońca w Miami), linie środkowe dróg, neonowe obrysy budynków. */
    private fun setExtras(style: Style, p: MapColors, miami: Boolean, light: Boolean, show3d: Boolean) {
        try {
            style.light?.let { l ->
                if (miami) {
                    l.setAnchor(Property.ANCHOR_MAP)
                    l.setColor(Color.parseColor(if (light) "#FFE3C0" else "#FFB27E"))
                    l.setIntensity(if (light) 0.42f else 0.62f)
                    l.setPosition(org.maplibre.android.style.light.Position(1.2f, 250f, if (light) 35f else 55f))
                } else {
                    l.setAnchor(Property.ANCHOR_VIEWPORT)
                    l.setColor(Color.WHITE)
                    l.setIntensity(0.5f)
                    l.setPosition(org.maplibre.android.style.light.Position(1.15f, 210f, 30f))
                }
            }
        } catch (_: Exception) {
        }
        try {
            val lastRoad = style.layers.lastOrNull { it is LineLayer && it.sourceLayer == "transportation" && !it.id.startsWith("caros-") } as? LineLayer
            if (!miami || lastRoad == null) {
                listOf(MARKS, NEON).forEach { id -> style.getLayer(id)?.let { style.removeLayer(it) } }
                return
            }
            val src = lastRoad.sourceId
            val markColor = Color.parseColor(if (light) "#FFFFFF" else "#FFD84D")
            val marks = style.getLayer(MARKS) as? LineLayer
            if (marks == null) {
                val m = LineLayer(MARKS, src).withSourceLayer("transportation").withProperties(
                    PropertyFactory.lineColor(markColor),
                    PropertyFactory.lineWidth(
                        Expression.interpolate(Expression.exponential(1.4f), Expression.zoom(), Expression.stop(14.5, 0.8f), Expression.stop(17, 2.2f), Expression.stop(19, 5f)),
                    ),
                    PropertyFactory.lineDasharray(arrayOf(2.2f, 2.6f)),
                    PropertyFactory.lineOpacity(0.85f),
                )
                m.minZoom = 14.5f
                m.setFilter(
                    Expression.match(
                        Expression.get("class"),
                        Expression.literal("primary"), Expression.literal(true),
                        Expression.literal("secondary"), Expression.literal(true),
                        Expression.literal("tertiary"), Expression.literal(true),
                        Expression.literal(false),
                    ),
                )
                style.addLayerAbove(m, lastRoad.id)
            } else {
                marks.setProperties(PropertyFactory.lineColor(markColor))
            }
            val neon = style.getLayer(NEON)
            if (light) {
                neon?.let { style.removeLayer(it) }
            } else if (neon == null) {
                val n = LineLayer(NEON, src).withSourceLayer("building").withProperties(
                    PropertyFactory.lineColor(Color.parseColor("#FF4FB8")),
                    PropertyFactory.lineWidth(
                        Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(15, 0.6f), Expression.stop(17, 1.6f), Expression.stop(19, 3f)),
                    ),
                    PropertyFactory.lineOpacity(0.8f),
                    PropertyFactory.lineBlur(0.6f),
                    PropertyFactory.visibility(if (show3d) Property.VISIBLE else Property.NONE),
                )
                n.minZoom = 15f
                style.addLayerAbove(n, lastRoad.id)
            }
        } catch (_: Exception) {
        }
    }

    /** Neonowa poświata: szeroka, rozmyta linia pod autostradami i głównymi drogami. */
    private fun setGlow(style: Style, p: MapColors) {
        try {
            val cur = style.getLayer(GLOW)
            if (!p.glow) {
                cur?.let { style.removeLayer(it) }
                return
            }
            if (cur != null) return
            val firstRoad = style.layers.firstOrNull { it is LineLayer && it.sourceLayer == "transportation" && !it.id.startsWith("caros-") } ?: return
            val src = (firstRoad as LineLayer).sourceId
            val cls = Expression.get("class")
            val layer = LineLayer(GLOW, src).withSourceLayer("transportation").withProperties(
                PropertyFactory.lineColor(
                    Expression.match(
                        cls,
                        Expression.literal("motorway"), Expression.color(Color.parseColor(p.lMotorway)),
                        Expression.literal("trunk"), Expression.color(Color.parseColor(p.lMotorway)),
                        Expression.literal("primary"), Expression.color(Color.parseColor(p.lPrimary)),
                        Expression.color(Color.parseColor(p.lSecondary)),
                    ),
                ),
                PropertyFactory.lineWidth(
                    Expression.interpolate(
                        Expression.exponential(1.35f), Expression.zoom(),
                        Expression.stop(6, 3f), Expression.stop(10, 9f), Expression.stop(13, 18f), Expression.stop(15, 30f), Expression.stop(17, 56f), Expression.stop(19, 120f),
                    ),
                ),
                PropertyFactory.lineBlur(
                    Expression.interpolate(
                        Expression.exponential(1.35f), Expression.zoom(),
                        Expression.stop(6, 3f), Expression.stop(10, 8f), Expression.stop(13, 16f), Expression.stop(15, 26f), Expression.stop(17, 48f), Expression.stop(19, 100f),
                    ),
                ),
                PropertyFactory.lineOpacity(0.32f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            )
            layer.setFilter(
                Expression.match(
                    cls,
                    Expression.literal("motorway"), Expression.literal(true),
                    Expression.literal("trunk"), Expression.literal(true),
                    Expression.literal("primary"), Expression.literal(true),
                    Expression.literal("secondary"), Expression.literal(true),
                    Expression.literal(false),
                ),
            )
            style.addLayerBelow(layer, firstRoad.id)
        } catch (_: Exception) {
        }
    }

    // kolory naszych warstw 2D i numerów (pętla wyżej ich nie rusza albo nadpisuje kolorem z palety)
    private fun recolorOwn(style: Style, light: Boolean) {
        val p = pal(light)
        try {
            (style.getLayer(BUILDINGS_2D) as? FillLayer)?.setProperties(
                PropertyFactory.fillColor(Color.parseColor(p.f2d)),
                PropertyFactory.fillOutlineColor(Color.parseColor(p.f2d)),
            )
            (style.getLayer(BUILDINGS_2D_LINE) as? LineLayer)?.setProperties(
                PropertyFactory.lineColor(Color.parseColor(p.l2d)),
            )
            (style.getLayer(HOUSENUM) as? SymbolLayer)?.setProperties(
                PropertyFactory.textColor(Color.parseColor(p.hn)),
                PropertyFactory.textHaloColor(Color.parseColor(p.hnHalo)),
            )
        } catch (_: Exception) {
        }
    }

    /** Płaskie kształty budynków – widoczne w 2D (w 3D są bryły). */
    fun add2dBuildings(style: Style, below: String?, light: Boolean) {
        if (style.getLayer(BUILDINGS_2D) != null) return
        val src = style.sources.firstOrNull { it.id == "openmaptiles" }?.id ?: return
        val fill = FillLayer(BUILDINGS_2D, src).withSourceLayer("building").withProperties(
            PropertyFactory.fillOpacity(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(14, 0f), Expression.stop(15, 1f))),
        )
        fill.minZoom = 14f
        val line = LineLayer(BUILDINGS_2D_LINE, src).withSourceLayer("building").withProperties(
            PropertyFactory.lineWidth(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(15, 0.6f), Expression.stop(18, 1.6f))),
            PropertyFactory.lineOpacity(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(14.5, 0f), Expression.stop(15.5, 1f))),
        )
        line.minZoom = 14.5f
        // pod budynkami 3D (gdyby oba były widoczne), nad drogami
        val anchor = style.layers.firstOrNull { it.id.startsWith(BUILDINGS_3D) }?.id ?: below
        if (anchor != null) { style.addLayerBelow(fill, anchor); style.addLayerBelow(line, anchor) } else { style.addLayer(fill); style.addLayer(line) }
        recolorOwn(style, light)
    }

    /** Numery domów od dużego przybliżenia (ok. 16,5) – nad budynkami, pod resztą napisów. */
    fun addHouseNumbers(style: Style, below: String?, light: Boolean) {
        if (style.getLayer(HOUSENUM) != null) return
        val src = style.sources.firstOrNull { it.id == "openmaptiles" }?.id ?: return
        // oryginalna warstwa stylu – ukryj (żeby nie było dubli), weź z niej czcionkę
        var font: Array<String>? = null
        style.layers.filterIsInstance<SymbolLayer>().filter { it.sourceLayer == "housenumber" && !it.id.startsWith("caros-") }.forEach {
            try { if (font == null) font = it.textFont.value } catch (_: Exception) {}
            it.setProperties(PropertyFactory.visibility(Property.NONE))
        }
        val layer = SymbolLayer(HOUSENUM, src).withSourceLayer("housenumber").withProperties(
            PropertyFactory.textField(Expression.toString(Expression.get("housenumber"))),
            PropertyFactory.textFont(font ?: arrayOf("Noto Sans Regular")),
            PropertyFactory.textSize(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(16.5, 11f), Expression.stop(19, 15f))),
            PropertyFactory.textHaloWidth(1.3f),
            PropertyFactory.textPadding(3f),
            PropertyFactory.textAllowOverlap(false),
        )
        layer.minZoom = 16.5f
        if (below != null) style.addLayerBelow(layer, below) else style.addLayer(layer)
        recolorOwn(style, light)
    }

    fun setHouseNumbers(style: Style, on: Boolean) {
        try { style.getLayer(HOUSENUM)?.setProperties(PropertyFactory.visibility(if (on) Property.VISIBLE else Property.NONE)) } catch (_: Exception) {}
    }

    /**
     * Szerokości dróg (dp wg zoomu) – ok. 1,6–2× szersze niż w stylu OpenFreeMap.
     * Obrys („casing”) szerszy o ~3 dp, żeby droga miała wyraźną krawędź.
     */
    private fun roadWidth(id: String): org.maplibre.android.style.expressions.Expression? {
        if (has(id, "path", "footway", "cycle", "track", "pedestrian", "steps", "rail", "transit", "aeroway", "runway", "taxiway", "ferry", "pier", "bridleway")) return null
        val stops: List<Pair<Float, Float>> = when {
            has(id, "motorway", "trunk") -> listOf(5f to 1f, 8f to 2.2f, 10f to 3.5f, 13f to 7f, 15f to 13f, 17f to 26f, 19f to 60f)
            has(id, "primary") -> listOf(7f to 1f, 10f to 2.8f, 13f to 6f, 15f to 11f, 17f to 22f, 19f to 52f)
            has(id, "secondary", "tertiary") -> listOf(8f to 0.8f, 11f to 2.2f, 13f to 4.8f, 15f to 9f, 17f to 18f, 19f to 44f)
            has(id, "service", "track") -> listOf(13f to 0.8f, 15f to 3f, 17f to 7f, 19f to 18f)
            else -> listOf(11f to 0.8f, 13f to 2.4f, 15f to 6f, 17f to 13f, 19f to 32f)
        }
        val casing = has(id, "casing")
        val st = stops.map { (z, w) ->
            org.maplibre.android.style.expressions.Expression.stop(z, if (casing) w + (if (z >= 13f) 3f else 1f) else w)
        }.toTypedArray()
        return org.maplibre.android.style.expressions.Expression.interpolate(
            org.maplibre.android.style.expressions.Expression.exponential(1.35f),
            org.maplibre.android.style.expressions.Expression.zoom(),
            *st,
        )
    }

    private fun buildingsId(light: Boolean) = BUILDINGS_3D + "-" + pl.mati.carlauncher.data.Prefs.mapTheme.value + if (light) "-l" else "-d"

    /**
     * Kolor budynków zależy od wysokości (wyrażenie per budynek), a MapLibre „wypieka” go
     * w już wczytanych kafelkach – zmiana koloru byłaby widoczna dopiero po przybliżeniu.
     * Dlatego przy zmianie dzień/noc wstawiamy nową warstwę w nowych kolorach pod starą,
     * a starą usuwamy chwilę później (bez mrugnięcia).
     */
    private fun swapBuildings(style: Style, light: Boolean) {
        val want = buildingsId(light)
        if (style.getLayer(want) != null) return
        val old = style.layers.firstOrNull { it is FillExtrusionLayer && it.id.startsWith(BUILDINGS_3D) } ?: return
        val src = (old as FillExtrusionLayer).sourceId
        val fresh = buildingsLayer(want, src, light)
        try {
            fresh.setProperties(PropertyFactory.visibility(old.visibility.value ?: Property.VISIBLE))
        } catch (_: Exception) {
        }
        style.addLayerBelow(fresh, old.id)
        val oldId = old.id
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try { style.removeLayer(oldId) } catch (_: Exception) {}
        }, 450)
    }

    private fun buildingsLayer(id: String, src: String, light: Boolean): FillExtrusionLayer {
        val height = Expression.coalesce(Expression.get("render_height"), Expression.literal(6))
        val base = Expression.coalesce(Expression.get("render_min_height"), Expression.literal(0))
        val layer = FillExtrusionLayer(id, src)
            .withSourceLayer("building")
            .withProperties(
                PropertyFactory.fillExtrusionColor(buildingColor(light)),
                PropertyFactory.fillExtrusionHeight(
                    Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(14, 0), Expression.stop(15.5, height)),
                ),
                PropertyFactory.fillExtrusionBase(base),
                PropertyFactory.fillExtrusionOpacity(0.92f),
                PropertyFactory.fillExtrusionVerticalGradient(true),
            )
        layer.minZoom = 14f
        return layer
    }

    fun applyDark(style: Style) = apply(style, false)

    private fun buildingColor(light: Boolean): Expression {
        val p = pal(light)
        return Expression.interpolate(
            Expression.linear(), Expression.get("render_height"),
            Expression.stop(0, Expression.color(Color.parseColor(p.b0))),
            Expression.stop(30, Expression.color(Color.parseColor(p.b1))),
            Expression.stop(120, Expression.color(Color.parseColor(p.b2))),
        )
    }

    /** Pierwsza warstwa z napisami – nasze warstwy wstawiamy pod nią (napisy zostają na wierzchu). */
    fun firstSymbolId(style: Style): String? = style.layers.firstOrNull { it is SymbolLayer }?.id

    /** Budynki 3D z wysokości OpenStreetMap (render_height). Widoczne od zoomu 14, rosną płynnie. */
    fun add3dBuildings(style: Style, below: String?, light: Boolean = false) {
        if (style.layers.any { it.id.startsWith(BUILDINGS_3D) }) return
        val src = style.sources.firstOrNull { it.id == "openmaptiles" }?.id ?: return
        val layer = buildingsLayer(buildingsId(light), src, light)
        if (below != null) style.addLayerBelow(layer, below) else style.addLayer(layer)
    }

    fun set3d(style: Style, on: Boolean) {
        style.layers.filter { it.id.startsWith(BUILDINGS_3D) }.forEach {
            it.setProperties(PropertyFactory.visibility(if (on) Property.VISIBLE else Property.NONE))
        }
        // w 2D wyraźne kształty domów zamiast brył
        listOf(BUILDINGS_2D, BUILDINGS_2D_LINE).forEach { id ->
            try { style.getLayer(id)?.setProperties(PropertyFactory.visibility(if (on) Property.NONE else Property.VISIBLE)) } catch (_: Exception) {}
        }
        // neonowe obrysy pod bryłami – tylko w 3D (w 2D są zwykłe obrysy domów)
        try { style.getLayer(NEON)?.setProperties(PropertyFactory.visibility(if (on) Property.VISIBLE else Property.NONE)) } catch (_: Exception) {}
    }
}
