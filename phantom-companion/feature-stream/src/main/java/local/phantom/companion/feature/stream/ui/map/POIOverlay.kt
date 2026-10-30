package local.phantom.companion.feature.stream.ui.map

import local.phantom.companion.core.net.peer.PeerName
import android.content.Context
import android.graphics.Color
import local.phantom.companion.core.data.poi.PoiEntity
import local.phantom.companion.feature.stream.ui.map.icons.GroundTokens
import local.phantom.companion.feature.stream.ui.map.source.BatchGSource
import local.phantom.companion.feature.stream.ui.map.icons.PlaceToken
import local.phantom.companion.feature.stream.ui.map.icons.PinTier
import local.phantom.companion.feature.stream.ui.map.icons.PoiGlyphTable
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * The one face every pin on the map wears, in dp — the flat MapLibre symbol,
 * the saved-place star, the Compose marker over Crystal City. Sizes are dp and
 * multiplied by density at the point of use: a bitmap floor measured in raw
 * pixels skews by density and is how «26dp» became four different sizes.
 *
 * The pad matters as much as the content. A rim drawn hard against the bitmap
 * boundary loses its own anti-aliased outer edge to the crop, which reads as a
 * flat spot on one side of the circle and is half of what «люфт» looked like.
 *
 * [GLYPH_DP] — сторона коробки, у яку вписано знак родини (сітка 24×24), а не
 * кегль шрифту: емодзі малювало ~85% свого кегля, векторний гліф малює всю
 * коробку, тож те саме число тепер дає більше чорнила.
 *
 * Colours live in [MapSymbolPalette]; this is only the geometry.
 */
internal object MapPinFace {
    const val CONTENT_DP = 26f
    const val PAD_DP = 3f
    const val BITMAP_DP = CONTENT_DP + 2 * PAD_DP
    const val RIM_DP = 1.5f
    const val RING_DP = 2f
    const val GLYPH_DP = 14f

    /** The only place a dp becomes a pixel. No floor, no coerce: a pixel floor
     *  is a different physical size on every screen, which is how one written
     *  size became several drawn ones. */
    fun bitmapPx(density: Float, contentDp: Float = CONTENT_DP, padDp: Float = PAD_DP): Int =
        ((contentDp + 2 * padDp) * density).toInt()
}

/**
 * Скільки порожнечі кожен наш знак вимагає навколо себе, у пікселях стилю.
 *
 * ГОНТЛЕТ-1 §5: «движок підписів не знає про UI». Напрошується `setPadding`,
 * але він не про це: заміряно по API 11.5.1 — `MapLibreMap.setPadding` веде в
 * `Projection.setContentPadding`, тобто у ВІДСТУПИ КАМЕРИ. Вони зсувають
 * проєкцію (а з нею й точку оператора з оптичного центру екрана) і жодної
 * смуги з розкладки символів не вилучають: межі зіткнень движок рахує з
 * РОЗМІРУ вьюпорта. Ціна певна, користь ні — тож не платимо.
 *
 * Тож єдиний важіль, який движок справді слухає, — поле навколо символу. Числа
 * тут спільні для ВСІХ трьох наших символьних шарів (місця, мітки, знаки
 * подій): різне поле на однакову роль читається як випадковий розкид, а не як
 * одна мапа.
 */
internal object MapSymbolSpace {
    /** Диск: 2 було рівно тим, що дозволяло двом тарілкам торкатися боками. */
    const val ICON_PADDING_PX = 4f

    /**
     * Підпис просить більше за диск: він широкий і горизонтальний, тож саме
     * підписи сходились у суцільний рядок на ТРЦ і вздовж вулиці магазинів.
     */
    const val LABEL_PADDING_PX = 6f

    /**
     * Куди підпис має право поїхати, коли на місці під піном його нема.
     *
     * Порядок — це і є правило. Спершу «під піном» (звичне око), далі ті кути,
     * де текст росте ЛІВОРУЧ від пікселя (`*RIGHT` означає, що права межа
     * тексту стоїть на якорі), і лише потім ті, де він росте праворуч. Смугу
     * рейки движок не бачить, але з такого порядку підпис біля правого краю
     * статистично тікає ВІД неї, а не під неї.
     */
    val LABEL_ANCHORS: Array<String> = arrayOf(
        org.maplibre.android.style.layers.Property.TEXT_ANCHOR_TOP,
        org.maplibre.android.style.layers.Property.TEXT_ANCHOR_TOP_RIGHT,
        org.maplibre.android.style.layers.Property.TEXT_ANCHOR_RIGHT,
        org.maplibre.android.style.layers.Property.TEXT_ANCHOR_TOP_LEFT,
        org.maplibre.android.style.layers.Property.TEXT_ANCHOR_LEFT,
    )

