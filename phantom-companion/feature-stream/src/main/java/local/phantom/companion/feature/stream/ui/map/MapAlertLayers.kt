package local.phantom.companion.feature.stream.ui.map

import android.content.Context
import android.graphics.Bitmap
import local.phantom.companion.feature.stream.ui.map.icons.PhantomPin
import local.phantom.companion.feature.stream.ui.map.icons.glyph
import local.phantom.companion.feature.stream.R
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

// СИРЕНА / 500-line rule — the air-raid alert overlay engine, split out of
// MapOverlayLayers.kt when threat-typed paint + centroid badges outgrew it.
// Same package, same `internal` surface.

internal const val ALERT_SOURCE = "phantom-alert-oblasts-source"
internal const val ALERT_FILL_LAYER = "phantom-alert-oblasts-fill"
internal const val ALERT_LINE_HALO_LAYER = "phantom-alert-oblasts-line-halo"
internal const val ALERT_LINE_LAYER = "phantom-alert-oblasts-line"
internal const val ALERT_LABEL_LAYER = "phantom-alert-oblasts-label"
internal const val ALERT_BADGE_SOURCE = "phantom-alert-badges-source"
internal const val ALERT_BADGE_LAYER = "phantom-alert-badges-layer"
private const val ALERT_BADGE_IMG_PREFIX = "phantom-alert-badge-"

/**
 * Стилі, у які значки загроз уже намальовано. Слабкі ключі: стиль живе своїм
 * життям і має право згинути, а знімати з нього прапорець нікому.
 *
 * Питати `style.getImage(name)` замість прапорця дорого не там, де здається:
 * ця ручка тягне картинку з нативного шару назад у Java-Bitmap — десять копій
 * на кожне завантаження стилю, тобто на кожній зміні підложки й теми, на
 * головному потоці, лише щоб спитати «чи вона вже там».
 */
private val badgedStyles: MutableSet<Style> =
    java.util.Collections.newSetFromMap(java.util.WeakHashMap())

/**
 * ATLAS QA redesign — install the oblast-boundary source + the alert
 * subsystem's four layers (fill wash, border halo, dashed border core,
 * «ТРИВОГА» label), all born [Property.NONE].
 *
 * The visibility discipline is the TOUCH FIX, not a style choice: the old
 * overlay parked country-wide polygons at opacity 0 — still "rendered" as
 * far as `queryRenderedFeatures` is concerned — so every tap anywhere in
 * Ukraine hit an invisible oblast named like a POI and hijacked the dossier
 * flow. A layer that has nothing to say is NONE, never transparent.
 */
