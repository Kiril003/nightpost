package local.phantom.companion.feature.stream.ui.map

import local.phantom.companion.core.data.transit.AccessMode
import local.phantom.companion.core.data.transit.Itinerary
import local.phantom.companion.core.data.transit.raptor.TransitMode
import local.phantom.companion.feature.stream.R
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * TRANSIT GEOMETRY — the multi-colour transit ribbon.
 *
 * One selected [Itinerary] becomes three visual voices on the map:
 *  - rides as solid lines in the LINE's OWN colour (data-driven from the
 *    feed — a tram is not a bus is not the metro), over a dark casing;
 *  - walking (and transfer tunnels) as a dotted thread — visibly "on foot";
 *  - board / transfer / alight stops as tappable nodes (white core, line-
 *    coloured ring; transfers ride larger). Taps land in MapCanvas and open
 *    the same place-card as phantom-layer markers.
 *
 * The generic blue route layer stands down while an itinerary is drawn —
 * two truths about one journey would fight, and the ribbon is the richer.
 */

internal const val TRANSIT_LEGS_SOURCE = "phantom-transit-legs-source"
internal const val TRANSIT_LEGS_CASING = "phantom-transit-legs-casing"
internal const val TRANSIT_LEGS_RIDE = "phantom-transit-legs-ride"
internal const val TRANSIT_LEGS_WALK = "phantom-transit-legs-walk"
internal const val TRANSIT_STOPS_SOURCE = "phantom-transit-stops-source"
internal const val TRANSIT_STOPS_LAYER = "phantom-transit-stops-layer"

internal fun installTransitLayers(style: Style, isDark: Boolean) {
    val palette = MapSymbolPalette.resolve(isDark)
    if (style.getSource(TRANSIT_LEGS_SOURCE) == null) {
        style.addSource(GeoJsonSource(TRANSIT_LEGS_SOURCE, EMPTY_FC))
    }
    if (style.getSource(TRANSIT_STOPS_SOURCE) == null) {
        style.addSource(GeoJsonSource(TRANSIT_STOPS_SOURCE, EMPTY_FC))
    }

    val rideFilter = Expression.eq(Expression.get("kind"), Expression.literal("ride"))
    val walkFilter = Expression.eq(Expression.get("kind"), Expression.literal("walk"))

    if (style.getLayer(TRANSIT_LEGS_CASING) == null) {
        val casing = LineLayer(TRANSIT_LEGS_CASING, TRANSIT_LEGS_SOURCE)
        casing.setFilter(rideFilter)
        casing.setProperties(
            PropertyFactory.lineColor("#0B1220"),
            PropertyFactory.lineWidth(9f),
            PropertyFactory.lineOpacity(0.55f),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        )
        addAboveRouteOrTop(style, casing)
    }
    if (style.getLayer(TRANSIT_LEGS_RIDE) == null) {
        val ride = LineLayer(TRANSIT_LEGS_RIDE, TRANSIT_LEGS_SOURCE)
        ride.setFilter(rideFilter)
        ride.setProperties(
            PropertyFactory.lineColor(Expression.toColor(Expression.get("colour"))),
            PropertyFactory.lineWidth(5.5f),
            PropertyFactory.lineOpacity(0.95f),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        )
        style.addLayerAbove(ride, TRANSIT_LEGS_CASING)
    }
    if (style.getLayer(TRANSIT_LEGS_WALK) == null) {
        val walk = LineLayer(TRANSIT_LEGS_WALK, TRANSIT_LEGS_SOURCE)
        walk.setFilter(walkFilter)
        walk.setProperties(
            // The leg the operator walks. #94A3B8 was 2.34:1 on the light
            // basemap — the one part of the journey done on foot was the one
            // part that disappeared in daylight.
            PropertyFactory.lineColor(palette.transitWalk),
            PropertyFactory.lineWidth(4f),
            PropertyFactory.lineOpacity(0.9f),
            // Round caps + tiny dash = a dotted thread, unmistakably on foot.
            PropertyFactory.lineDasharray(arrayOf(0.1f, 2f)),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        )
        style.addLayerAbove(walk, TRANSIT_LEGS_RIDE)
    }
    if (style.getLayer(TRANSIT_STOPS_LAYER) == null) {
        val stops = CircleLayer(TRANSIT_STOPS_LAYER, TRANSIT_STOPS_SOURCE)
        stops.setProperties(
            PropertyFactory.circleColor("#FFFFFF"),
            PropertyFactory.circleStrokeColor(Expression.toColor(Expression.get("colour"))),
            PropertyFactory.circleStrokeWidth(2.5f),
            PropertyFactory.circleRadius(
                Expression.switchCase(
                    Expression.eq(Expression.get("node"), Expression.literal("transfer")),
                    Expression.literal(7f),
                    Expression.literal(4.5f),
                ),
            ),
        )
        style.addLayerAbove(stops, TRANSIT_LEGS_WALK)
    }
}

