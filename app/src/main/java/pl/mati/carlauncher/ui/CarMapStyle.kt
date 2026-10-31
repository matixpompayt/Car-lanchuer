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

    /** Przemalowanie warstw stylu „Liberty” na ciemno. */
    fun applyDark(style: Style) {
        for (layer in style.layers) {
            val id = layer.id.lowercase()
            try {
                when (layer) {
                    is BackgroundLayer -> layer.setProperties(PropertyFactory.backgroundColor(Color.parseColor("#0B0E12")))
                    is FillLayer -> {
                        val c = when {
                            has(id, "water", "ocean", "lake", "river") -> "#0C1A25"
                            has(id, "park", "wood", "forest", "grass", "wetland", "landcover", "scrub", "farmland", "meadow") -> "#0E1914"
                            has(id, "building") -> "#171C23"
                            has(id, "aeroway", "runway") -> "#1A1F27"
                            has(id, "pier", "bridge") -> "#151A21"
                            else -> "#10141A"
                        }
                        // warstwy z deseniem (mokradła, lód…) – ukryj, deseń byłby jasny
                        if (!layer.fillPattern.isNull) layer.setProperties(PropertyFactory.visibility(Property.NONE))
                        else layer.setProperties(
                            PropertyFactory.fillColor(Color.parseColor(c)),
                            PropertyFactory.fillOutlineColor(Color.parseColor(c)),
                        )
                    }
                    is LineLayer -> {
                        val c = when {
                            has(id, "waterway", "water", "river", "stream", "canal") -> "#15344B"
                            has(id, "boundary", "admin") -> "#3A404A"
                            has(id, "rail", "transit") -> "#262C34"
                            has(id, "casing") -> "#06080B"
                            has(id, "motorway", "trunk") -> "#677180"
                            has(id, "primary") -> "#535C69"
                            has(id, "secondary", "tertiary") -> "#434B56"
                            has(id, "path", "footway", "cycle", "track", "pedestrian", "steps") -> "#232931"
                            has(id, "aeroway", "runway", "taxiway") -> "#2B313A"
                            else -> "#333A44"
                        }
                        layer.setProperties(PropertyFactory.lineColor(Color.parseColor(c)))
                    }
                    is SymbolLayer -> {
                        val poi = has(id, "poi")
                        layer.setProperties(
                            PropertyFactory.textColor(Color.parseColor(if (has(id, "water")) "#4E7FA3" else if (poi) "#8C96A3" else "#C3C9D0")),
                            PropertyFactory.textHaloColor(Color.parseColor("#0A0C0F")),
                            PropertyFactory.textHaloWidth(1.4f),
                            PropertyFactory.iconOpacity(if (poi) 0.55f else 0.85f),
                        )
                    }
                    is FillExtrusionLayer -> if (layer.id != BUILDINGS_3D) layer.setProperties(PropertyFactory.visibility(Property.NONE))
                    is RasterLayer -> layer.setProperties(PropertyFactory.visibility(Property.NONE))
                    is HillshadeLayer -> layer.setProperties(PropertyFactory.visibility(Property.NONE))
                }
            } catch (_: Exception) {
            }
        }
    }

    /** Pierwsza warstwa z napisami – nasze warstwy wstawiamy pod nią (napisy zostają na wierzchu). */
    fun firstSymbolId(style: Style): String? = style.layers.firstOrNull { it is SymbolLayer }?.id

    /** Budynki 3D z wysokości OpenStreetMap (render_height). Widoczne od zoomu 14, rosną płynnie. */
    fun add3dBuildings(style: Style, below: String?) {
        if (style.getLayer(BUILDINGS_3D) != null) return
        val src = style.sources.firstOrNull { it.id == "openmaptiles" }?.id ?: return
        val height = Expression.coalesce(Expression.get("render_height"), Expression.literal(6))
        val base = Expression.coalesce(Expression.get("render_min_height"), Expression.literal(0))
        val layer = FillExtrusionLayer(BUILDINGS_3D, src)
            .withSourceLayer("building")
            .withProperties(
                PropertyFactory.fillExtrusionColor(
                    Expression.interpolate(
                        Expression.linear(), Expression.get("render_height"),
                        Expression.stop(0, Expression.color(Color.parseColor("#1B212A"))),
                        Expression.stop(30, Expression.color(Color.parseColor("#232B36"))),
                        Expression.stop(120, Expression.color(Color.parseColor("#2C3644"))),
                    ),
                ),
                PropertyFactory.fillExtrusionHeight(
                    Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(14, 0), Expression.stop(15.5, height)),
                ),
                PropertyFactory.fillExtrusionBase(base),
                PropertyFactory.fillExtrusionOpacity(0.92f),
                PropertyFactory.fillExtrusionVerticalGradient(true),
            )
        layer.minZoom = 14f
        if (below != null) style.addLayerBelow(layer, below) else style.addLayer(layer)
    }

    fun set3d(style: Style, on: Boolean) {
        style.getLayer(BUILDINGS_3D)?.setProperties(PropertyFactory.visibility(if (on) Property.VISIBLE else Property.NONE))
    }
}
