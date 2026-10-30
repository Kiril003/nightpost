package local.phantom.companion.feature.stream.ui.map

import android.content.Context
import local.phantom.companion.core.data.places.SavedPlaceCategory
import local.phantom.companion.core.data.poi.PoiEntity
import local.phantom.companion.feature.stream.ui.map.icons.PhantomPin
import local.phantom.companion.feature.stream.ui.map.icons.savedPlaceGlyph
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * Мітки оператора — його власна памʼять на мапі.
 *
 * Золоте кільце незмінне: воно і є «це моє». Категорію несе гліф, бо одна
 * зірка на дім, схрон і кавʼярню коштує секунди в стресі — а укриття, що
 * виглядає як кавʼярня, коштує більше.
 */
object SavedPlacesOverlay {
    const val SAVED_POI_SOURCE_ID = "phantom-saved-poi-source"
    const val SAVED_POI_LAYER_ID = "phantom-saved-poi-layer"
    const val SAVED_POI_LABEL_LAYER_ID = "phantom-saved-poi-label-layer"

    /** Золото — це те, чим збережене місце відрізняється від усіх інших. */
    private const val SAVED_RING = 0xFFFFD700.toInt()

    /**
     * Один шар, а не шар на категорію: тап по мітці шукає ознаки саме в
     * [SAVED_POI_LAYER_ID], тож мітка з другого шару стала б невидимою для
     * пальця, лишаючись видимою для ока.
     */
    private val CATEGORIES: List<String> = SavedPlaceCategory.ALL

    private fun imageId(category: String) = "saved-poi-pin-$category"

    /**
     * Нижче цього зуму мітки не малюються: на оглядовому зумі вони не ведуть
     * нікуди, а лише перекривають землю. Кластеризації тут нема свідомо —
     * брама імені тримає їх кількість малою, а кластер це костиль під сміття.
     */
    private const val MIN_MARK_ZOOM = 10f