    /**
     * Одна відстань на два записи: радіус при змінному якорі й зсув-підлога.
     *
     * В em кегля підпису (10). Диск має радіус 13dp + 3dp поля = 16dp, тобто
     * 1.6em — це підпис упритул до обідка; 1.15 клав його НА диск, і поки
     * підпис жив окремим шаром, він цим зіткненням і вбивав власний диск.
     */
    const val LABEL_RADIAL_OFFSET = 1.85f
}

/**
 * Шар місць із кластеризацією.
 *
 * Гліф піна малює [PhantomPin] з власної родини знаків: та сама рука, та сама
 * вага штриха, той самий вигляд на кожному телефоні. До цього тут стояло
 * СИСТЕМНЕ кольорове емодзі — тобто вигляд мапи вирішував виробник прошивки, і
 * на одному апараті знак кави був плоскою чашкою, на іншому обʼємною, а на
 * третьому порожнім квадратом.
 */
object POIOverlay {
    const val POI_SOURCE_ID = "phantom-poi-source"
    const val POI_LAYER_ID = "phantom-poi-layer"
    const val POI_MINOR_LAYER_ID = "phantom-poi-minor-layer"
    const val POI_FURNITURE_LAYER_ID = "phantom-poi-furniture-layer"
    const val POI_CLUSTER_LAYER_ID = "phantom-poi-cluster-layer"

    /** Усі шари з дисками — їх ховають, показують і торкають разом. */
    val PIN_LAYER_IDS: List<String> = listOf(POI_LAYER_ID, POI_MINOR_LAYER_ID, POI_FURNITURE_LAYER_ID)

    /**
     * Ярус місця як число в ознаках: перші два — [PinTier] (укриття, аптека,
     * зупинка), третій — звичайна крамниця, четвертий — дворові меблі
     * (входи), які не є місцем, доки не стоїш перед будинком.
     */
    internal const val TIER_CRITICAL = 0
    internal const val TIER_SUSTAIN = 1
    internal const val TIER_ORDINARY = 2
    internal const val TIER_FURNITURE = 3

    /** Підпис критичних і опорних місць — з z15, як і було. */
    private const val MAJOR_LABEL_ZOOM = 15f
    /** Крамниці й кафе — на пів кроку глибше: на 500 м вони були суцільним текстом. */
    private const val MINOR_MIN_ZOOM = 15.5f
    /** Вхід у будинок видно, коли видно будинок; слово до нього — ще глибше. */
    private const val FURNITURE_MIN_ZOOM = 17.5f
    private const val FURNITURE_LABEL_ZOOM = 18.5f

    /** Довше за це підпис не є підписом — вивіска дантиста на чотири рядки (кадр 06.09). */
    internal const val LABEL_MAX_CHARS = 28

    private val GLUED_COMMA = Regex(",(?=\\S)")
    const val POI_COUNT_LAYER_ID = "phantom-poi-count-layer"
    private const val CLUSTER_TEXT_SP = 12f

    private const val DEFAULT_PIN_KEY = "poi-default"

    private fun imageId(key: String) = "poi-$key"

    /** Кільце і гліф носять один тон ярусу — колір ЗАВЖДИ з палітри. */
    private fun tintOf(tier: PinTier, palette: MapSymbolPalette): Int = when (tier) {
        PinTier.CRITICAL -> palette.pinCritical
        PinTier.SUSTAIN -> palette.pinSustain
        PinTier.ORDINARY -> palette.pinOrdinary
    }

    /**
     * Хто лишається, коли місця на всіх нема. Менше число кладеться першим, тож
     * у тісноті аптека й укриття витісняють крамницю, а не навпаки — інакше
     * розрідження просто прибирало б з екрана випадкові точки, і саме ті, що
     * потрібні, коли щось пішло не так, зникали б нарівні з рештою.
     */
    private fun sortKeyExpression(): Expression {
        val critical = PoiGlyphTable.criticalKeys
        val stops = ArrayList<Expression>()
        stops.add(Expression.get("pin"))
        (critical + PoiGlyphTable.sustainKeys).forEach { key ->
            stops.add(Expression.literal(key))
            stops.add(Expression.literal(if (key in critical) 0f else 1f))
        }
        stops.add(Expression.literal(2f))
        return Expression.match(*stops.toTypedArray())
    }

