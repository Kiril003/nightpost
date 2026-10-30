package local.phantom.companion.feature.stream.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.VectorSource

/**
 * **Земля, якої в чужому стилі немає.**
 *
 * [applyGroundCartography] лише ПЕРЕФАРБОВУЄ наявні шари — тож клас, для якого
 * у вендора шару немає, наша палітра малювала в нікуди. Виміряно на живому
 * Liberty (17.08, 111 шарів): немає жодного шару для `landcover.class=farmland`
 * і `rock`, ані для `landuse.class` commercial / retail / industrial / garages /
 * quarry / railway. Україна — країна полів: клас, що вкриває
 * більшість площі держави, не малювався взагалі, і саме тому знімок села на z16
 * — бежева пустка з однією дорогою.
 *
 * Тут же лежить друга половина ґрунту, яка не вмістилась у перефарб (правило
 * 500 рядків): зняті чужі ворота зумів і четвертий щабель ширини дороги.
 *
 * Мережі не коштує нічого: дані вже у векторних тайлах, патерни малюються
 * локально. На відміну від hillshade, це працює в офлайні — там, де мапа й
 * потрібна.
 */
internal fun installGroundBackfill(style: Style, isDark: Boolean, context: Context) {
    // Поле, скеля й промзона — рідні шари Обсидіана
    // (`obsidian-landcover-farmland` і сусіди), добудовувати нема чого.
    if (style.speaksObsidian()) return
    // Той самий спосіб, що в installBuildingExtrusions: OpenFreeMap зве джерело
    // «openmaptiles», Mapbox-класика — «composite», і хардкод означав би шари,
    // які ніколи не малюються.
    val source = style.sources.firstOrNull { it is VectorSource }?.id ?: return
    val p = groundPalette(isDark)
    val density = context.resources.displayMetrics.density

    if (style.getLayer(GROUND_FARMLAND_LAYER) == null) {
        val layer = FillLayer(GROUND_FARMLAND_LAYER, source).withSourceLayer(LANDCOVER)
        layer.setFilter(classIs("farmland"))
        layer.setProperties(
            PropertyFactory.fillColor(p.farmland),
            PropertyFactory.fillOpacity(1f),
        )
        addAsGround(style, layer, VENDOR_LANDCOVER_WOOD, above = false)
    }

    if (style.getLayer(GROUND_ROCK_LAYER) == null) {
        val layer = FillLayer(GROUND_ROCK_LAYER, source).withSourceLayer(LANDCOVER)
        layer.setFilter(classIs("rock"))
        layer.setProperties(
            PropertyFactory.fillColor(p.rock),
            PropertyFactory.fillOpacity(1f),
        )
        addAsGround(style, layer, VENDOR_LANDCOVER_WOOD, above = false)
    }

    if (style.getLayer(GROUND_ZONES_LAYER) == null) {
        val layer = FillLayer(GROUND_ZONES_LAYER, source).withSourceLayer(LANDUSE)
        layer.setFilter(classIs(*ZONE_CLASSES))
        layer.setProperties(
            PropertyFactory.fillColor(
                match(
                    "class",
                    "commercial" to p.commercial,
                    "retail" to p.commercial,
                    "industrial" to p.industrial,
                    "garages" to p.industrial,
                    "quarry" to p.rock,
                    "railway" to p.industrial,
                    default = p.industrial,
                ),
            ),
            PropertyFactory.fillOpacity(1f),
        )
        addAsGround(style, layer, VENDOR_LANDUSE_RESIDENTIAL, above = true)
    }

    if (style.getLayer(GROUND_WETLAND_PATTERN_LAYER) == null) {
        style.addImage(MARSH_PATTERN, marshBitmap(marshInk(isDark), density))
        val layer = FillLayer(GROUND_WETLAND_PATTERN_LAYER, source).withSourceLayer(LANDCOVER)
        layer.setFilter(classIs("wetland"))
        layer.setProperties(PropertyFactory.fillPattern(MARSH_PATTERN))
        layer.minZoom = MARSH_MIN_ZOOM
        addAsGround(style, layer, VENDOR_LANDCOVER_WETLAND, above = true)
    }

    val shadow = buildingShadowColor(isDark)
    if (shadow != null && style.getLayer(BUILDING_SHADOW_LAYER) == null) {
        val layer = FillLayer(BUILDING_SHADOW_LAYER, source).withSourceLayer(BUILDING_SOURCE_LAYER)
        layer.setProperties(
            PropertyFactory.fillColor(shadow),
            PropertyFactory.fillTranslate(arrayOf(1f, 1.5f)),
            // VIEWPORT, не MAP: сонце не обертається разом із мапою під пальцем.
            PropertyFactory.fillTranslateAnchor(Property.FILL_TRANSLATE_ANCHOR_VIEWPORT),
        )
        layer.minZoom = BUILDING_DETAIL_MIN_ZOOM
        addAsBuilding(style, layer, VENDOR_BUILDING, above = false)
    }

    if (style.getLayer(BUILDING_EDGE_LAYER) == null) {
        val layer = LineLayer(BUILDING_EDGE_LAYER, source).withSourceLayer(BUILDING_SOURCE_LAYER)
        layer.setProperties(
            PropertyFactory.lineColor(p.buildingEdge),
            PropertyFactory.lineWidth(
                Expression.interpolate(
                    Expression.linear(),
                    Expression.zoom(),
                    Expression.stop(15, 0.5f),
                    Expression.stop(18, 1.2f),
                ),
            ),
        )
        layer.minZoom = BUILDING_DETAIL_MIN_ZOOM
        addAsBuilding(style, layer, VENDOR_BUILDING, above = true)
    }
}

