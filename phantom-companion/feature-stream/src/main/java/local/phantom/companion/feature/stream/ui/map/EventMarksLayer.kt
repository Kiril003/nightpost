package local.phantom.companion.feature.stream.ui.map

import android.content.Context
import local.phantom.companion.feature.stream.ui.map.icons.marks.MarkBar
import local.phantom.companion.feature.stream.ui.map.icons.marks.MarkKind
import local.phantom.companion.feature.stream.ui.map.icons.marks.MarkPlate
import local.phantom.companion.feature.stream.ui.map.icons.marks.WorldFigures
import local.phantom.companion.feature.stream.ui.map.world.WorldKind
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * ЗНАКИ ПОДІЙ — у движку, не поверх нього.
 *
 * До цього це був Compose-оверлей із власним `OnCameraMoveListener`: він гнав
 * рекомпозицію на КОЖЕН кадр камери, проєктував кожну загрозу в екранні
 * координати і все одно защіпався на іншому кадрі, ніж земля, — тобто пігулки
 * пливли по мапі під пальцем. Тепер це три шари стилю на одному джерелі:
 *
 *  1. [MARKS_BAR_LAYER] — брус воріт, вирівняний ПО МАПІ й повернутий за
 *     курсом шляху. Тільки там, де курс справді відомий.
 *  2. [MARKS_LAYER] — фігурка, білборд, привʼязана НИЗОМ до точки контакту.
 *  3. [MARKS_LIVE_LAYER] — крапка провенансу: мітка від спільноти, не своя.
 */
internal const val MARKS_SOURCE_ID = "phantom-marks-source"
internal const val MARKS_BAR_LAYER = "phantom-marks-bar-layer"
internal const val MARKS_LAYER = "phantom-marks-layer"
internal const val MARKS_LIVE_LAYER = "phantom-marks-live-layer"
internal const val MARKS_BREATH_LAYER = "phantom-marks-breath-layer"
internal val BREATH_AMBER = local.phantom.companion.feature.stream.ui.map.icons.marks.MarkPalette.AMBER_NIGHT

/** Скільки порожнечі фігурка вимагає навколо себе — спільний закон, [MapSymbolSpace]. */
private const val MARK_PADDING_PX = MapSymbolSpace.ICON_PADDING_PX

/** Крапка провенансу біля правої підошви, 4dp з чорнильним обідком. */
private const val LIVE_DOT_R = 2f
private val LIVE_DOT_OFFSET = arrayOf(14f, -3f)
private const val LIVE_NIGHT = 0xFF3DDC84.toInt()
private const val LIVE_DAY = 0xFF1FA45B.toInt()

/**
 * 28 → 32 → 36dp від авторських 36. [Expression.zoom] мусить лишатись ПРЯМИМ
 * входом верхньорівневого interpolate — вкладений у match чи product він
 * відкидається разом із властивістю (SymbolExpressionFirewallTest).
 */
private fun markSizeRamp(): Expression = Expression.interpolate(
    Expression.linear(), Expression.zoom(),
    Expression.stop(15f, 0.78f),
    Expression.stop(16f, 0.89f),
    Expression.stop(17.5f, 1f),
)

/** Тіло кожної фічі — з її `fig` (вид, «-accent» для мети й тривоги) і полярності. */
private fun figureImage(isDark: Boolean): Expression = Expression.concat(
    Expression.literal("mark-"),
    Expression.get("fig"),
    Expression.literal(if (isDark) "-night" else "-day"),
)

/** Хто лишається в тісноті — ранг зі світу мапи (placeWorld): менше — важливіше. */
private fun markSortKey(): Expression = Expression.get("rank")

/**
 * Стилі, у яких знаки вже намальовано, і полярність, у якій саме. Слабкі
 * ключі: стиль живе своїм життям і має право згинути. Значення-полярність —
 * єдине, що змушує перемалювати знаки при зміні теми без перезавантаження
 * стилю (та сама дисципліна, що в POIOverlay).
 */
private val markedStyles: MutableMap<Style, Boolean> = java.util.WeakHashMap()

private fun registerMarkImages(style: Style, context: Context, isDark: Boolean) {
    if (markedStyles.put(style, isDark) == isDark) return
    val density = context.resources.displayMetrics.density
    WorldKind.entries.forEach { kind ->
        listOf(false, true).forEach { accent ->
            style.addImage(WorldFigures.imageId(kind, isDark, accent), WorldFigures.figure(kind, isDark, accent, density))
        }
    }
    style.addImage(MarkPlate.barImageId(isDark), MarkPlate.bar(isDark, density))
}