    fun install(style: Style, context: Context, isDark: Boolean = true) {
        val palette = MapSymbolPalette.resolve(isDark)
        registerPinImages(style, context, palette)

        if (style.getSource(POI_SOURCE_ID) == null) {
            val options = GeoJsonOptions()
                .withCluster(true)
                .withClusterRadius(50)
                .withClusterMaxZoom(14)
            style.addSource(GeoJsonSource(POI_SOURCE_ID, options))
        }

        // Одна символьна одиниця: диск І підпис. Досі підпис жив окремим шаром
        // над диском, а рушій розкладає верхній шар першим і віддає місце йому —
        // диск програвав зіткнення ВЛАСНОМУ підписові, і на 500 м мапа була самим
        // текстом без жодного знака (кадр A52 06.09). У межах одного символу
        // диск і текст не змагаються; textOptional лишає диск, коли тексту тісно.
        //
        // Порядок додавання = пріоритет у зіткненнях: вищий шар виграє, тож
        // меблі йдуть першими, звичайні місця другими, критичні — останніми.
        installPinLayer(style, POI_FURNITURE_LAYER_ID, palette, TIER_FURNITURE..TIER_FURNITURE, FURNITURE_MIN_ZOOM, FURNITURE_LABEL_ZOOM)
        installPinLayer(style, POI_MINOR_LAYER_ID, palette, TIER_ORDINARY..TIER_ORDINARY, MINOR_MIN_ZOOM, MINOR_MIN_ZOOM)
        installPinLayer(style, POI_LAYER_ID, palette, TIER_CRITICAL..TIER_SUSTAIN, null, MAJOR_LABEL_ZOOM)

        if (style.getLayer(POI_CLUSTER_LAYER_ID) == null) {
            fun byPile(pick: (GroundTokens.Pile) -> Expression): Expression = Expression.step(
                Expression.get("point_count"),
                pick(GroundTokens.Pile.FEW),
                Expression.stop(10, pick(GroundTokens.Pile.SOME)),
                Expression.stop(50, pick(GroundTokens.Pile.MANY)),
            )
            val layer = SymbolLayer(POI_CLUSTER_LAYER_ID, POI_SOURCE_ID).withProperties(
                PropertyFactory.iconImage(byPile { Expression.literal(GroundTokens.pileImageId(it, isDark)) }),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.textField(Expression.toString(Expression.get("point_count"))),
                PropertyFactory.textFont(MAP_TEXT_FONT),
                PropertyFactory.textSize(CLUSTER_TEXT_SP),
                PropertyFactory.textColor(palette.plateInk),
                // Лічба лежить на верхньому камені стосу, не в центрі бітмапа.
                PropertyFactory.textOffset(
                    byPile { GroundTokens.pileFaceOffsetDp(it).let { (x, y) -> Expression.literal(arrayOf(x / CLUSTER_TEXT_SP, y / CLUSTER_TEXT_SP)) } },
                ),
                PropertyFactory.textAllowOverlap(true),
                PropertyFactory.textIgnorePlacement(true),
            )
            layer.setFilter(Expression.has("point_count"))
            style.addLayer(layer)
        }
        installTilePoiLayer(style, palette)
    }

    const val TILE_POI_LAYER_ID = "phantom-tile-poi-layer"
    private const val TILE_SOURCE_ID = "openmaptiles"
    private const val STYLE_POI_LABEL_ID = "obsidian-label-poi"

    /** Класи тайла, що не є місцем для людини — той самий список, що в підписах стилю. */
    internal val TILE_SILENT_CLASSES = listOf(
        "parking", "bicycle_parking", "waste_basket", "bollard", "gate", "lift_gate",
        "information", "post", "recycling", "toilets", "drinking_water", "entrance", "shelter",
        "railway", "bus", "ferry_terminal",
    )

    /** Знак для subclass тайла: власний ключ таблиці, інакше знак за замовчуванням. */
    internal fun tileImageId(subclass: String?): String {
        val key = subclass?.trim()?.lowercase().orEmpty()
        return if (key in PoiGlyphTable.bySubcategory) imageId(key) else DEFAULT_PIN_KEY
    }