/**
 * Тінь падає лише там, де є світло.
 *
 * Композит цієї фарби над НІЧНОЮ землею не відрізняється від самої землі —
 * нижче порога розрізнення, який міряє MapGroundCartographyTest. Намалювати її
 * там означало б платити за шар, якого не видно, і вважати, що будівлі мають
 * обʼєм.
 */
internal fun buildingShadowColor(isDark: Boolean): Int? = if (isDark) null else 0x332E2417

/* ── Патерни ─────────────────────────────────────────────────────────────── */

internal fun marshInk(isDark: Boolean): Int =
    if (isDark) 0xFF2E5A60.toInt() else 0xFF7FA8A0.toInt()

private const val MARSH_TILE_W_DP = 20
private const val MARSH_TILE_H_DP = 12

/** Дві горизонтальні риски зі зсувом — знак болота, який читається формою, а
 *  не відтінком: під червоним фільтром відтінку не лишається. */
private fun marshBitmap(colorArgb: Int, density: Float): Bitmap {
    val w = (MARSH_TILE_W_DP * density).toInt().coerceAtLeast(1)
    val h = (MARSH_TILE_H_DP * density).toInt().coerceAtLeast(1)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorArgb
        style = Paint.Style.STROKE
        strokeWidth = 1.25f * density
    }
    canvas.drawLine(2f * density, 3.5f * density, 7f * density, 3.5f * density, paint)
    canvas.drawLine(11.5f * density, 8.5f * density, 16.5f * density, 8.5f * density, paint)
    return bmp
}

/* ── Куди лягає шар ──────────────────────────────────────────────────────── */

/**
 * Без якоря стиль — не Liberty, і вгадувати його порядок ми не будемо: ґрунт
 * лягає одразу над тлом. `addLayer` без якоря поклав би поле ПОВЕРХ доріг і
 * підписів — заливка на всю країну, що ховає мапу.
 */
private fun addAsGround(style: Style, layer: Layer, anchor: String, above: Boolean) {
    if (style.getLayer(anchor) != null) {
        if (above) style.addLayerAbove(layer, anchor) else style.addLayerBelow(layer, anchor)
        return
    }
    val base = style.layers.firstOrNull()
    if (base != null) style.addLayerAbove(layer, base.id) else style.addLayer(layer)
}