    /**
     * Стилі, у які зірку вже намальовано. Слабкі ключі: стиль має право згинути,
     * а знімати з нього прапорець нікому. Питати `style.getImage` замість
     * прапорця — це копія нативного бітмапа назад у Java на кожне завантаження
     * стилю, на головному потоці, лише щоб спитати «чи вона вже там».
     */
    private val pinnedStyles: MutableSet<Style> =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap())

    fun install(style: Style, context: Context, isDark: Boolean = true) {
        val palette = MapSymbolPalette.resolve(isDark)
        if (pinnedStyles.add(style)) {
            val density = context.resources.displayMetrics.density
            CATEGORIES.forEach { category ->
                style.addImage(
                    imageId(category),
                    // Гліф — чорнилом, не золотом: золото тримає 1.4:1 на
                    // теплій плашці, тож знак у ньому був би здогадкою. Кільце
                    // лишається золотим — воно і є «це моє».
                    PhantomPin.bitmap(
                        savedPlaceGlyph(category),
                        ring = SAVED_RING,
                        ink = MapSymbolPalette.DARK.plateInk,
                        density = density,
                    ),
                )
            }
        }

        if (style.getSource(SAVED_POI_SOURCE_ID) == null) {
            val options = GeoJsonOptions()
            style.addSource(GeoJsonSource(SAVED_POI_SOURCE_ID, options))
        }

        if (style.getLayer(SAVED_POI_LAYER_ID) == null) {
            val layer = SymbolLayer(SAVED_POI_LAYER_ID, SAVED_POI_SOURCE_ID).withProperties(
                PropertyFactory.iconImage(pinByCategory()),
                PropertyFactory.iconSize(1.0f),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(false),
                PropertyFactory.iconPadding(MapSymbolSpace.ICON_PADDING_PX),
                PropertyFactory.iconAnchor(org.maplibre.android.style.layers.Property.ICON_ANCHOR_CENTER),
                PropertyFactory.iconRotationAlignment(org.maplibre.android.style.layers.Property.ICON_ROTATION_ALIGNMENT_VIEWPORT),
                PropertyFactory.iconPitchAlignment(org.maplibre.android.style.layers.Property.ICON_PITCH_ALIGNMENT_VIEWPORT),
                PropertyFactory.visibility(org.maplibre.android.style.layers.Property.VISIBLE),
            )
            layer.minZoom = MIN_MARK_ZOOM
            style.addLayer(layer)
        }

        if (style.getLayer(SAVED_POI_LABEL_LAYER_ID) == null) {
            val layer = SymbolLayer(SAVED_POI_LABEL_LAYER_ID, SAVED_POI_SOURCE_ID).withProperties(
                PropertyFactory.textField(Expression.get("name")),
                PropertyFactory.textFont(MAP_TEXT_FONT),
                PropertyFactory.textSize(10f),
                PropertyFactory.textColor(palette.textInk),
                PropertyFactory.textHaloColor(palette.textHalo),
                PropertyFactory.textHaloWidth(1f),
                // Та сама граматика поля, що в POIOverlay: підпис має право
                // зʼїхати з-під піна, і тікає він переважно ліворуч — праворуч
                // стоїть непрозора дека, а движок про неї не знає.
                // Підлога на випадок, якщо движок відкине змінний якір мовчки.
                PropertyFactory.textOffset(arrayOf(0f, MapSymbolSpace.LABEL_RADIAL_OFFSET)),
                PropertyFactory.textAnchor(org.maplibre.android.style.layers.Property.TEXT_ANCHOR_TOP),
                PropertyFactory.textVariableAnchor(MapSymbolSpace.LABEL_ANCHORS),
                PropertyFactory.textRadialOffset(MapSymbolSpace.LABEL_RADIAL_OFFSET),
                PropertyFactory.textJustify(org.maplibre.android.style.layers.Property.TEXT_JUSTIFY_AUTO),
                PropertyFactory.textPadding(MapSymbolSpace.LABEL_PADDING_PX),
                PropertyFactory.textAllowOverlap(false),
                PropertyFactory.textOptional(true),
            )
            layer.minZoom = 13f
            style.addLayer(layer)
        }
    }

    private fun pinByCategory(): Expression {
        val stops = ArrayList<Expression>()
        stops.add(Expression.get("category"))
        CATEGORIES.forEach { category ->
            stops.add(Expression.literal(category))
            stops.add(Expression.literal(imageId(category)))
        }
        stops.add(Expression.literal(imageId(SavedPlaceCategory.CUSTOM)))
        return Expression.match(*stops.toTypedArray())
    }

    fun update(style: Style, pois: List<PoiEntity>, visible: Boolean) {
        val source = style.getSourceAs<GeoJsonSource>(SAVED_POI_SOURCE_ID) ?: return
        val layer = style.getLayer(SAVED_POI_LAYER_ID) ?: return

        val visibility = if (visible) org.maplibre.android.style.layers.Property.VISIBLE else org.maplibre.android.style.layers.Property.NONE
        layer.setProperties(PropertyFactory.visibility(visibility))
        style.getLayer(SAVED_POI_LABEL_LAYER_ID)?.setProperties(PropertyFactory.visibility(visibility))

        if (!visible) {
            source.setGeoJson("""{"type":"FeatureCollection","features":[]}""")
            return
        }

        val featureArray = org.json.JSONArray()
        pois.forEach { poi ->
            val properties = org.json.JSONObject()
                .put("id", poi.id)
                .put("name", poi.name)
                .put("category", SavedPlaceCategory.canonical(poi.category))
                .put("subcategory", poi.subcategory ?: "")
            val geometry = org.json.JSONObject()
                .put("type", "Point")
                .put("coordinates", org.json.JSONArray().put(poi.lon).put(poi.lat))
            val feature = org.json.JSONObject()
                .put("type", "Feature")
                .put("properties", properties)
                .put("geometry", geometry)
            featureArray.put(feature)
        }
        val collection = org.json.JSONObject()
            .put("type", "FeatureCollection")
            .put("features", featureArray)
        source.setGeoJson(collection.toString())
    }
}