    /**
     * Магазини, кафе й решта з самого векторного тайла: пакунок і мережа дають
     * їх без Overpass. Знаки ті самі, що в піна, тож мапа не заводить другої
     * мови. Підпис стилю поступається цьому шару, щоб назва не стояла двічі.
     */
    private fun installTilePoiLayer(style: Style, palette: MapSymbolPalette) {
        if (style.getLayer(TILE_POI_LAYER_ID) != null || style.getSource(TILE_SOURCE_ID) == null) return
        val stops = ArrayList<Expression>()
        stops.add(Expression.get("subclass"))
        PoiGlyphTable.bySubcategory.keys.forEach { key ->
            stops.add(Expression.literal(key))
            stops.add(Expression.literal(imageId(key)))
        }
        stops.add(Expression.literal(DEFAULT_PIN_KEY))
        val layer = SymbolLayer(TILE_POI_LAYER_ID, TILE_SOURCE_ID).withProperties(
            PropertyFactory.iconImage(Expression.match(*stops.toTypedArray())),
            PropertyFactory.iconAllowOverlap(false),
            PropertyFactory.iconPadding(MapSymbolSpace.ICON_PADDING_PX),
            PropertyFactory.symbolSortKey(Expression.toNumber(Expression.coalesce(Expression.get("rank"), Expression.literal(100)))),
            PropertyFactory.textField(
                Expression.step(
                    Expression.zoom(), Expression.literal(""),
                    Expression.stop(MAJOR_LABEL_ZOOM, Expression.coalesce(Expression.get("name:uk"), Expression.get("name"))),
                ),
            ),
            PropertyFactory.textFont(MAP_TEXT_FONT),
            PropertyFactory.textSize(10f),
            PropertyFactory.textColor(palette.textInk),
            PropertyFactory.textHaloColor(palette.textHalo),
            PropertyFactory.textHaloWidth(1f),
            PropertyFactory.textOffset(arrayOf(0f, MapSymbolSpace.LABEL_RADIAL_OFFSET)),
            PropertyFactory.textAnchor(org.maplibre.android.style.layers.Property.TEXT_ANCHOR_TOP),
            PropertyFactory.textVariableAnchor(MapSymbolSpace.LABEL_ANCHORS),
            PropertyFactory.textRadialOffset(MapSymbolSpace.LABEL_RADIAL_OFFSET),
            PropertyFactory.textPadding(MapSymbolSpace.LABEL_PADDING_PX),
            PropertyFactory.textMaxWidth(8f),
            PropertyFactory.textOptional(true),
        )
        layer.sourceLayer = "poi"
        layer.minZoom = MAJOR_LABEL_ZOOM
        val silent = TILE_SILENT_CLASSES.flatMap { listOf(Expression.literal(it), Expression.literal(true)) }
        layer.setFilter(
            Expression.all(
                Expression.has("name"),
                Expression.not(Expression.match(*(listOf(Expression.get("class")) + silent + Expression.literal(false)).toTypedArray())),
            ),
        )
        style.addLayer(layer)
        style.getLayer(STYLE_POI_LABEL_ID)?.setProperties(PropertyFactory.visibility(org.maplibre.android.style.layers.Property.NONE))
    }

    private fun installPinLayer(
        style: Style,
        id: String,
        palette: MapSymbolPalette,
        tiers: IntRange,
        minZoom: Float?,
        labelFromZoom: Float,
    ) {
        if (style.getLayer(id) != null) return
        val layer = SymbolLayer(id, POI_SOURCE_ID).withProperties(
            PropertyFactory.iconImage(pinImageExpression()),
            PropertyFactory.iconSize(1.0f),
            // Диски більше не малюються ЗАВЖДИ. На ТРЦ шість тарілок
            // наїжджали одна на одну — H&M на круасани, Sinsay на
            // «Паспортний сервіс», — і купа 26dp-дисків із кільцями читалась
            // як бруд, а не як місця. Тепер тісні розріджуються, а хто саме
            // виживає, вирішує sortKey нижче, а не порядок у джерелі.
            PropertyFactory.iconAllowOverlap(false),
            PropertyFactory.iconIgnorePlacement(false),
            PropertyFactory.iconPadding(MapSymbolSpace.ICON_PADDING_PX),
            PropertyFactory.symbolSortKey(sortKeyExpression()),
            PropertyFactory.iconAnchor(org.maplibre.android.style.layers.Property.ICON_ANCHOR_CENTER),
            PropertyFactory.iconRotationAlignment(org.maplibre.android.style.layers.Property.ICON_ROTATION_ALIGNMENT_VIEWPORT),
            PropertyFactory.iconPitchAlignment(org.maplibre.android.style.layers.Property.ICON_PITCH_ALIGNMENT_VIEWPORT),
            // `label`, а не `name`: близнюк із тим самим іменем за кілька
            // метрів (відділення «Нової Пошти» і його ж поштомат — два
            // законні OSM-обʼєкти) віддає підпис сусідові й лишається
            // самим піном. Імʼя в ознаках ЛИШАЄТЬСЯ — його читає досʼє.
            // Слово приходить пізніше за диск: диск каже «тут є місце» з
            // першого зуму шару, підпис — «і зветься так» лише з labelFromZoom.
            PropertyFactory.textField(
                Expression.step(Expression.zoom(), Expression.literal(""), Expression.stop(labelFromZoom, Expression.get("label"))),
            ),
            PropertyFactory.textFont(MAP_TEXT_FONT),
            PropertyFactory.textSize(10f),
            PropertyFactory.textColor(palette.textInk),
            PropertyFactory.textHaloColor(palette.textHalo),
            PropertyFactory.textHaloWidth(1f),
            // Пара «якір + зсув» лишається ПІДЛОГОЮ і читається лише тоді,
            // коли движок відкинув змінний якір: властивість, якої він не
            // тримає, він викидає мовчки, і підпис ліг би просто на диск.
            PropertyFactory.textOffset(arrayOf(0f, MapSymbolSpace.LABEL_RADIAL_OFFSET)),
            PropertyFactory.textAnchor(org.maplibre.android.style.layers.Property.TEXT_ANCHOR_TOP),
            PropertyFactory.textVariableAnchor(MapSymbolSpace.LABEL_ANCHORS),
            PropertyFactory.textRadialOffset(MapSymbolSpace.LABEL_RADIAL_OFFSET),
            PropertyFactory.textJustify(org.maplibre.android.style.layers.Property.TEXT_JUSTIFY_AUTO),
            PropertyFactory.textPadding(MapSymbolSpace.LABEL_PADDING_PX),
            PropertyFactory.textMaxWidth(8f),
            PropertyFactory.textAllowOverlap(false),
            PropertyFactory.textOptional(true),
            PropertyFactory.visibility(org.maplibre.android.style.layers.Property.VISIBLE),
        )
        if (minZoom != null) layer.minZoom = minZoom
        layer.setFilter(
            Expression.all(
                Expression.not(Expression.has("point_count")),
                Expression.gte(Expression.get("tier"), Expression.literal(tiers.first)),
                Expression.lte(Expression.get("tier"), Expression.literal(tiers.last)),
            ),
        )
        // Під тілами світу: верхній шар розкладається першим, і крамниця-пін
        // витісняла б аптеку-тіло поруч із собою.
        if (style.getLayer(MARKS_BAR_LAYER) != null) style.addLayerBelow(layer, MARKS_BAR_LAYER) else style.addLayer(layer)
    }