/** Те саме для шарів масштабу будівлі: без якоря — під перший підпис, бо
 *  будинок лежить над усіма заливками й під іменами. */
private fun addAsBuilding(style: Style, layer: Layer, anchor: String, above: Boolean) {
    if (style.getLayer(anchor) != null) {
        if (above) style.addLayerAbove(layer, anchor) else style.addLayerBelow(layer, anchor)
        return
    }
    val firstSymbol = style.layers.firstOrNull { it is SymbolLayer }
    if (firstSymbol != null) style.addLayerBelow(layer, firstSymbol.id) else style.addLayer(layer)
}

private fun classIs(vararg classes: String): Expression = Expression.match(
    *(
        listOf(Expression.get("class")) +
            classes.flatMap { listOf(Expression.literal(it), Expression.literal(true)) } +
            listOf(Expression.literal(false))
        ).toTypedArray(),
)

/* ── Чужі ворота ─────────────────────────────────────────────────────────── */

/**
 * Чужий обʼєм гаситься назавжди.
 *
 * `building-3d` малює той самий сірий `hsl(35,8%,85%)`, що й плаский шар, але
 * на нічній землі це 8.94:1 — кожен будинок світиться сірим лего яскравіше за
 * будь-який підпис. Форму міста несуть футпринти; обʼєм лишається платному
 * [installBuildingExtrusions] і Кришталевому місту.
 *
 * ⚠ Пара до цього — виняток у [setBuildingExtrusionsVisible]: без нього перший
 * же цикл Кришталевого міста повертає шару VISIBLE і лего воскресає.
 */
internal fun hideVendorVolumes(layer: FillExtrusionLayer) {
    if (layer.id != VENDOR_BUILDING_3D) return
    layer.setProperties(PropertyFactory.visibility(Property.NONE))
}

/**
 * Імена конкретних чужих шарів. Так само, як [CASING_TOKENS], це рішення про
 * РОЛЬ шару в стилі, а не про факт із даних: жодне поле тайла не каже, з якого
 * зуму вендор вирішив ховати тінт забудови.
 */
internal const val VENDOR_BUILDING_3D = "building-3d"
internal const val VENDOR_LANDUSE_RESIDENTIAL = "landuse_residential"
// Шар футпринтів, а НЕ source-layer: у Liberty вони звуться однаково, і
// сплутати їх означає мовчки втратити якір і покласти обвід під землю.
internal const val VENDOR_BUILDING = "building"
internal const val VENDOR_LANDCOVER_WOOD = "landcover_wood"
internal const val VENDOR_LANDCOVER_WETLAND = "landcover_wetland"
internal const val VENDOR_HIGHWAY_NAME_MINOR = "highway-name-minor"
internal const val VENDOR_PATH_PEDESTRIAN = "road_path_pedestrian"

/** Стеля самого MapLibre, тобто «ворота зняті». */
internal const val VENDOR_GATE_OPEN = 24f

internal const val STREET_NAME_MIN_ZOOM = 14f
private const val MARSH_MIN_ZOOM = 12f
private const val BUILDING_DETAIL_MIN_ZOOM = 15f

private const val LANDCOVER = "landcover"
private const val LANDUSE = "landuse"

/** Класи землі з наслідком, яких вендор не малює жодним шаром. */
internal val ZONE_CLASSES = arrayOf(
    "commercial", "retail", "industrial", "garages", "quarry", "railway",
)

/* ── Наші шари ───────────────────────────────────────────────────────────── */

internal const val GROUND_FARMLAND_LAYER = "phantom-ground-farmland"
internal const val GROUND_ROCK_LAYER = "phantom-ground-rock"
internal const val GROUND_ZONES_LAYER = "phantom-ground-zones"
internal const val GROUND_WETLAND_PATTERN_LAYER = "phantom-ground-wetland-pattern"
internal const val BUILDING_SHADOW_LAYER = "phantom-building-shadow"
internal const val BUILDING_EDGE_LAYER = "phantom-building-edge"
internal const val MARSH_PATTERN = "phantom-marsh"

