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

    private fun has(id: String, vararg keys: String) = keys.any { id.contains(it) }

    private class MapColors(
        val bg: String, val water: String, val green: String, val building: String, val aero: String, val pier: String, val land: String,
        val lWater: String, val lBoundary: String, val lRail: String, val lCasing: String, val lMotorway: String, val lPrimary: String,
        val lSecondary: String, val lPath: String, val lAero: String, val lRoad: String,
        val text: String, val textPoi: String, val textWater: String, val halo: String,
        val b0: String, val b1: String, val b2: String,
    )

    private val DARK = MapColors(
        "#0B0E12", "#0C1A25", "#0E1914", "#171C23", "#1A1F27", "#151A21", "#10141A",
        "#15344B", "#3A404A", "#262C34", "#06080B", "#677180", "#535C69", "#434B56", "#232931", "#2B313A", "#333A44",
        "#C3C9D0", "#8C96A3", "#4E7FA3", "#0A0C0F",
        "#1B212A", "#232B36", "#2C3644",
    )

    /** Dzień: jasna, ale stonowana (nie biała) – pasuje do jasnego interfejsu CAR OS. */
    private val LIGHT = MapColors(
        "#DADFE4", "#A9C6DC", "#C4D9C3", "#CBD0D7", "#D2D6DC", "#D4D8DE", "#D6DBE0",
        "#8FB6D3", "#A3A9B2", "#B3B8C0", "#B9BFC7", "#F1D9A0", "#F7F8FA", "#F4F5F7", "#C9CED5", "#C3C8CF", "#EEF0F3",
        "#2F3640", "#56606C", "#3C6F96", "#EEF1F4",
        "#C9CED6", "#BCC3CC", "#AEB7C2",
    )

    /** Przemalowanie warstw stylu „Liberty”: ciemno (noc) albo jasno (dzień). */
    fun apply(style: Style, light: Boolean) {
        val p = if (light) LIGHT else DARK
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

    private fun buildingsId(light: Boolean) = BUILDINGS_3D + if (light) "-l" else "-d"

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
        val p = if (light) LIGHT else DARK
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
    }
}