    /** iconImage = match(pin) → else match(category) → else default. */
    private fun pinImageExpression(): Expression {
        val catStops = ArrayList<Expression>()
        catStops.add(Expression.get("category"))
        PoiGlyphTable.byCategory.keys.forEach { key ->
            catStops.add(Expression.literal(key))
            catStops.add(Expression.literal(imageId(key)))
        }
        catStops.add(Expression.literal(DEFAULT_PIN_KEY))
        val byCategory = Expression.match(*catStops.toTypedArray())

        val subStops = ArrayList<Expression>()
        subStops.add(Expression.get("pin"))
        PoiGlyphTable.bySubcategory.keys.forEach { key ->
            subStops.add(Expression.literal(key))
            subStops.add(Expression.literal(imageId(key)))
        }
        subStops.add(byCategory) // default of the subcategory match
        return Expression.match(*subStops.toTypedArray())
    }

    /**
     * Стилі, у які піни вже намальовано. Слабкі ключі: стиль живе своїм життям
     * і має право згинути, а знімати з нього прапорець нікому.
     *
     * Питати `style.getImage(name)` замість прапорця дорого не там, де здається:
     * ця ручка витягує картинку з нативного шару назад у Java-Bitmap, тобто 63
     * виділення і копії по кілька десятків кілобайт — щоб лише спитати «чи вона
     * вже там». І питалось це на головному потоці при кожному завантаженні
     * стилю, тобто на кожній зміні підложки й теми.
     *
     * Значення — полярність, у якій піни намальовано: тон ярусу залежить від
     * теми, тож стиль, що пережив перемикання дня й ночі, має перемалюватись,
     * а не лишитись із нічними кільцями на денній землі.
     */
    private val pinnedStyles: MutableMap<Style, Boolean> = java.util.WeakHashMap()

    /** Draw + register every pin bitmap once per style and polarity. */
    private fun registerPinImages(style: Style, context: Context, palette: MapSymbolPalette) {
        if (pinnedStyles.put(style, palette.isDark) == palette.isDark) return
        val density = context.resources.displayMetrics.density
        (PoiGlyphTable.bySubcategory + PoiGlyphTable.byCategory).forEach { (key, family) ->
            style.addImage(imageId(key), PlaceToken.bitmap(family.glyph, family.tier, palette.isDark, density))
        }
        style.addImage(
            DEFAULT_PIN_KEY,
            PlaceToken.bitmap(PoiGlyphTable.DEFAULT.glyph, PoiGlyphTable.DEFAULT.tier, palette.isDark, density),
        )
        GroundTokens.Pile.entries.forEach { pile ->
            style.addImage(GroundTokens.pileImageId(pile, palette.isDark), GroundTokens.pile(pile, palette.isDark, density))
        }
    }

    fun update(style: Style, pois: List<PoiEntity>, visible: Boolean) {
        apply(style, if (visible) featureCollection(pois) else EMPTY_COLLECTION, visible)
    }