/* ── Дорожня драбина ─────────────────────────────────────────────────────── */

/**
 * Ранг, ширина і дві облямівки.
 *
 * Живе тут, а не в перефарбі, через правило 500 рядків — але й за змістом це те
 * саме, чим займається цей файл: чужий стиль дає trunk рівно ту ширину, що й
 * дворовому проїзду, і одну на всіх облямівку.
 */
internal fun paintRoad(layer: LineLayer, p: GroundPalette, scaleRoads: Boolean) {
    val casing = CASING_TOKENS.any { layer.id.lowercase().contains(it) }
    val ink = if (casing) {
        // Дві облямівки замість однієї: удень серцевина національної мережі
        // тепла, і ієрархію тримає ще й те, на чому вона лежить. Уночі обидва
        // поля палітри — те саме виміряне чорнило, тож вираз нічого не змінює.
        match(
            "class",
            "motorway" to p.roadCasingNational,
            "trunk" to p.roadCasingNational,
            default = p.roadCasing,
        )
    } else {
        match(
            "class",
            "motorway" to p.roadNational,
            "trunk" to p.roadNational,
            "primary" to p.roadRegional,
            "secondary" to p.roadRegional,
            "rail" to p.rail,
            "transit" to p.rail,
            "path" to p.path,
            "track" to p.path,
            "ferry" to p.waterway,
            "pier" to p.buildingEdge,
            default = p.roadLocal,
        )
    }
    layer.setProperties(PropertyFactory.lineColor(ink))
    // Стежка — пунктиром. Це єдиний чужий шар, що везе самі path і pedestrian,
    // тож розрив можна дати шару, а не обʼєкту: dash у MapLibre не залежить від
    // даних, а track сидить в одному шарі зі службовим проїздом.
    if (layer.id == VENDOR_PATH_PEDESTRIAN) {
        layer.setProperties(PropertyFactory.lineDasharray(arrayOf(2.5f, 1.5f)))
    }
    // І серцевина, І облямівка. Множник лише на серцевині означав, що на z20
    // національна серцевина (18×1.5 = 27px) ковтала власну облямівку (22px) — і
    // дорога з двох ліній ставала однією білою смугою.
    if (scaleRoads) scaleRoadWidth(layer, roadWidthByClass())
}

/**
 * Ширина щабля — від класу тайла, а не від рангу чорнила.
 *
 * Рангів чорнила лишається три (вони виміряні проти двадцяти одного ґрунту),
 * але `tertiary` — це реальна сітка руху між селами, і йти їй завширшки з
 * дворовим проїздом не можна. Власний крок ширини дає їй голос, не чіпаючи
 * виміряного чорнила.
 */
private fun roadWidthByClass(): Expression = Expression.match(
    *(
        listOf(Expression.get("class")) +
            WIDTH_STEPPED_CLASSES.flatMap {
                listOf(Expression.literal(it), Expression.literal(roadWidthScaleOf(it)))
            } +
            listOf(Expression.literal(roadWidthScaleOf(WIDTH_DEFAULT_CLASS)))
        ).toTypedArray(),
)

private val WIDTH_STEPPED_CLASSES = listOf("motorway", "trunk", "primary", "secondary", "tertiary")
private const val WIDTH_DEFAULT_CLASS = "minor"

/** Четвертий щабель драбини ширини — саме він і тільки він. */
internal const val TERTIARY_WIDTH_SCALE = 1.05f

internal fun roadWidthScaleOf(osmClass: String): Float =
    if (osmClass == "tertiary") TERTIARY_WIDTH_SCALE else roadWidthScale(roadRankOf(osmClass))

/**
 * Як чужий стиль зве шар-облямівку. Це єдине місце, де імʼя шару лишилося
 * підставою для рішення — бо облямівка це РОЛЬ шару в стилі, а не факт із
 * даних: обидва шари читають той самий `transportation` із тим самим `class`.
 */
private val CASING_TOKENS = listOf("casing", "_case", "outline")
