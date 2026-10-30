package local.phantom.companion.feature.stream.ui.map.peers

import android.content.Context
import local.phantom.companion.feature.stream.ui.map.EMPTY_FC
import local.phantom.companion.feature.stream.ui.map.MAP_TEXT_FONT
import local.phantom.companion.feature.stream.ui.map.MapSymbolPalette
import local.phantom.companion.feature.stream.ui.map.MapSymbolSpace
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * Контакти в движку (D1). Мережевої залежності немає й не буде — закон №1
 * контракту, `PeerLayerFirewallTest` тримає його на рівні імпортів.
 */
internal const val PEERS_SOURCE_ID = "phantom-peers-source"
internal const val PEERS_LAYER = "phantom-peers-layer"

private const val STALE_OPACITY = 0.55f

/**
 * [Expression.zoom] мусить лишатись ПРЯМИМ входом верхньорівневого interpolate —
 * вкладений у case чи match він відкидається разом із властивістю.
 */
private fun peerSizeRamp(): Expression = Expression.interpolate(
    Expression.linear(), Expression.zoom(),
    Expression.stop(11f, 0.72f),
    Expression.stop(14f, 0.86f),
    Expression.stop(16.5f, 1f),
)

private fun peerFigureImage(isDark: Boolean): Expression = Expression.switchCase(
    Expression.get("verified"),
    Expression.literal(PeerPlate.imageId(verified = true, isDark = isDark)),
    Expression.literal(PeerPlate.imageId(verified = false, isDark = isDark)),
)

private fun peerAgeOpacity(): Expression = Expression.switchCase(
    Expression.get("fresh"),
    Expression.literal(1f),
    Expression.literal(STALE_OPACITY),
)

/** Менше число кладеться першим: звірені поверх незвірених. */
private fun peerSortKey(): Expression = Expression.switchCase(
    Expression.get("verified"),
    Expression.literal(0f),
    Expression.literal(1f),
)

internal fun peerFigureLabel(): Expression = Expression.concat(
    Expression.get("name"),
    Expression.switchCase(
        Expression.neq(Expression.get("tag"), Expression.literal("")),
        Expression.concat(Expression.literal(" · "), Expression.get("tag")),
        Expression.literal(""),
    ),
)

private val platedStyles: MutableMap<Style, Boolean> = java.util.WeakHashMap()

private fun registerPeerImages(style: Style, context: Context, isDark: Boolean) {
    if (platedStyles.put(style, isDark) == isDark) return
    val density = context.resources.displayMetrics.density
    listOf(true, false).forEach { verified ->
        style.addImage(
            PeerPlate.imageId(verified, isDark),
            PeerPlate.figure(verified, isDark, density),
        )
    }
}

/**
 * Один шар на фігурку й імʼя, не два: підпис мусить зникати разом зі своєю
 * людиною, а не змагатись за місце окремо від неї.
 */
internal fun installPeerFigures(style: Style, context: Context, mapIsDark: Boolean) {
    registerPeerImages(style, context, mapIsDark)

    if (style.getSource(PEERS_SOURCE_ID) == null) {
        style.addSource(GeoJsonSource(PEERS_SOURCE_ID, EMPTY_FC))
    }
    if (style.getLayer(PEERS_LAYER) != null) return

    val palette = MapSymbolPalette.resolve(mapIsDark)
    style.addLayer(
        SymbolLayer(PEERS_LAYER, PEERS_SOURCE_ID).withProperties(
            PropertyFactory.iconImage(peerFigureImage(mapIsDark)),
            PropertyFactory.iconSize(peerSizeRamp()),
            PropertyFactory.iconOpacity(peerAgeOpacity()),
            PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
            // Низ бітмапа нижчий за точку контакту: без доводки людина стоїть
            // на два dp північніше за координату, яку заявляє.
            PropertyFactory.iconOffset(arrayOf(0f, PeerPlate.ANCHOR_SLACK_DP)),
            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_VIEWPORT),
            PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT),
            // Літерал, ніколи вираз: вираз від даних MapLibre мовчки відкидає
            // разом із властивістю. Пріоритет у тісноті — symbolSortKey.
            PropertyFactory.iconAllowOverlap(false),
            PropertyFactory.iconIgnorePlacement(false),
            PropertyFactory.iconPadding(MapSymbolSpace.ICON_PADDING_PX),
            PropertyFactory.symbolSortKey(peerSortKey()),

            PropertyFactory.textField(peerFigureLabel()),
            PropertyFactory.textFont(MAP_TEXT_FONT),
            PropertyFactory.textSize(11f),
            PropertyFactory.textColor(palette.textInk),
            PropertyFactory.textHaloColor(palette.textHalo),
            PropertyFactory.textHaloWidth(1f),
            PropertyFactory.textOpacity(peerAgeOpacity()),
            // Якір фіксований свідомо: у юрбі змінний пустив би імʼя під
            // сусідню людину, а це брехня про те, хто де стоїть.
            PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
            PropertyFactory.textOffset(arrayOf(0f, MapSymbolSpace.LABEL_RADIAL_OFFSET)),
            PropertyFactory.textPadding(MapSymbolSpace.LABEL_PADDING_PX),
            PropertyFactory.textAllowOverlap(false),
            PropertyFactory.textOptional(true),
        ),
    )
}

internal fun updatePeerFigures(
    style: Style,
    geoJson: String,
    visible: Boolean,
    mapIsDark: Boolean,
) {
    val source = style.getSourceAs<GeoJsonSource>(PEERS_SOURCE_ID) ?: return
    val palette = MapSymbolPalette.resolve(mapIsDark)
    style.getLayer(PEERS_LAYER)?.setProperties(
        PropertyFactory.visibility(if (visible) Property.VISIBLE else Property.NONE),
        PropertyFactory.iconImage(peerFigureImage(mapIsDark)),
        PropertyFactory.textColor(palette.textInk),
        PropertyFactory.textHaloColor(palette.textHalo),
    )
    // Свідчення для польової звірки: «фігурки немає» і «фігурка застаріла» —
    // різні хвороби з однаковим виглядом на склі.
    if (visible) {
        android.util.Log.i(
            "PeerFigures",
            "peers=${geoJson.split("\"nodeId\":").size - 1} " +
                "live=${geoJson.split("\"fresh\":true").size - 1} " +
                "verified=${geoJson.split("\"verified\":true").size - 1}",
        )
    }
    source.setGeoJson(if (visible) geoJson else EMPTY_FC)
}