    /**
     * The style half of [update]: cheap, and the only half that must touch the
     * MapLibre style (so it must run on the main thread). Callers that already
     * hold a built collection use this so the serialisation below — which is
     * O(pois) and, on a «поруч» flood, thousands of JSONObjects plus a
     * megabyte-scale toString — can be paid on a worker thread instead of
     * under the operator's finger.
     */
    fun apply(style: Style, geoJson: String, visible: Boolean) {
        val source = style.getSourceAs<GeoJsonSource>(POI_SOURCE_ID) ?: return
        val layer = style.getLayer(POI_LAYER_ID) ?: return

        val visibility = if (visible) org.maplibre.android.style.layers.Property.VISIBLE else org.maplibre.android.style.layers.Property.NONE
        layer.setProperties(PropertyFactory.visibility(visibility))
        (PIN_LAYER_IDS + POI_CLUSTER_LAYER_ID + POI_COUNT_LAYER_ID).forEach {
            style.getLayer(it)?.setProperties(PropertyFactory.visibility(visibility))
        }

        source.setGeoJson(if (visible) geoJson else EMPTY_COLLECTION)
    }

    const val EMPTY_COLLECTION = """{"type":"FeatureCollection","features":[]}"""

    /** Синтетичні імена дворових барʼєрів з poiDisplayName. Дорожні
     *  («Пункт пропуску», «Пункт оплати», камери) сюди НЕ входять свідомо. */
    val BARRIER_FURNITURE_NAMES = BatchGSource.BARRIER_FURNITURE_NAMES

    private val GENERIC_NAMES = setOf("parking", "parking_space", "yes", "no", "amenity", "shop", "building", "pitch", "undefined")

    /**
     * Чи має точка право на місце в POI-джерелі. Порожнє/«Unknown»/generic —
     * правило «ніколи не заглушка» від PoiRepository. Дворові барʼєри малює
     * hazard-шар з доречністю (HazardMarkerGate) — тут вони лише повторювали
     * «Ворота» тузином підписів на квартал; це закритий словник наших же
     * синтетичних імен, не дані користувача. Пошук/досьє живляться
     * репозиторієм, не цим шаром.
     */
    internal fun poiRenderable(name: String): Boolean {
        val clean = name.trim()
        if (clean.isBlank() || clean.equals("Unknown", ignoreCase = true) || clean.lowercase() in GENERIC_NAMES) return false
        return clean !in BARRIER_FURNITURE_NAMES
    }

    /**
     * Роди, у яких уже Є власний шар із доречністю. У суцільному шарі місць
     * вони лише повторюють себе тисячами однакових підписів.
     *
     * `ford` — заміряно на національному корпусі 16.09: 25 991 рядок, із них
     * 25 871 з ІДЕНТИЧНИМ іменем «Брід», тобто кожне сьоме місце в країні.
     * Брід — не місце, куди йдуть: це властивість ДОРОГИ, і PHANTOM уже
     * розповідає про нього двічі й доречніше — окремим шаром «Броди» (вмикає
     * оператор, `map_layer_fords`) і рядком обережності маршруту
     * (`RouteCaution.Ford` — «N бродів на маршруті»). Третій показ додав би
     * не знання, а 25 тисяч однакових ромбів там, де довкола більше нічого
     * немає, — тобто саме в селі, де мапа й так найбідніша.
     *
     * Той самий закон, що вже вигнав звідси дворові барʼєри
     * ([BARRIER_FURNITURE_NAMES]): маєш власний шар — не дублюйся в цьому.
     *
     * ОГОЛОШЕНО В `core-data`, і це не охайність. Стелю кадру рахує
     * `PoiDensity.rank` — на крок РАНІШЕ за цей шар. Поки правило стояло лише
     * тут, брід спершу забирав слот під стелею, а потім його ж і викидали:
     * заміряно 17.09 на кадрах із бродом, z13 — 567 слотів із 1 350 у 54
     * переповнених кадрах (42%) пішли на піни, яких ніхто не побачив.
     */
    internal val OWN_LAYER_CATEGORIES: Set<String> =
        local.phantom.companion.core.data.poi.PlaceTiers.OWN_LAYER_CATEGORIES

    /**
     * Чи має місце право на СУЦІЛЬНИЙ шар землі — на відміну від пошуку й
     * досʼє, які живляться репозиторієм і бачать усе.
     *
     * Рід питається окремо від імені навмисно: закритий словник імен ловив би
     * «Брід» і проґавив «Брід (сезонний)», а завтра не впізнав би жодного з
     * них іншою мовою. Рід цієї вади не має.
     */
    internal fun drawableOnGround(poi: PoiEntity): Boolean {
        if (poi.category.trim().lowercase() in OWN_LAYER_CATEGORIES) return false
        return poiRenderable(poi.name)
    }