private fun addAboveRouteOrTop(style: Style, layer: LineLayer) {
    when {
        style.getLayer(ROUTE_LAYER_ID) != null -> style.addLayerAbove(layer, ROUTE_LAYER_ID)
        style.getLayer(OPERATOR_ARROW_LAYER) != null -> style.addLayerBelow(layer, OPERATOR_ARROW_LAYER)
        else -> style.addLayer(layer)
    }
}

internal fun updateTransitLayers(style: Style, itinerary: Itinerary?, isDark: Boolean) {
    installTransitLayers(style, isDark)
    val legsSource = style.getSourceAs<GeoJsonSource>(TRANSIT_LEGS_SOURCE) ?: return
    val stopsSource = style.getSourceAs<GeoJsonSource>(TRANSIT_STOPS_SOURCE) ?: return
    if (itinerary == null) {
        legsSource.setGeoJson(EMPTY_FC)
        stopsSource.setGeoJson(EMPTY_FC)
        return
    }
    legsSource.setGeoJson(buildTransitLegsGeoJson(itinerary))
    stopsSource.setGeoJson(buildTransitStopsGeoJson(itinerary))
}

/** Feed colour, or the mode's own voice when the feed declares none. */
internal fun transitLegHex(colour: Int, mode: TransitMode): String =
    if (colour >= 0) String.format("#%06X", colour and 0xFFFFFF)
    else when (mode) {
        TransitMode.METRO -> "#7B1FA2"
        TransitMode.TRAM -> "#C62828"
        TransitMode.TROLLEYBUS -> "#2E7D32"
        TransitMode.BUS -> "#1565C0"
        TransitMode.RAIL -> "#5D4037"
        TransitMode.FUNICULAR -> "#F57F17"
        TransitMode.FERRY -> "#0277BD"
        TransitMode.OTHER -> "#546E7A"
    }

private const val DRIVE_HEX = "#546E7A"

