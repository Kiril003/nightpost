package local.phantom.companion.feature.stream.ui.map

import androidx.compose.ui.graphics.toArgb
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

internal const val OFFLINE_PACKS_SOURCE = "phantom-offline-packs-source"
internal const val OFFLINE_PACKS_FILL = "phantom-offline-packs-fill"
internal const val OFFLINE_PACKS_LINE = "phantom-offline-packs-line"

internal fun installOfflinePacks(style: Style) {
    if (style.getSource(OFFLINE_PACKS_SOURCE) == null) {
        style.addSource(GeoJsonSource(OFFLINE_PACKS_SOURCE, EMPTY_FC))
    }
    if (style.getLayer(OFFLINE_PACKS_FILL) == null) {
        style.addLayer(
            FillLayer(OFFLINE_PACKS_FILL, OFFLINE_PACKS_SOURCE).withProperties(
                PropertyFactory.fillColor(Expression.get("color")),
                PropertyFactory.fillOpacity(
                    Expression.switchCase(
                        Expression.get("selected"), Expression.literal(0.26f),
                        Expression.literal(0.12f),
                    ),
                ),
            ),
        )
    }
    if (style.getLayer(OFFLINE_PACKS_LINE) == null) {
        style.addLayer(
            LineLayer(OFFLINE_PACKS_LINE, OFFLINE_PACKS_SOURCE).withProperties(
                PropertyFactory.lineColor(Expression.get("color")),
                PropertyFactory.lineWidth(
                    Expression.switchCase(
                        Expression.get("selected"), Expression.literal(2.6f),
                        Expression.literal(1.2f),
                    ),
                ),
                PropertyFactory.lineOpacity(
                    Expression.switchCase(
                        Expression.get("selected"), Expression.literal(0.95f),
                        Expression.literal(0.55f),
                    ),
                ),
            ),
        )
    }
}

internal fun updateOfflinePacks(style: Style, geoJson: String) {
    style.getSourceAs<GeoJsonSource>(OFFLINE_PACKS_SOURCE)?.setGeoJson(geoJson)
}

internal fun hexOf(color: androidx.compose.ui.graphics.Color): String =
    String.format("#%06X", 0xFFFFFF and color.toArgb())
