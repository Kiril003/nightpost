package local.phantom.companion.feature.stream.ui.map.peers

import androidx.compose.ui.graphics.toArgb
import local.phantom.companion.core.design.theme.PhantomPalette
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.ui.map.EMPTY_FC
import local.phantom.companion.feature.stream.ui.map.MAP_TEXT_FONT
import local.phantom.companion.feature.stream.ui.map.MapSymbolPalette
import local.phantom.companion.feature.stream.ui.map.escapeJsonString
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource

internal const val PEER_ROUTE_SOURCE_ID = "phantom-peer-route-source"
internal const val PEER_ROUTE_LAYER = "phantom-peer-route-layer"
internal const val PEER_ROUTE_LABEL_LAYER = "phantom-peer-route-label"

/**
 * Фіолет — свідомо НЕ бірюза свого маршруту й не бурштин пучка: чужий план і
 * свій план на одній землі мусять читатись як дві різні речі з першого погляду,
 * інакше людина поїде не тим. Пунктир каже те саме вдруге, формою: суцільна
 * лінія тут значить «мій маршрут» і ніколи нічого іншого.
 */
private val PEER_ROUTE_INK = PhantomPalette.Violet.toArgb()

/** Довші за свій пунктир штрихи: цей маршрут читають, а не їдуть ним. */
private val PEER_ROUTE_DASH = arrayOf(3f, 2f)

internal fun installPeerRoutes(style: Style, mapIsDark: Boolean) {
    if (style.getSource(PEER_ROUTE_SOURCE_ID) == null) {
        style.addSource(GeoJsonSource(PEER_ROUTE_SOURCE_ID, EMPTY_FC))
    }
    if (style.getLayer(PEER_ROUTE_LAYER) == null) {
        style.addLayer(
            LineLayer(PEER_ROUTE_LAYER, PEER_ROUTE_SOURCE_ID).withProperties(
                PropertyFactory.lineColor(PEER_ROUTE_INK),
                PropertyFactory.lineWidth(4f),
                PropertyFactory.lineOpacity(0.9f),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                PropertyFactory.lineCap(Property.LINE_CAP_BUTT),
                PropertyFactory.lineDasharray(PEER_ROUTE_DASH),
            ),
        )
    }
    // Підпис на тій самій ламаній, а не окремим джерелом: «маршрут від Оксани»
    // мусить зникати разом зі своєю лінією, а не лишатись висіти над землею.
    if (style.getLayer(PEER_ROUTE_LABEL_LAYER) == null) {
        val palette = MapSymbolPalette.resolve(mapIsDark)
        style.addLayer(
            SymbolLayer(PEER_ROUTE_LABEL_LAYER, PEER_ROUTE_SOURCE_ID).withProperties(
                PropertyFactory.textField(Expression.get("label")),
                // Явна пачка шрифтів: усталена в стилі OpenFreeMap дає 404, і
                // підпис тоді мовчки не малюється взагалі.
                PropertyFactory.textFont(MAP_TEXT_FONT),
                PropertyFactory.textSize(12f),
                PropertyFactory.textColor(palette.textInk),
                PropertyFactory.textHaloColor(palette.textHalo),
                PropertyFactory.textHaloWidth(2f),
                PropertyFactory.symbolPlacement(Property.SYMBOL_PLACEMENT_LINE_CENTER),
                PropertyFactory.textOptional(true),
            ),
        )
    }
}

internal fun updatePeerRoutes(style: Style, geoJson: String, visible: Boolean, mapIsDark: Boolean) {
    val source = style.getSourceAs<GeoJsonSource>(PEER_ROUTE_SOURCE_ID) ?: return
    val palette = MapSymbolPalette.resolve(mapIsDark)
    style.getLayer(PEER_ROUTE_LABEL_LAYER)?.setProperties(
        PropertyFactory.textColor(palette.textInk),
        PropertyFactory.textHaloColor(palette.textHalo),
        PropertyFactory.visibility(if (visible) Property.VISIBLE else Property.NONE),
    )
    style.getLayer(PEER_ROUTE_LAYER)?.setProperties(
        PropertyFactory.visibility(if (visible) Property.VISIBLE else Property.NONE),
    )
    source.setGeoJson(if (visible) geoJson else EMPTY_FC)
}

/**
 * Чужий маршрут у тому вигляді, у якому його малюють.
 *
 * Свідомо НЕ тип дроту: цей каталог не має права знати мережевий шар навіть
 * іменем типу, і `PeerLayerFirewallTest` боронить це коренем пакета. Саме
 * звідти колись виросла б спокуса сходити по чужих координатах назовні.
 * Месенджер кладе — мапа читає, і більше нічого. Дріт стає фігурою на порозі,
 * у `PeerRouteFeed.kt`.
 */
internal data class PeerRouteFigure(
    val peerId: String,
    val label: String,
    val shape: List<Pair<Double, Double>>,
    val expiresAtMs: Long,
)

/**
 * Малюються ЛИШЕ ті маршрути, чий власний строк ще не вийшов.
 *
 * Фільтр стоїть тут, а не покладається на мітлу: мітла прокидається на строк,
 * і між пробудженнями протухлий маршрут лишався б на землі. Строк — межа
 * листа, а не порада рендеру.
 */
internal fun peerRouteGeoJson(figures: List<PeerRouteFigure>, nowMs: Long): String {
    val features = figures
        .filter { it.expiresAtMs > nowMs && it.shape.size >= 2 }
        .joinToString(",") { f ->
            // `[довгота, широта]` — переставлені кладуть маршрут в інше море.
            val coords = f.shape.joinToString(",") { (lat, lon) -> "[$lon,$lat]" }
            "{\"type\":\"Feature\",\"properties\":{" +
                "\"peerId\":\"${escapeJsonString(f.peerId)}\"," +
                "\"label\":\"${escapeJsonString(f.label)}\"}," +
                "\"geometry\":{\"type\":\"LineString\",\"coordinates\":[$coords]}}"
        }
    return "{\"type\":\"FeatureCollection\",\"features\":[$features]}"
}

/** «маршрут від N». Імʼя порожнім не буває — скринька дає короткий ідентифікатор. */
internal fun peerRouteLabel(res: android.content.res.Resources, name: String): String =
    res.getString(R.string.map_peer_route_label, name)