internal fun buildTransitLegsGeoJson(itin: Itinerary): String {
    val features = ArrayList<String>(itin.legs.size)
    var lastEnd: Pair<Double, Double>? = null

    fun lineFeature(points: List<Pair<Double, Double>>, kind: String, colour: String) {
        if (points.size < 2) return
        val coords = points.joinToString(",") { (lat, lon) -> "[$lon,$lat]" }
        features += "{\"type\":\"Feature\"," +
            "\"properties\":{\"kind\":\"$kind\",\"colour\":\"$colour\"}," +
            "\"geometry\":{\"type\":\"LineString\",\"coordinates\":[$coords]}}"
    }

    for ((i, leg) in itin.legs.withIndex()) {
        when (leg) {
            is Itinerary.Leg.Access -> {
                val pts = leg.points.ifEmpty {
                    listOf(leg.fromLat to leg.fromLon, leg.toLat to leg.toLon)
                }
                if (leg.mode == AccessMode.DRIVE) lineFeature(pts, "ride", DRIVE_HEX)
                else lineFeature(pts, "walk", "")
                lastEnd = pts.last()
            }
            is Itinerary.Leg.Ride -> {
                val pts = leg.points.ifEmpty {
                    listOf(leg.fromLat to leg.fromLon, leg.toLat to leg.toLon)
                }
                lineFeature(pts, "ride", transitLegHex(leg.colour, leg.mode))
                lastEnd = pts.last()
            }
            is Itinerary.Leg.Transfer -> {
                // A transfer's geometry is the gap it bridges: previous leg's
                // end to the next ride's boarding stop, dotted like any walk.
                val from = lastEnd
                val next = itin.legs.drop(i + 1).firstOrNull { it is Itinerary.Leg.Ride }
                    as? Itinerary.Leg.Ride
                if (from != null && next != null) {
                    lineFeature(listOf(from, next.fromLat to next.fromLon), "walk", "")
                }
            }
        }
    }
    return "{\"type\":\"FeatureCollection\",\"features\":[${features.joinToString(",")}]}"
}

internal fun buildTransitStopsGeoJson(itin: Itinerary): String {
    val rides = itin.legs.filterIsInstance<Itinerary.Leg.Ride>()
    val features = ArrayList<String>(rides.size * 2)

    fun node(lat: Double, lon: Double, node: String, colour: String, label: String, detail: String) {
        features += "{\"type\":\"Feature\",\"properties\":{" +
            "\"node\":\"$node\",\"colour\":\"$colour\"," +
            "\"label\":\"${escapeJsonString(label)}\"," +
            "\"detail\":\"${escapeJsonString(detail)}\"}," +
            "\"geometry\":{\"type\":\"Point\",\"coordinates\":[$lon,$lat]}}"
    }

    for ((i, ride) in rides.withIndex()) {
        val hex = transitLegHex(ride.colour, ride.mode)
        val verb = modeWord(ride.mode)
        if (i == 0) {
            node(
                ride.fromLat, ride.fromLon, "board", hex, ride.fromStopName,
                MapText.s(R.string.mapl_e_board_stop, verb, ride.routeLabel),
            )
        }
        val next = rides.getOrNull(i + 1)
        if (next != null) {
            node(
                ride.toLat, ride.toLon, "transfer", hex, ride.toStopName,
                MapText.s(R.string.mapl_e_transfer_stop, ride.routeLabel, modeWord(next.mode), next.routeLabel),
            )
            if (next.fromStopName != ride.toStopName ||
                next.fromLat != ride.toLat || next.fromLon != ride.toLon
            ) {
                node(
                    next.fromLat, next.fromLon, "transfer", transitLegHex(next.colour, next.mode),
                    next.fromStopName, MapText.s(R.string.mapl_e_board_stop, modeWord(next.mode), next.routeLabel),
                )
            }
        } else {
            node(ride.toLat, ride.toLon, "alight", hex, ride.toStopName, MapText.s(R.string.mapl_e_alight_stop))
        }
    }
    return "{\"type\":\"FeatureCollection\",\"features\":[${features.joinToString(",")}]}"
}

private fun modeWord(mode: TransitMode): String = when (mode) {
    TransitMode.METRO -> MapText.s(R.string.mapl_e_mode_metro)
    TransitMode.TRAM -> MapText.s(R.string.mapl_e_mode_tram)
    TransitMode.TROLLEYBUS -> MapText.s(R.string.mapl_e_mode_trolleybus)
    TransitMode.BUS -> MapText.s(R.string.mapl_e_mode_bus)
    TransitMode.RAIL -> MapText.s(R.string.mapl_e_mode_rail)
    TransitMode.FUNICULAR -> MapText.s(R.string.mapl_e_mode_funicular)
    TransitMode.FERRY -> MapText.s(R.string.mapl_e_mode_ferry)
    TransitMode.OTHER -> MapText.s(R.string.mapl_e_mode_other)
}