internal fun installAlertOverlay(style: Style, oblastGeoJson: String, context: Context, isDark: Boolean) {
    val palette = MapSymbolPalette.resolve(isDark)
    if (style.getSource(ALERT_SOURCE) == null) {
        style.addSource(GeoJsonSource(ALERT_SOURCE, oblastGeoJson))
    }
    val hidden = PropertyFactory.visibility(org.maplibre.android.style.layers.Property.NONE)
    if (style.getLayer(ALERT_FILL_LAYER) == null) {
        style.addLayer(
            org.maplibre.android.style.layers.FillLayer(ALERT_FILL_LAYER, ALERT_SOURCE)
                .withProperties(
                    PropertyFactory.fillColor(android.graphics.Color.parseColor("#EF4444")),
                    // Strong at oblast scale (situational awareness), receding
                    // at street scale so the alert never buries the streets
                    // the operator is navigating through it.
                    PropertyFactory.fillOpacity(
                        Expression.interpolate(
                            Expression.linear(),
                            Expression.zoom(),
                            Expression.stop(5, 0.32f),
                            Expression.stop(10, 0.20f),
                            Expression.stop(13, 0.10f),
                            Expression.stop(15.5, 0.05f),
                        ),
                    ),
                    hidden,
                ),
        )
    }
    if (style.getLayer(ALERT_LINE_HALO_LAYER) == null) {
        style.addLayer(
            org.maplibre.android.style.layers.LineLayer(ALERT_LINE_HALO_LAYER, ALERT_SOURCE)
                .withProperties(
                    PropertyFactory.lineColor(android.graphics.Color.parseColor("#EF4444")),
                    PropertyFactory.lineWidth(7f),
                    PropertyFactory.lineOpacity(0.22f),
                    PropertyFactory.lineBlur(3f),
                    hidden,
                ),
        )
    }
    if (style.getLayer(ALERT_LINE_LAYER) == null) {
        style.addLayer(
            org.maplibre.android.style.layers.LineLayer(ALERT_LINE_LAYER, ALERT_SOURCE)
                .withProperties(
                    // The oblast's own boundary while it is under alert — the
                    // one hard edge of the wash. #F87171 measured 2.53:1 on
                    // the light basemap; the dash carries the meaning, but a
                    // dash you cannot see carries nothing.
                    PropertyFactory.lineColor(palette.alertEdge),
                    PropertyFactory.lineWidth(2f),
                    PropertyFactory.lineOpacity(0.95f),
                    PropertyFactory.lineDasharray(arrayOf(2.4f, 1.6f)),
                    hidden,
                ),
        )
    }
    if (style.getLayer(ALERT_LABEL_LAYER) == null) {
        val label = org.maplibre.android.style.layers.SymbolLayer(ALERT_LABEL_LAYER, ALERT_SOURCE)
            .withProperties(
                PropertyFactory.textField(
                    Expression.concat(
                        Expression.literal(context.getString(R.string.map_alert_prefix) + " · "),
                        Expression.get("name"),
                    ),
                ),
                PropertyFactory.textFont(MAP_TEXT_FONT),
                PropertyFactory.textSize(
                    Expression.interpolate(
                        Expression.linear(),
                        Expression.zoom(),
                        Expression.stop(5, 11f),
                        Expression.stop(9, 15f),
                    ),
                ),
                PropertyFactory.textColor(android.graphics.Color.parseColor("#FCA5A5")),
                PropertyFactory.textHaloColor(android.graphics.Color.parseColor("#450A0A")),
                PropertyFactory.textHaloWidth(1.6f),
                PropertyFactory.textAllowOverlap(false),
                PropertyFactory.textOptional(true),
                hidden,
            )
        // The banner belongs to the oblast overview; at street zoom the wash
        // and border carry the message without shouting over labels.
        label.maxZoom = 11f
        style.addLayer(label)
    }

    // СИРЕНА — the threat badges: one glyph disc per alerting oblast, pinned
    // at its centroid, telling WHAT is in the sky (дрон, ракета, балістика…)
    // at the country scale where the fill wash only says «something». Один
    // бітмап на тип, намальований власною родиною знаків.
    if (badgedStyles.add(style)) {
        val density = context.resources.displayMetrics.density
        ThreatType.entries.forEach { type ->
            style.addImage(ALERT_BADGE_IMG_PREFIX + type.name, makeThreatBadge(type, density))
        }
    }
    if (style.getSource(ALERT_BADGE_SOURCE) == null) {
        style.addSource(GeoJsonSource(ALERT_BADGE_SOURCE, EMPTY_FC))
    }
    if (style.getLayer(ALERT_BADGE_LAYER) == null) {
        val badges = org.maplibre.android.style.layers.SymbolLayer(ALERT_BADGE_LAYER, ALERT_BADGE_SOURCE)
            .withProperties(
                PropertyFactory.iconImage(Expression.get("badge")),
                PropertyFactory.iconSize(
                    Expression.interpolate(
                        Expression.linear(),
                        Expression.zoom(),
                        Expression.stop(4, 0.75f),
                        Expression.stop(7, 1.0f),
                    ),
                ),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconAnchor(org.maplibre.android.style.layers.Property.ICON_ANCHOR_CENTER),
                PropertyFactory.iconRotationAlignment(org.maplibre.android.style.layers.Property.ICON_ROTATION_ALIGNMENT_VIEWPORT),
                PropertyFactory.iconPitchAlignment(org.maplibre.android.style.layers.Property.ICON_PITCH_ALIGNMENT_VIEWPORT),
                hidden,
            )
        // Street zoom keeps only the wash + border; a country-scale badge
        // hovering over one street would claim precision the feed lacks.
        badges.maxZoom = 11f
        style.addLayer(badges)
    }
}

/**
 * The same plate and ink rim every map symbol wears, carrying the widest ring
 * on the map: 3dp of threat tint. The disc used to be obsidian, which on the
 * dark basemap made the badge a hole rather than an object. Stakes buy weight,
 * not blackness.
 *
 * Кільце носить колір загрози — той самий, що й заливка області, тож значок і
 * пляма читаються як одне. Гліф іде чорнилом: із десяти тонів тривоги більшість
 * (жовта радіація, лаймова хімія) на теплій плашці не бере й двох до одного, а
 * силует загрози — це останнє, що має право стати здогадкою.
 */
private fun makeThreatBadge(type: ThreatType, density: Float): Bitmap =
    PhantomPin.bitmap(
        type.meta.glyph,
        ring = type.meta.colorArgb.toInt(),
        ink = MapSymbolPalette.DARK.plateInk,
        density = density,
        contentDp = BADGE_CONTENT_DP,
        padDp = BADGE_PAD_DP,
        ringDp = BADGE_RING_DP,
        glyphDp = BADGE_GLYPH_DP,
    )

// Bigger than a POI pin and ringed wider: a threat badge is the one symbol
// whose stakes justify taking more of the glass. Rim comes from [MapPinFace] —
// the face is shared even where the size is not.
internal const val BADGE_CONTENT_DP = 30f
internal const val BADGE_PAD_DP = 2f
internal const val BADGE_RING_DP = 3f
internal const val BADGE_GLYPH_DP = 16f

