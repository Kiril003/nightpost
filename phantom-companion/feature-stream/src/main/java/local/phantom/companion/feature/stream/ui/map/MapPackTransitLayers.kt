package local.phantom.companion.feature.stream.ui.map

import local.phantom.companion.feature.stream.ui.map.icons.GroundTokens
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * МЕРЕЖА, А НЕ МАРШРУТ — зупинки, станції й колії з дорожнього пакета.
 *
 * Лежить ПІД стрічкою транзиту ([MapTransitLayers]) навмисно: обраний
 * маршрут — це відповідь на питання, а мережа — тло, на якому питання
 * ставлять. Коли обидва на склі, відповідь мусить бути зверху.
 *
 * Ані хвилини, ані ціни тут немає й не буде: OSM їх не несе.
 */

internal const val PACK_RAILS_SOURCE = "phantom-pack-rails-source"
internal const val PACK_RAILS_LAYER = "phantom-pack-rails-layer"
internal const val PACK_RAILS_TIES = "phantom-pack-rails-ties"
internal const val PACK_STOPS_SOURCE = "phantom-pack-stops-source"
internal const val PACK_STOPS_LAYER = "phantom-pack-stops-layer"
internal const val PACK_STOPS_LABEL = "phantom-pack-stops-label"

internal fun installPackTransitLayers(style: Style, isDark: Boolean) {
    if (style.getSource(PACK_RAILS_SOURCE) == null) {
        style.addSource(GeoJsonSource(PACK_RAILS_SOURCE, EMPTY_FC))
    }
    if (style.getSource(PACK_STOPS_SOURCE) == null) {
        style.addSource(GeoJsonSource(PACK_STOPS_SOURCE, EMPTY_FC))
    }

    val railHex = if (isDark) "#8D9AA8" else "#4A5560"
    val tieHex = if (isDark) "#C8D2DC" else "#7C8894"
    val haloHex = if (isDark) "#0B1220" else "#FFFFFF"
    val mainOnly = Expression.eq(Expression.get("main"), Expression.literal(true))

    // Колія читається двома штрихами: суцільним тілом і рідкими шпалами
    // поверх нього. Один штрих виглядав би дорогою, а на мапі, де людину
    // ведуть маршрутом, сплутати колію з проїздом небезпечно.
    if (style.getLayer(PACK_RAILS_LAYER) == null) {
        val body = LineLayer(PACK_RAILS_LAYER, PACK_RAILS_SOURCE)
        body.setProperties(
            PropertyFactory.lineColor(railHex),
            PropertyFactory.lineWidth(
                Expression.switchCase(mainOnly, Expression.literal(1.8f), Expression.literal(1.0f)),
            ),
            PropertyFactory.lineOpacity(
                Expression.switchCase(mainOnly, Expression.literal(0.85f), Expression.literal(0.5f)),
            ),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        )
        addPackLayer(style, body)
    }
    if (style.getLayer(PACK_RAILS_TIES) == null) {
        val ties = LineLayer(PACK_RAILS_TIES, PACK_RAILS_SOURCE)
        // Шпали лише на магістралях: на підʼїзних коліях депо вони
        // перетворюють двір станції на суцільну штриховку.
        ties.setFilter(mainOnly)
        ties.setProperties(
            PropertyFactory.lineColor(tieHex),
            PropertyFactory.lineWidth(1.8f),
            PropertyFactory.lineOpacity(0.9f),
            PropertyFactory.lineDasharray(arrayOf(0.4f, 2.4f)),
        )
        style.addLayerAbove(ties, PACK_RAILS_LAYER)
    }

    if (style.getLayer(PACK_STOPS_LAYER) == null) {
        val density = android.content.res.Resources.getSystem().displayMetrics.density
        GroundTokens.StopKind.entries.forEach { kind ->
            style.addImage(GroundTokens.stopImageId(kind, isDark), GroundTokens.stop(kind, isDark, density))
        }
        fun img(kind: GroundTokens.StopKind) = Expression.literal(GroundTokens.stopImageId(kind, isDark))
        val tiles = SymbolLayer(PACK_STOPS_LAYER, PACK_STOPS_SOURCE)
        tiles.setProperties(
            PropertyFactory.iconImage(
                Expression.match(
                    Expression.get("mode"),
                    img(GroundTokens.StopKind.BUS),
                    Expression.stop("TRAM", img(GroundTokens.StopKind.TRAM)),
                    Expression.stop("METRO", img(GroundTokens.StopKind.STATION)),
                    Expression.stop("RAIL", img(GroundTokens.StopKind.STATION)),
                    Expression.stop("FUNICULAR", img(GroundTokens.StopKind.STATION)),
                ),
            ),
            PropertyFactory.iconSize(
                Expression.interpolate(
                    Expression.linear(), Expression.zoom(),
                    Expression.stop(12f, 0.7f),
                    Expression.stop(15f, 1f),
                    Expression.stop(17.5f, 1.3f),
                ),
            ),
            PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
        )
        addPackLayer(style, tiles)
    }
    if (style.getLayer(PACK_STOPS_LABEL) == null) {
        val label = SymbolLayer(PACK_STOPS_LABEL, PACK_STOPS_SOURCE)
        // Підпис приходить пізніше за крапку: на z13 їх сотні, і назви
        // зʼїли б мапу. Крапка каже «тут є зупинка» на всьому діапазоні,
        // підпис — «і зветься так» лише коли є місце.
        label.minZoom = 15.5f
        label.setProperties(
            PropertyFactory.textField(Expression.get("label")),
            // Без явного шрифту MapLibre просить у OpenFreeMap типовий стек,
            // якого там немає, і підпис МОВЧКИ не малюється — глиф 404.
            // Спіймано сторожем MapStyleConfigTest, не оком.
            PropertyFactory.textFont(MAP_TEXT_FONT),
            PropertyFactory.textSize(10.5f),
            PropertyFactory.textColor(if (isDark) "#E6EDF3" else "#1B2530"),
            PropertyFactory.textHaloColor(haloHex),
            PropertyFactory.textHaloWidth(1.2f),
            PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
            PropertyFactory.textOffset(arrayOf(0f, 0.7f)),
            PropertyFactory.textMaxWidth(7f),
            PropertyFactory.textOptional(true),
            PropertyFactory.textAllowOverlap(false),
        )
        style.addLayerAbove(label, PACK_STOPS_LAYER)
    }
}

/**
 * Мережа лягає ПІД стрічку обраного маршруту, якщо та вже стоїть, і просто
 * зверху, якщо ні.
 */
private fun addPackLayer(style: Style, layer: org.maplibre.android.style.layers.Layer) {
    when {
        style.getLayer(TRANSIT_LEGS_CASING) != null ->
            style.addLayerBelow(layer, TRANSIT_LEGS_CASING)
        style.getLayer(ROUTE_LAYER_ID) != null -> style.addLayerBelow(layer, ROUTE_LAYER_ID)
        else -> style.addLayer(layer)
    }
}

internal fun updatePackTransitLayers(
    style: Style,
    stopsGeoJson: String,
    railsGeoJson: String,
    isDark: Boolean,
) {
    installPackTransitLayers(style, isDark)
    style.getSourceAs<GeoJsonSource>(PACK_STOPS_SOURCE)?.setGeoJson(stopsGeoJson)
    style.getSourceAs<GeoJsonSource>(PACK_RAILS_SOURCE)?.setGeoJson(railsGeoJson)
}
