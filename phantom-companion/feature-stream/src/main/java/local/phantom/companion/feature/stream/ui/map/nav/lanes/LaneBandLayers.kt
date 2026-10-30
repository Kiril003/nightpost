package local.phantom.companion.feature.stream.ui.map.nav.lanes

import local.phantom.companion.feature.stream.ui.map.EMPTY_FC
import local.phantom.companion.feature.stream.ui.map.OPERATOR_ARROW_LAYER
import local.phantom.companion.feature.stream.ui.map.ROUTE_CORE_COLOR
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * PRESENCE wave 1 — the band on the glass.
 *
 * Three layers over one source: the lanes, the lanes that are actually VALID
 * for the maneuver ahead, and the hairline paint between them. In-engine, in
 * the same frame as the ground (D1) — the lesson of Crystal City was that a
 * scene glued on top as a second surface delaminates from the map under it.
 *
 * The band is drawn UNDER the route ribbon's casing: the ribbon is the answer,
 * the band is the asphalt the answer is written on.
 */

internal const val LANE_BAND_SOURCE = "phantom-lane-band"
internal const val LANE_BAND_LAYER = "phantom-lane-band-lanes"
internal const val LANE_BAND_VALID_LAYER = "phantom-lane-band-valid"
internal const val LANE_BAND_SEP_LAYER = "phantom-lane-band-sep"

/** Below this a 3.5 m lane is under two pixels — paint, not information. */
private const val LANE_MIN_ZOOM = 16f

/** ph.color.ink-faint — the lane nobody may take, and the paint between all of them. */
private fun laneNeutral(isDark: Boolean) = if (isDark) 0xFF6B6860.toInt() else 0xFF9A958B.toInt()

private const val LANE_NEUTRAL_ALPHA = 0.30f
private const val LANE_VALID_ALPHA = 0.55f
private const val LANE_SEP_ALPHA = 0.45f

/**
 * Lane width in style units, tracking 3.5 m of real ground.
 *
 * Base 2 is not a taste: one zoom level halves the metres a pixel covers, so a
 * doubling ramp holds a constant width on the earth. Stops at the band's own
 * ends only — everything between is the exponent doing the work. zoom() stays
 * the top-level input (SymbolExpressionFirewallTest's law).
 */
private fun laneWidthExpression(): Expression = Expression.interpolate(
    Expression.exponential(2f),
    Expression.zoom(),
    Expression.stop(16f, 2.3f),
    Expression.stop(20f, 36.8f),
)

/**
 * The paint between lanes stays a HAIRLINE instead of tracking its real 12 cm:
 * a metric separator is a third of a pixel at the zooms the band lives at, and
 * a line nobody can see does not separate anything.
 */
private fun separatorWidthExpression(): Expression = Expression.interpolate(
    Expression.linear(),
    Expression.zoom(),
    Expression.stop(16f, 0.6f),
    Expression.stop(20f, 1.6f),
)

internal fun installLaneBand(style: Style, isDark: Boolean) {
    if (style.getSource(LANE_BAND_SOURCE) == null) {
        style.addSource(GeoJsonSource(LANE_BAND_SOURCE, EMPTY_FC))
    }
    val neutral = laneNeutral(isDark)
    // Three passes, bottom-up: every lane as asphalt, the usable ones lit over
    // them, the paint last. Одна пара кольорів на шар замість вибору за даними
    // всередині одного — так яскравість валідної смуги не залежить від того,
    // чи стилю сподобався булевий вираз.
    if (style.getLayer(LANE_BAND_LAYER) == null) {
        addBelowArrow(
            style,
            LineLayer(LANE_BAND_LAYER, LANE_BAND_SOURCE).withProperties(
                PropertyFactory.lineColor(neutral),
                PropertyFactory.lineWidth(laneWidthExpression()),
                PropertyFactory.lineOpacity(LANE_NEUTRAL_ALPHA),
                PropertyFactory.lineCap(Property.LINE_CAP_BUTT),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ).also {
                it.minZoom = LANE_MIN_ZOOM
                it.setFilter(Expression.eq(Expression.get("kind"), Expression.literal("lane")))
            },
        )
    }
    if (style.getLayer(LANE_BAND_VALID_LAYER) == null) {
        addBelowArrow(
            style,
            LineLayer(LANE_BAND_VALID_LAYER, LANE_BAND_SOURCE).withProperties(
                // The route's own token: «ця смуга веде туди, куди веде лінія».
                PropertyFactory.lineColor(ROUTE_CORE_COLOR),
                PropertyFactory.lineWidth(laneWidthExpression()),
                PropertyFactory.lineOpacity(LANE_VALID_ALPHA),
                PropertyFactory.lineCap(Property.LINE_CAP_BUTT),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ).also {
                it.minZoom = LANE_MIN_ZOOM
                it.setFilter(
                    Expression.all(
                        Expression.eq(Expression.get("kind"), Expression.literal("lane")),
                        Expression.eq(Expression.get("valid"), Expression.literal(true)),
                    ),
                )
            },
        )
    }
    if (style.getLayer(LANE_BAND_SEP_LAYER) == null) {
        addBelowArrow(
            style,
            LineLayer(LANE_BAND_SEP_LAYER, LANE_BAND_SOURCE).withProperties(
                PropertyFactory.lineColor(neutral),
                PropertyFactory.lineWidth(separatorWidthExpression()),
                PropertyFactory.lineOpacity(LANE_SEP_ALPHA),
                PropertyFactory.lineDasharray(arrayOf(3f, 3f)),
            ).also {
                it.minZoom = LANE_MIN_ZOOM
                it.setFilter(Expression.eq(Expression.get("kind"), Expression.literal("sep")))
            },
        )
    }
}

/** [geoJson] null = no band on this stretch, and that is a full answer. */
internal fun updateLaneBand(style: Style, geoJson: String?) {
    style.getSourceAs<GeoJsonSource>(LANE_BAND_SOURCE)?.setGeoJson(geoJson ?: EMPTY_FC)
}

/** Same anchor the route ribbon uses, so the band lands under its casing. */
private fun addBelowArrow(style: Style, layer: LineLayer) {
    if (style.getLayer(OPERATOR_ARROW_LAYER) != null) {
        style.addLayerBelow(layer, OPERATOR_ARROW_LAYER)
    } else {
        style.addLayer(layer)
    }
}