internal fun installEventMarks(style: Style, context: Context, mapIsDark: Boolean) {
    registerMarkImages(style, context, mapIsDark)

    if (style.getSource(MARKS_SOURCE_ID) == null) {
        style.addSource(GeoJsonSource(MARKS_SOURCE_ID, EMPTY_FC))
    }

    if (style.getLayer(MARKS_BAR_LAYER) == null) {
        val bar = SymbolLayer(MARKS_BAR_LAYER, MARKS_SOURCE_ID).withProperties(
            PropertyFactory.iconImage(MarkPlate.barImageId(mapIsDark)),
            PropertyFactory.iconSize(markSizeRamp()),
            // MAP-вирівнювання робить icon-rotate КОМПАСНИМ курсом; брус
            // намальовано горизонтально, тож курс шляху кладе його ВПОПЕРЕК.
            PropertyFactory.iconRotate(Expression.get("bearing")),
            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
            PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
            PropertyFactory.iconAnchor(Property.ICON_ANCHOR_CENTER),
            // Меблі під фігуркою не змагаються за місце: вони частина тієї
            // самої фічі, і зникнути окремо від неї означало б збрехати.
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
        )
        bar.setFilter(
            Expression.all(
                Expression.eq(Expression.get("kind"), Expression.literal(MarkKind.GATE.id)),
                Expression.get("aimed"),
            ),
        )
        style.addLayer(bar)
    }

    if (style.getLayer(MARKS_LAYER) == null) {
        val figures = SymbolLayer(MARKS_LAYER, MARKS_SOURCE_ID).withProperties(
            PropertyFactory.iconImage(figureImage(mapIsDark)),
            PropertyFactory.iconSize(markSizeRamp()),
            PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
            // Низ бітмапа нижчий за точку контакту — без цього доведення знак
            // висів би на два dp північніше за координату, яку заявляє.
            PropertyFactory.iconOffset(arrayOf(0f, MarkPlate.ANCHOR_SLACK_DP)),
            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_VIEWPORT),
            PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT),
            // Літерал, ніколи вираз: вираз від даних MapLibre мовчки відкидає
            // разом із властивістю. Пріоритет у тісноті — symbolSortKey.
            PropertyFactory.iconAllowOverlap(false),
            PropertyFactory.iconIgnorePlacement(false),
            PropertyFactory.iconPadding(MARK_PADDING_PX),
            PropertyFactory.symbolSortKey(markSortKey()),
            // Зачинене, давнє, не на твоєму шляху — тихіше, але на місці.
            PropertyFactory.iconOpacity(Expression.get("opacity")),
        )
        style.addLayer(figures)
    }

    // Коло під укриттям, над яким тривога: стоїть завжди, дихає — обмежено (ShelterBreath).
    if (style.getLayer(MARKS_BREATH_LAYER) == null) {
        val rest = local.phantom.companion.feature.stream.ui.map.world.ShelterBreath.rest
        val breath = CircleLayer(MARKS_BREATH_LAYER, MARKS_SOURCE_ID).withProperties(
            PropertyFactory.circleRadius(rest.radiusDp),
            PropertyFactory.circleOpacity(rest.opacity),
            PropertyFactory.circleColor(BREATH_AMBER),
            PropertyFactory.circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_MAP),
        )
        breath.setFilter(Expression.get("breathe"))
        style.addLayerBelow(breath, MARKS_LAYER)
    }

    if (style.getLayer(MARKS_LIVE_LAYER) == null) {
        val dot = CircleLayer(MARKS_LIVE_LAYER, MARKS_SOURCE_ID).withProperties(
            PropertyFactory.circleRadius(LIVE_DOT_R),
            PropertyFactory.circleColor(if (mapIsDark) LIVE_NIGHT else LIVE_DAY),
            PropertyFactory.circleStrokeColor(0xFF14161B.toInt()),
            PropertyFactory.circleStrokeWidth(1f),
            PropertyFactory.circleTranslate(LIVE_DOT_OFFSET),
            // Зсув у ЕКРАННІЙ рамці: за замовчуванням він у мапній, тож на
            // повороті камери крапка поїхала б навколо підошви.
            PropertyFactory.circleTranslateAnchor(Property.CIRCLE_TRANSLATE_ANCHOR_VIEWPORT),
        )
        dot.setFilter(Expression.get("community"))
        style.addLayer(dot)
    }
}