    /**
     * Ближче за це два однойменні місця — одна вивіска для ока.
     *
     * «Нова Пошта Нова Пошта» (гонтлет-1) — не помилка склеювання рядків: це
     * ДВА законні обʼєкти OSM за чотири метри, відділення й поштомат того ж
     * бренду, з однаковим `name`. Дедуплікації по id тут не досить — id різні,
     * і обидва обʼєкти справжні. Тому не зникає жоден пін: другий лише мовчить.
     */
    private const val LABEL_TWIN_M = 25.0

    /**
     * Хто з однойменних сусідів несе підпис. Перший у списку — і це не сваволя:
     * список приходить уже проранжованим ([local.phantom.companion.core.data.poi.PoiDensity]),
     * тож першим стоїть доречніший, а порядок стабільний між кадрами.
     */
    internal fun labelCarriers(pois: List<PoiEntity>, names: List<String>): BooleanArray {
        val carries = BooleanArray(pois.size)
        if (pois.isEmpty()) return carries
        // Сітка з коміркою в сам поріг: близнюка досить шукати у девʼятьох
        // сусідніх комірках. Без неї пошук за брендом («Нова Пошта» — тисячі
        // однойменних рядків) коштував би квадрата від тисяч.
        val cosRef = kotlin.math.cos(Math.toRadians(pois[0].lat))
        val labelled = HashMap<Long, MutableList<Int>>()
        for (i in pois.indices) {
            val p = pois[i]
            // Комірка — по більшому з двох порогів, інакше сітка не знайде того,
            // кого шукає правило одного місця.
            val gx = kotlin.math.floor(p.lon * 111_320.0 * cosRef / ONE_PLACE_M).toLong()
            val gy = kotlin.math.floor(p.lat * 111_320.0 / ONE_PLACE_M).toLong()
            var twin = false
            for (dx in -1..1) {
                for (dy in -1..1) {
                    val bucket = labelled[cellKey(gx + dx, gy + dy)] ?: continue
                    if (bucket.any { names[it] == names[i] && sameSign(pois[it], p) }) {
                        twin = true
                        break
                    }
                }
                if (twin) break
            }
            if (twin) continue
            carries[i] = true
            labelled.getOrPut(cellKey(gx, gy)) { ArrayList(2) } += i
        }
        return carries
    }

    private fun cellKey(x: Long, y: Long): Long = (x shl 32) xor (y and 0xFFFF_FFFFL)

    /**
     * Що саме напише шар: місце і його підпис, де «» — мовчазний близнюк.
     *
     * Окремо від [featureCollection] не заради краси, а щоб було ЩО міряти:
     * серіалізація йде через `org.json`, а в юніт-тесті це заглушка з android.jar.
     * Тут — усе рішення, там — самі дужки.
     */
    internal fun labelPlan(pois: List<PoiEntity>, latin: Boolean): List<Pair<PoiEntity, String>> {
        val renderable = pois.filter { drawableOnGround(it) }
        val shown = renderable.map { poi ->
            // «Poruba,Otakara Jeremiáše» — чеські назви зупинок клеять кому без пробілу.
            val own = poi.name.trim().replace(GLUED_COMMA, ", ")
            clipLabel(if (latin) latinPlaceName(poi.tags, own) else own)
        }
        val carries = labelCarriers(renderable, shown)
        return renderable.mapIndexed { i, poi -> poi to if (carries[i]) shown[i] else "" }
    }


    /**
     * Скільки метрів навколо однойменного обʼєкта ІНШОГО роду ще той самий обʼєкт.
     *
     * Кадр стенда 03.09: «Independence Square» пʼять разів поспіль на одному екрані.
     * Це не мережа — це ОДНЕ місце, яке OSM тримає кількома обʼєктами різного роду
     * (площа, станція метро, зупинки), і кожен приніс свій підпис. Мережа виглядає
     * інакше: дванадцять «Фора» через тридцять метрів — це дванадцять різних
     * крамниць, і глушити їх не можна (сторож `ланцюжок однойменних…` про це).
     * Тому рід і розрізняє два випадки: однакова категорія — різні місця,
     * різні категорії під одним іменем — одне місце в кількох записах.
     */
    private const val ONE_PLACE_M = 300.0

    /** Категорія в тому вигляді, у якому її бачить око: рід місця, не підвид. */
    private fun kindOf(p: PoiEntity): String = p.category.trim().lowercase()

