package local.phantom.companion.feature.stream.ui.map

import local.phantom.companion.core.data.spatial.GeoPoint
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

internal const val DRAW_LINE_SOURCE = "phantom-draw-line-source"
internal const val DRAW_LINE_LAYER = "phantom-draw-line-layer"
internal const val DRAW_LINE_VERTICES = "phantom-draw-line-vertices"
internal const val DRAW_LINE_VERTEX_HELD = "phantom-draw-line-vertex-held"

/** Обрана вершина в GeoJSON. Одна на всю збірку — обраних не буває двох. */
private const val DRAW_SELECTED_KEY = "sel"

/**
 * Ламана, яку саме малюють пальцем.
 *
 * Свідомо власне джерело, а не спільний шар просторових шарів: той пише в
 * Room на кожен дотик, і кожна вершина коштувала б запису на диск. Тут лінія
 * живе стільки, скільки триває режим, і зникає разом із ним.
 */
internal fun installDrawLine(style: Style, isDark: Boolean) {
    val ink = if (isDark) DRAW_INK_DARK else DRAW_INK_LIGHT
    val halo = if (isDark) "#000000" else "#FFFFFF"
    if (style.getSource(DRAW_LINE_SOURCE) == null) {
        style.addSource(GeoJsonSource(DRAW_LINE_SOURCE, EMPTY_FC))
    }
    if (style.getLayer(DRAW_LINE_LAYER) == null) {
        style.addLayer(
            LineLayer(DRAW_LINE_LAYER, DRAW_LINE_SOURCE).withProperties(
                PropertyFactory.lineColor(ink),
                PropertyFactory.lineWidth(4f),
                PropertyFactory.lineOpacity(0.9f),
                PropertyFactory.lineJoin("round"),
                PropertyFactory.lineCap("round"),
            ),
        )
    }
    // Вершини окремо: без них не видно, куди саме ліг дотик, і «прибрати
    // останню» прибирала б щось невидиме.
    if (style.getLayer(DRAW_LINE_VERTICES) == null) {
        style.addLayer(
            CircleLayer(DRAW_LINE_VERTICES, DRAW_LINE_SOURCE).withProperties(
                PropertyFactory.circleRadius(4.5f),
                PropertyFactory.circleColor(ink),
                PropertyFactory.circleStrokeWidth(1.5f),
                PropertyFactory.circleStrokeColor(halo),
                PropertyFactory.circleOpacity(0.95f),
                PropertyFactory.circleStrokeOpacity(0.9f),
            ).withFilter(
                Expression.neq(Expression.get(DRAW_SELECTED_KEY), Expression.literal(true)),
            ),
        )
    }
    // Обрана вершина — окремим шаром, а не більшим радіусом на тому самому:
    // палець накриває вершину цілком, і єдине, чим правка каже «тримаю саме
    // цю», — розмір під пальцем. 11 проти 4,5 видно й крізь палець.
    if (style.getLayer(DRAW_LINE_VERTEX_HELD) == null) {
        style.addLayer(
            CircleLayer(DRAW_LINE_VERTEX_HELD, DRAW_LINE_SOURCE).withProperties(
                PropertyFactory.circleRadius(11f),
                PropertyFactory.circleColor(halo),
                PropertyFactory.circleStrokeWidth(3f),
                PropertyFactory.circleStrokeColor(ink),
                PropertyFactory.circleOpacity(0.85f),
            ).withFilter(
                Expression.eq(Expression.get(DRAW_SELECTED_KEY), Expression.literal(true)),
            ),
        )
    }
}

internal fun updateDrawLine(style: Style, geoJson: String) {
    style.getSourceAs<GeoJsonSource>(DRAW_LINE_SOURCE)?.setGeoJson(geoJson)
}

/**
 * Вершини → FeatureCollection: сама ламана плюс по крапці на вершину.
 *
 * Одна вершина ще не лінія, тому LineString не пишеться взагалі — MapLibre
 * на відрізку з однієї точки малює порожнечу, і людина бачила б, що дотик
 * «не спрацював».
 */
internal fun buildDrawGeoJson(points: List<GeoPoint>, selected: Int? = null): String {
    if (points.isEmpty()) return EMPTY_FC
    // Рядком, а не org.json: у модульних тестах цього модуля `org.json` —
    // андроїдна заглушка, що віддає null, тож збірка через неї не перевіряється
    // взагалі. Тут нема жодного рядкового значення, тому екранувати нічого, а
    // `append(Double)` не має локалі й не підмінить крапку комою.
    val sb = StringBuilder("""{"type":"FeatureCollection","features":[""")
    if (points.size >= 2) {
        sb.append("""{"type":"Feature","properties":{},"geometry":""")
        sb.append("""{"type":"LineString","coordinates":[""")
        points.forEachIndexed { i, p ->
            if (i > 0) sb.append(',')
            sb.append('[').append(p.lon).append(',').append(p.lat).append(']')
        }
        sb.append("]}}")
    }
    points.forEachIndexed { i, p ->
        if (sb.last() != '[') sb.append(',')
        sb.append("""{"type":"Feature","properties":{""")
        if (i == selected) sb.append('"').append(DRAW_SELECTED_KEY).append("\":true")
        sb.append("""},"geometry":""")
        sb.append("""{"type":"Point","coordinates":[""")
            .append(p.lon).append(',').append(p.lat).append("]}}")
    }
    return sb.append("]}").toString()
}

private const val DRAW_INK_DARK = "#FFB020"
private const val DRAW_INK_LIGHT = "#B25E00"