internal fun updateEventMarks(
    style: Style,
    geoJson: String,
    visible: Boolean,
    mapIsDark: Boolean,
) {
    val source = style.getSourceAs<GeoJsonSource>(MARKS_SOURCE_ID) ?: return
    val visibility = if (visible) Property.VISIBLE else Property.NONE
    val bar = style.getLayer(MARKS_BAR_LAYER)
    val figures = style.getLayer(MARKS_LAYER)
    val dot = style.getLayer(MARKS_LIVE_LAYER)
    bar?.setProperties(
        PropertyFactory.visibility(visibility),
        PropertyFactory.iconImage(MarkPlate.barImageId(mapIsDark)),
    )
    figures?.setProperties(
        PropertyFactory.visibility(visibility),
        PropertyFactory.iconImage(figureImage(mapIsDark)),
    )
    dot?.setProperties(
        PropertyFactory.visibility(visibility),
        PropertyFactory.circleColor(if (mapIsDark) LIVE_NIGHT else LIVE_DAY),
    )
    // Свідчення для польової звірки: скільки знаків у сцені та скільки з них —
    // мітки шару (лаг «мітка злилась → знак на екрані» читається з журналу).
    // `community` окремо: один рядок у мить приходу не відрізняє «мітка не
    // намалювалась» від «мітка пішла з шару наступним кадром», а це різні
    // хвороби і лікуються в різних файлах.
    if (visible) {
        android.util.Log.i(
            "EventMarks",
            "scene=${geoJson.split("\"kind\":").size - 1} " +
                "layerMarks=${geoJson.split("\"mark\":true").size - 1} " +
                "community=${geoJson.split("\"community\":true").size - 1} " +
                // Джерело живе окремо від шарів: нагодувати його і не мати чим
                // намалювати — це РІЗНІ поломки з однаковим рядком у журналі.
                "layers=${listOfNotNull(bar, figures, dot).size}/3",
        )
    }
    source.setGeoJson(if (visible) geoJson else EMPTY_FC)
}

/**
 * Фічі знаків. `bearing` є завжди (0 — «невідомо»), а право бруса воріт на
 * малювання несе окреме `aimed`: нуль градусів — це теж курс, і сплутати їх
 * означало б класти брус упоперек уявної дороги.
 */
internal fun eventMarksGeoJson(marks: List<EventMark>): String {
    // Той самий екранувальник, що й у шарів фантома: назва з OSM цілком може
    // містити лапку, а `Double.toString` не залежить від локалі — на відміну
    // від String.format, який на українському апараті пише кому.
    val features = marks.joinToString(",") { m ->
        val props = buildString {
            append("\"id\":\"").append(escapeJsonString(m.id)).append("\",")
            append("\"kind\":\"").append(m.kind?.id ?: m.world.id).append("\",")
            append("\"label\":\"").append(escapeJsonString(m.label)).append("\",")
            append("\"detail\":\"").append(escapeJsonString(m.detail.orEmpty())).append("\",")
            append("\"mark\":").append(m.mark).append(",")
            append("\"community\":").append(m.community).append(",")
            append("\"aimed\":").append(m.bearingDeg != null).append(",")
            append("\"bearing\":").append(m.bearingDeg ?: 0.0).append(",")
            append("\"fig\":\"").append(m.world.id).append(if (m.accent) "-accent" else "").append("\",")
            append("\"rank\":").append(m.rank).append(",")
            append("\"opacity\":").append(m.opacity).append(",")
            append("\"breathe\":").append(m.breathe).append(",")
            append("\"cat\":\"").append(escapeJsonString(m.poiCategory.orEmpty())).append("\",")
            append("\"sub\":\"").append(escapeJsonString(m.poiSubcategory.orEmpty())).append("\"")
        }
        "{\"type\":\"Feature\",\"properties\":{$props}," +
            "\"geometry\":{\"type\":\"Point\",\"coordinates\":[${m.lon},${m.lat}]}}"
    }
    return "{\"type\":\"FeatureCollection\",\"features\":[$features]}"
}
