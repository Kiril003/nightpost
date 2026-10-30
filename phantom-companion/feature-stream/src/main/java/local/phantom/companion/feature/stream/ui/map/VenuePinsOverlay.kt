package local.phantom.companion.feature.stream.ui.map

import android.content.Context
import local.phantom.companion.feature.stream.ui.map.icons.PhantomGlyph
import local.phantom.companion.feature.stream.ui.map.icons.PhantomPin
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource

/** Місця Nightpost (сторінки з Builder) на мапі: чорнильний пін з назвою; дотик — картка місця. */
object VenuePinsOverlay {
    const val SOURCE_ID = "phantom-venue-pins-source"
    const val LAYER_ID = "phantom-venue-pins-layer"
    const val LABEL_LAYER_ID = "phantom-venue-pins-label-layer"
    private const val IMAGE_ID = "venue-pin"
    private const val MIN_ZOOM = 11f

    private val pinned: MutableSet<Style> = java.util.Collections.newSetFromMap(java.util.WeakHashMap())

    fun install(style: Style, context: Context, isDark: Boolean = true) {
        val palette = MapSymbolPalette.resolve(isDark)
        if (pinned.add(style)) {
            val density = context.resources.displayMetrics.density
            style.addImage(IMAGE_ID, PhantomPin.bitmap(PhantomGlyph.PLACE, ring = palette.plateInk, ink = palette.plateInk, density = density))
        }
        if (style.getSource(SOURCE_ID) == null) style.addSource(GeoJsonSource(SOURCE_ID, EMPTY_FC))
        if (style.getLayer(LAYER_ID) == null) {
            val layer = SymbolLayer(LAYER_ID, SOURCE_ID).withProperties(
                PropertyFactory.iconImage(IMAGE_ID),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(false),
                PropertyFactory.iconPadding(MapSymbolSpace.ICON_PADDING_PX),
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_CENTER),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_VIEWPORT),
                PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT),
            )
            layer.minZoom = MIN_ZOOM
            style.addLayer(layer)
        }
        if (style.getLayer(LABEL_LAYER_ID) == null) {
            val label = SymbolLayer(LABEL_LAYER_ID, SOURCE_ID).withProperties(
                PropertyFactory.textField(Expression.get("name")),
                PropertyFactory.textFont(MAP_TEXT_FONT),
                PropertyFactory.textSize(11f),
                PropertyFactory.textColor(palette.textInk),
                PropertyFactory.textHaloColor(palette.textHalo),
                PropertyFactory.textHaloWidth(1f),
                PropertyFactory.textVariableAnchor(MapSymbolSpace.LABEL_ANCHORS),
                PropertyFactory.textRadialOffset(MapSymbolSpace.LABEL_RADIAL_OFFSET),
                PropertyFactory.textJustify(Property.TEXT_JUSTIFY_AUTO),
                PropertyFactory.textPadding(MapSymbolSpace.LABEL_PADDING_PX),
                PropertyFactory.textAllowOverlap(false),
                PropertyFactory.textOptional(true),
            )
            label.minZoom = 13f
            style.addLayer(label)
        }
    }

    fun update(style: Style, geoJson: String, visible: Boolean) {
        val source = style.getSourceAs<GeoJsonSource>(SOURCE_ID) ?: return
        val v = if (visible) Property.VISIBLE else Property.NONE
        style.getLayer(LAYER_ID)?.setProperties(PropertyFactory.visibility(v))
        style.getLayer(LABEL_LAYER_ID)?.setProperties(PropertyFactory.visibility(v))
        source.setGeoJson(if (visible) geoJson else EMPTY_FC)
    }
}
