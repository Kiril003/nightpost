package local.phantom.companion.feature.stream.ui.map

import local.phantom.companion.feature.stream.ui.map.icons.GroundTokens
import local.phantom.companion.feature.stream.ui.map.icons.marks.MarkPlate
import local.phantom.companion.feature.stream.ui.widget.MapPayload
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource

internal const val ROUTE_ENDS_SOURCE_ID = "phantom-route-ends-source"
internal const val ROUTE_START_LAYER_ID = "phantom-route-start"
internal const val ROUTE_END_LAYER_ID = "phantom-route-end"

private val endsStyles: MutableMap<Style, Boolean> = java.util.WeakHashMap()

/**
 * Кінці обраного маршруту. Віха мети лягає ПІД тіла світу і змагається з
 * ними за місце: якщо мета — аптека, її вже несе бурштинове тіло аптеки, і
 * друга позначка на тій самій точці була б тим самим місцем двічі.
 */
internal fun installRouteEnds(style: Style, isDark: Boolean, context: android.content.Context) {
    if (endsStyles.put(style, isDark) != isDark) {
        val density = context.resources.displayMetrics.density
        style.addImage(GroundTokens.ROUTE_END_IMAGE, GroundTokens.routeEnd(isDark, density))
        style.addImage(GroundTokens.ROUTE_START_IMAGE, GroundTokens.routeStart(isDark, density))
    }
    if (style.getSource(ROUTE_ENDS_SOURCE_ID) == null) {
        style.addSource(GeoJsonSource(ROUTE_ENDS_SOURCE_ID, EMPTY_FC))
    }
    if (style.getLayer(ROUTE_START_LAYER_ID) == null) {
        val start = SymbolLayer(ROUTE_START_LAYER_ID, ROUTE_ENDS_SOURCE_ID).withProperties(
            PropertyFactory.iconImage(GroundTokens.ROUTE_START_IMAGE),
            PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
        )
        start.setFilter(Expression.eq(Expression.get("end"), Expression.literal("start")))
        addRouteEndLayer(style, start)
    }
    if (style.getLayer(ROUTE_END_LAYER_ID) == null) {
        val end = SymbolLayer(ROUTE_END_LAYER_ID, ROUTE_ENDS_SOURCE_ID).withProperties(
            PropertyFactory.iconImage(GroundTokens.ROUTE_END_IMAGE),
            PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
            PropertyFactory.iconOffset(arrayOf(0f, MarkPlate.ANCHOR_SLACK_DP)),
            PropertyFactory.iconSize(
                Expression.interpolate(
                    Expression.linear(), Expression.zoom(),
                    Expression.stop(12f, 0.7f),
                    Expression.stop(16f, 0.9f),
                    Expression.stop(17.5f, 1f),
                ),
            ),
            PropertyFactory.iconAllowOverlap(false),
            PropertyFactory.iconIgnorePlacement(false),
            PropertyFactory.iconPadding(0f),
        )
        end.setFilter(Expression.eq(Expression.get("end"), Expression.literal("end")))
        addRouteEndLayer(style, end)
    }
}

private fun addRouteEndLayer(style: Style, layer: SymbolLayer) {
    when {
        style.getLayer(MARKS_BAR_LAYER) != null -> style.addLayerBelow(layer, MARKS_BAR_LAYER)
        style.getLayer(OPERATOR_HALO_LAYER) != null -> style.addLayerBelow(layer, OPERATOR_HALO_LAYER)
        else -> style.addLayer(layer)
    }
}

/** Початок лише поза супроводом: у дорозі початок — це сама людина. */
internal fun routeEndsGeoJson(routes: List<MapPayload.PolylineData>, guiding: Boolean): String {
    val pts = routes.lastOrNull()?.points ?: return EMPTY_FC
    if (pts.size < 2) return EMPTY_FC
    fun f(kind: String, p: MapPayload.PointData) =
        """{"type":"Feature","properties":{"end":"$kind"},"geometry":{"type":"Point","coordinates":[${p.lon},${p.lat}]}}"""
    val features = listOfNotNull(f("start", pts.first()).takeIf { !guiding }, f("end", pts.last()))
    return """{"type":"FeatureCollection","features":[${features.joinToString(",")}]}"""
}

internal fun updateRouteEnds(style: Style, routes: List<MapPayload.PolylineData>, guiding: Boolean) {
    style.getSourceAs<GeoJsonSource>(ROUTE_ENDS_SOURCE_ID)?.setGeoJson(routeEndsGeoJson(routes, guiding))
}