    /** Чи це та сама вивіска: близнюк упритул, або один обʼєкт, розбитий на роди. */
    internal fun sameSign(a: PoiEntity, b: PoiEntity): Boolean {
        val d = metresApart(a, b)
        if (d < LABEL_TWIN_M) return true
        return kindOf(a) != kindOf(b) && d < ONE_PLACE_M
    }
    /** Рівнокутне наближення: на 25 метрах різниця з гаверсинусом нижча за метр. */
    private fun metresApart(a: PoiEntity, b: PoiEntity): Double {
        val dLat = (a.lat - b.lat) * 111_320.0
        val dLon = (a.lon - b.lon) * 111_320.0 * kotlin.math.cos(Math.toRadians(a.lat))
        return kotlin.math.hypot(dLat, dLon)
    }

    /**
     * Вивіска, а не документ: довше за [LABEL_MAX_CHARS] ріжеться по слову з
     * трикрапкою. «Szymon Frydrych. Specjalista stomatologii zachowawczej i
     * endodoncji.» стояв на землі чотирма рядками (кадр 06.09); повне імʼя
     * лишається в ознаках і в досʼє.
     */
    internal fun clipLabel(name: String): String {
        // Рахує PeerName, а не `.length`: у продукті одне числення довжини імені,
        // і воно по графемах. «сімʼя» — один знак для ока й пʼять для UTF-16;
        // різати за другим означає рубати посеред літери.
        val units = PeerName.graphemes(name)
        if (units.size <= LABEL_MAX_CHARS) return name
        // Включно з граничним індексом — так само, як робив початковий
        // lastIndexOf(' ', LABEL_MAX_CHARS). На одиницю коротше різало б
        // «Szymon Frydrych. Specjalista» посеред слова.
        val space = units.take(LABEL_MAX_CHARS + 1).indexOfLast { it == " " }
        val cut = if (space >= LABEL_MAX_CHARS / 2) space else LABEL_MAX_CHARS
        return units.take(cut).joinToString("")
            .trimEnd(' ', ',', '.', ';', ':', '-', '–', '—') + "…"
    }

    /**
     * Вхід — меблі будинку, не місце: до нього немає сенсу йти, доки не бачиш
     * самого будинку. Вхід у метро — виняток, він і є місцем призначення.
     */
    internal fun isFurniture(poi: PoiEntity): Boolean {
        if (poi.subcategory == "subway_entrance") return false
        return poi.tags.containsKey("entrance") || poi.category == "entrance" || poi.subcategory == "entrance"
    }

    /** Ярус у числі — див. TIER_*; шар обирає свій діапазон фільтром. */
    internal fun tierOf(poi: PoiEntity): Int =
        if (isFurniture(poi)) TIER_FURNITURE else PoiGlyphTable.resolve(poi).tier.ordinal

    /**
     * Pure: the POI FeatureCollection, safe to build off the main thread.
     *
     * [latin] — телефон каже, що його власник не читає кирилиці. Тоді підпис
     * піна бере `name:en`/`int_name` з тегів місця, якщо мапери їх написали;
     * `name` у сутності лишається українським, бо ним живуть пошук і досʼє.
     */
    fun featureCollection(pois: List<PoiEntity>, latin: Boolean = false): String {
        // Real OSM data at country scale is guaranteed to contain names/tags
        // with quotes, backslashes or stray control characters somewhere in
        // a batch of hundreds-to-thousands of POIs. Hand-built JSON strings
        // (previously: manual string interpolation escaping only `"` and
        // `\n` in `name`, and NOT escaping `subcategory` at all) let a
        // single malformed feature corrupt the whole FeatureCollection
        // string, silently zeroing out EVERY POI on the map even though the
        // fetch itself succeeded — exactly the "POIs load but nothing is
        // ever tappable" bug. `org.json` guarantees valid escaping for every
        // field regardless of content.
        val featureArray = org.json.JSONArray()
        labelPlan(pois, latin).forEach { (poi, label) ->
            val properties = org.json.JSONObject()
                .put("id", poi.id)
                .put("name", poi.name.trim())
                .put("label", label)
                .put("category", poi.category)
                .put("subcategory", poi.subcategory ?: "")
                // Знак і черга на розрідження беруться з ПІНА, а не з
                // підкатегорії: навіс мусить упасти на «просто місце», але
                // обробник дотику має отримати справжній тег.
                .put("pin", PoiGlyphTable.pinSubcategory(poi) ?: "")
                .put("tier", tierOf(poi))
            val geometry = org.json.JSONObject()
                .put("type", "Point")
                .put("coordinates", org.json.JSONArray().put(poi.lon).put(poi.lat))
            val feature = org.json.JSONObject()
                .put("type", "Feature")
                .put("properties", properties)
                .put("geometry", geometry)
            featureArray.put(feature)
        }
        return org.json.JSONObject()
            .put("type", "FeatureCollection")
            .put("features", featureArray)
            .toString()
    }
}