/**
 * Repaint the alert overlay from the live snapshot: only ACTIVE oblasts
 * exist as far as the renderer — and therefore the TOUCH PIPELINE — is
 * concerned. Hidden or empty = visibility NONE on every layer: an honest
 * empty map that cannot intercept a tap.
 *
 * СИРЕНА — the paint is now threat-typed: each oblast's wash, border and
 * label carry its [ThreatType]'s color and name (ballistics ≠ drones at a
 * glance), and [centroidOf] pins a glyph badge on every alerting oblast.
 */
internal fun updateAlertOverlay(
    style: Style,
    alerts: Map<String, AlertState>,
    visible: Boolean,
    centroidOf: (String) -> Pair<Double, Double>?,
    // Resources need a Context and this function has none — the call site owns
    // one. Until it passes the resolver in, the word comes from the same table
    // the rest of СИРЕНА reads, never from a literal typed a second time here.
    threatWord: (ThreatType) -> String = { it.meta.short },
) {
    val layers = listOfNotNull(
        style.getLayer(ALERT_FILL_LAYER),
        style.getLayer(ALERT_LINE_HALO_LAYER),
        style.getLayer(ALERT_LINE_LAYER),
        style.getLayer(ALERT_LABEL_LAYER),
        style.getLayer(ALERT_BADGE_LAYER),
    )
    if (layers.isEmpty()) return
    val active = alerts.filterValues { it.active }
    if (!visible || active.isEmpty()) {
        layers.forEach {
            it.setProperties(PropertyFactory.visibility(org.maplibre.android.style.layers.Property.NONE))
        }
        return
    }

    // match(get("name"), <name> -> true, …, default false)
    val filterStops = ArrayList<Expression>()
    filterStops.add(Expression.get("name"))
    active.keys.forEach { filterStops.add(Expression.literal(it)); filterStops.add(Expression.literal(true)) }
    filterStops.add(Expression.literal(false))
    val onlyActive = Expression.match(*filterStops.toTypedArray())

    // match(get("name"), <name> -> that oblast's threat color, …)
    fun colorMatch(): Expression {
        val stops = ArrayList<Expression>()
        stops.add(Expression.get("name"))
        active.forEach { (name, st) ->
            stops.add(Expression.literal(name))
            stops.add(Expression.color(st.threatType.meta.colorArgb.toInt()))
        }
        stops.add(Expression.color(ThreatType.UNKNOWN.meta.colorArgb.toInt()))
        return Expression.match(*stops.toTypedArray())
    }

    // match(get("name"), <name> -> «БПЛА · Київська область», …)
    val labelStops = ArrayList<Expression>()
    labelStops.add(Expression.get("name"))
    val unknownWord = threatWord(ThreatType.UNKNOWN).uppercase()
    active.forEach { (name, st) ->
        val word = if (st.threatType == ThreatType.UNKNOWN) unknownWord
        else threatWord(st.threatType).uppercase()
        labelStops.add(Expression.literal(name))
        labelStops.add(Expression.literal("$word · $name"))
    }
    labelStops.add(Expression.literal(unknownWord))
    val labelText = Expression.match(*labelStops.toTypedArray())

    layers.forEach { layer ->
        when (layer) {
            is org.maplibre.android.style.layers.FillLayer -> {
                layer.setFilter(onlyActive)
                layer.setProperties(PropertyFactory.fillColor(colorMatch()))
            }
            is org.maplibre.android.style.layers.LineLayer -> {
                layer.setFilter(onlyActive)
                layer.setProperties(PropertyFactory.lineColor(colorMatch()))
            }
            is org.maplibre.android.style.layers.SymbolLayer ->
                if (layer.id == ALERT_LABEL_LAYER) {
                    layer.setFilter(onlyActive)
                    // textFont on every textField write: the style default
                    // stack 404s its glyphs on OpenFreeMap and the label
                    // silently never renders.
                    layer.setProperties(
                        PropertyFactory.textField(labelText),
                        PropertyFactory.textFont(MAP_TEXT_FONT),
                    )
                }
        }
        layer.setProperties(PropertyFactory.visibility(org.maplibre.android.style.layers.Property.VISIBLE))
    }

    // The badge points: oblast centroid + which bitmap to draw there.
    val features = active.entries.mapNotNull { (name, st) ->
        val c = centroidOf(name) ?: return@mapNotNull null
        "{\"type\":\"Feature\",\"properties\":{" +
            "\"badge\":\"$ALERT_BADGE_IMG_PREFIX${st.threatType.name}\"," +
            "\"name\":\"${escapeJsonString(name)}\"}," +
            "\"geometry\":{\"type\":\"Point\",\"coordinates\":[${c.second},${c.first}]}}"
    }.joinToString(",")
    style.getSourceAs<GeoJsonSource>(ALERT_BADGE_SOURCE)
        ?.setGeoJson("{\"type\":\"FeatureCollection\",\"features\":[$features]}")
}
