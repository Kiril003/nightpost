package local.phantom.companion.feature.stream.ui.map

import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.VectorSource

/**
 * **Факти, які тайл уже везе, а мапа досі мовчала.**
 *
 * Знято з TileJSON постачальника (17.08.2026): шар `transportation` несе
 * `access`, `bicycle`, `brunnel`, `class`, `expressway`, `foot`, `horse`,
 * `layer`, `mtb_scale`, `network`, `oneway`, `ramp`, `service`, `subclass`,
 * **`surface`**, `toll`; шар `waterway` і `water` — ще й **`intermittent`**.
 *
 * Стиль постачальника читає з усього цього рівно пʼять полів. Решта їде на
 * телефон, лежить у кожному офлайн-пакеті, займає місце — і не відповідає ні на
 * що. Тут вона починає відповідати, і саме на ті питання, які ставить екіпаж:
 *
 *   «під колесами асфальт чи ґрунт?»  → [ROAD_UNPAVED_LAYER]
 *   «мене сюди пустять?»              → [ROAD_CLOSED_LAYER]
 *   «ця синя лінія — справді вода?»   → [installWaterTruth]
 *
 * Нуль мережі, нуль нових даних, працює в офлайні. Найдешевша чесність, яка в
 * цьому продукті взагалі можлива.
 *
 * ⚠ Межа: тайл каже лише те, що є в OSM. Дорога без `surface` не «асфальт» —
 * вона «невідомо», і жоден із цих шарів її не чіпає. Мовчання не отримує
 * сходинки — інакше мапа обіцяла б покриття, якого не міряла.
 */
internal fun installGroundFacts(style: Style, isDark: Boolean) {
    // Ґрунтівка, замок і пересихаюча вода — рідні шари Обсидіана
    // (`obsidian-road-unpaved`, `obsidian-road-closed`, `obsidian-water-intermittent`).
    if (style.speaksObsidian()) return
    val source = style.sources.firstOrNull { it is VectorSource }?.id ?: return
    val p = groundPalette(isDark)

    installUnpavedRoads(style, source, p)
    installClosedRoads(style, source, p)
    installWaterTruth(style, source)
}

/**
 * Ґрунтівка виглядає ґрунтівкою.
 *
 * Виміряно по українському витягу: `surface` несуть 593 209 обʼєктів, а
 * `highway=track` — 623 830. Тобто про більшість польових доріг країни дані
 * ЩОСЬ кажуть, і досі мапа малювала їх точно як асфальт.
 *
 * Малюється пунктиром ПОВЕРХ дороги, а не замість неї: сама дорога лишається
 * тією ж лінією тієї ж ширини — змінюється тільки її поверхня. Так око читає
 * «та сама дорога, інше покриття», а не «інша дорога».
 */
private fun installUnpavedRoads(style: Style, source: String, p: GroundPalette) {
    if (style.getLayer(ROAD_UNPAVED_LAYER) != null) return
    val layer = LineLayer(ROAD_UNPAVED_LAYER, source).withSourceLayer(TRANSPORTATION)
    layer.setFilter(
        Expression.all(
            ROADS_THAT_MATTER,
            Expression.has(SURFACE),
            Expression.match(
                *(
                    listOf(Expression.get(SURFACE)) +
                        UNPAVED.flatMap { listOf(Expression.literal(it), Expression.literal(true)) } +
                        listOf(Expression.literal(false))
                    ).toTypedArray(),
            ),
        ),
    )
    layer.setProperties(
        org.maplibre.android.style.layers.PropertyFactory.lineColor(p.roadUnpavedInk),
        org.maplibre.android.style.layers.PropertyFactory.lineCap(
            org.maplibre.android.style.layers.Property.LINE_CAP_ROUND,
        ),
        // Коротка риска з великим просвітом: на відстані витягнутої руки це
        // читається як «сипучка», а не як пунктир розмітки.
        org.maplibre.android.style.layers.PropertyFactory.lineDasharray(arrayOf(0.6f, 1.4f)),
        org.maplibre.android.style.layers.PropertyFactory.lineWidth(
            Expression.interpolate(
                Expression.linear(), Expression.zoom(),
                Expression.stop(12, 0.8f),
                Expression.stop(16, 1.8f),
                Expression.stop(20, 3.2f),
            ),
        ),
        org.maplibre.android.style.layers.PropertyFactory.lineOpacity(
            Expression.interpolate(
                Expression.linear(), Expression.zoom(),
                Expression.stop(13, 0f),
                Expression.stop(14, 0.7f),
            ),
        ),
    )
    addAboveRoads(style, layer)
}

/**
 * Дорога, якою тебе не пустять.
 *
 * `access=no` і `access=private` — це шлагбаум, ворота або чужий двір. По
 * країні барʼєрів 810 842, і половина з них стоїть саме на таких проїздах.
 * Маршрут, який туди веде, — провалений маршрут, а мапа про це мовчала.
 *
 * Малюється поперечними рисками — знаком «перекрито», а не кольором: колір на
 * тонкій лінії при сонці не читається, а риска читається формою.
 */
private fun installClosedRoads(style: Style, source: String, p: GroundPalette) {
    if (style.getLayer(ROAD_CLOSED_LAYER) != null) return
    val layer = LineLayer(ROAD_CLOSED_LAYER, source).withSourceLayer(TRANSPORTATION)
    layer.setFilter(
        Expression.all(
            ROADS_THAT_MATTER,
            Expression.has(ACCESS),
            Expression.match(
                *(
                    listOf(Expression.get(ACCESS)) +
                        CLOSED.flatMap { listOf(Expression.literal(it), Expression.literal(true)) } +
                        listOf(Expression.literal(false))
                    ).toTypedArray(),
            ),
        ),
    )
    layer.setProperties(
        org.maplibre.android.style.layers.PropertyFactory.lineColor(p.roadClosedInk),
        org.maplibre.android.style.layers.PropertyFactory.lineCap(
            org.maplibre.android.style.layers.Property.LINE_CAP_BUTT,
        ),
        // Густа коротка «драбина» — знак заборони, який ні з чим не сплутати.
        org.maplibre.android.style.layers.PropertyFactory.lineDasharray(arrayOf(0.35f, 0.9f)),
        org.maplibre.android.style.layers.PropertyFactory.lineWidth(
            Expression.interpolate(
                Expression.linear(), Expression.zoom(),
                Expression.stop(13, 1.6f),
                Expression.stop(18, 4.5f),
            ),
        ),
        org.maplibre.android.style.layers.PropertyFactory.lineOpacity(
            Expression.interpolate(
                Expression.linear(), Expression.zoom(),
                Expression.stop(14, 0f),
                Expression.stop(15, 0.7f),
            ),
        ),
    )
    addAboveRoads(style, layer)
}

/**
 * Синя лінія, під якою немає води.
 *
 * Виміряно по київському витягу: **37,7 % струмків і 35,3 % річок мають
 * `brunnel=tunnel`** — вони течуть у трубі під землею. Мапа малювала їх точно
 * як відкриту воду: Либідь, узята в колектор, виглядала річкою, яку видно й
 * можна перейти. Плюс `intermittent` — русла, що пересихають.
 *
 * Це не прикраса, а виправлення брехні, тому воно робиться хірургією по
 * ЧУЖИХ шарах: підземне не малюється зовсім, пересихаюче — пунктиром.
 * Обидва стани лишаються на мапі як факт, але жоден більше не вдає постійну
 * відкриту воду.
 */
private fun installWaterTruth(style: Style, source: String) {
    val underground = Expression.eq(Expression.get(BRUNNEL), Expression.literal("tunnel"))
    val onlyOpen = Expression.not(underground)
    style.layers.forEach { layer ->
        runCatching {
            // `when`, а не пара `is`-перевірок із раннім виходом: Kotlin не
            // звужує тип через `!is A && !is B`, і саме на цьому компілятор
            // втрачав `sourceLayer`.
            when (layer) {
                is LineLayer -> {
                    if (layer.sourceLayer != WATERWAY && layer.sourceLayer != WATER) return@runCatching
                    val existing: Expression? = layer.filter
                    layer.setFilter(if (existing != null) Expression.all(existing, onlyOpen) else onlyOpen)
                }
                is org.maplibre.android.style.layers.FillLayer -> {
                    if (layer.sourceLayer != WATERWAY && layer.sourceLayer != WATER) return@runCatching
                    val existing: Expression? = layer.filter
                    layer.setFilter(if (existing != null) Expression.all(existing, onlyOpen) else onlyOpen)
                }
                else -> Unit
            }
        }
    }

    // Пересихаюче русло — власним пунктиром, бо чужий шар його вже не малює
    // окремо, а «немає лінії» читалось би як «немає русла».
    if (style.getLayer(WATER_INTERMITTENT_LAYER) == null) {
        val layer = LineLayer(WATER_INTERMITTENT_LAYER, source).withSourceLayer(WATERWAY)
        layer.setFilter(
            Expression.all(
                Expression.eq(Expression.get(INTERMITTENT), Expression.literal(1)),
                Expression.not(
                    Expression.eq(Expression.get(BRUNNEL), Expression.literal("tunnel")),
                ),
            ),
        )
        layer.setProperties(
            org.maplibre.android.style.layers.PropertyFactory.lineColor(
                groundPalette(isDark = false).waterInk,
            ),
            org.maplibre.android.style.layers.PropertyFactory.lineDasharray(arrayOf(2f, 1.6f)),
            org.maplibre.android.style.layers.PropertyFactory.lineWidth(
                Expression.interpolate(
                    Expression.linear(), Expression.zoom(),
                    Expression.stop(12, 0.8f),
                    Expression.stop(17, 2.2f),
                ),
            ),
            org.maplibre.android.style.layers.PropertyFactory.lineOpacity(0.75f),
        )
        addAboveRoads(style, layer)
    }
}

/**
 * Кладе шар над дорогами, але під підписами: факт про дорогу мусить лежати на
 * ній, а не поверх її назви.
 */
private fun addAboveRoads(style: Style, layer: Layer) {
    val firstSymbol = style.layers.firstOrNull { it is SymbolLayer }
    if (firstSymbol != null) style.addLayerBelow(layer, firstSymbol.id) else style.addLayer(layer)
}

internal const val ROAD_UNPAVED_LAYER = "phantom-road-unpaved"
internal const val ROAD_CLOSED_LAYER = "phantom-road-closed"
internal const val WATER_INTERMITTENT_LAYER = "phantom-water-intermittent"

private const val TRANSPORTATION = "transportation"
private const val WATERWAY = "waterway"
private const val WATER = "water"
private const val SURFACE = "surface"
private const val ACCESS = "access"
private const val BRUNNEL = "brunnel"
private const val INTERMITTENT = "intermittent"

/**
 * OpenMapTiles нормалізує `surface` до `paved`/`unpaved`, але не всюди: у
 * витягах трапляються й сирі значення OSM. Перелічені обидва роди — інакше
 * половина ґрунтівок країни лишилась би без сходинки через те, що редактор
 * написав «gravel», а не «unpaved».
 */
private val UNPAVED = listOf(
    "unpaved", "gravel", "fine_gravel", "compacted", "dirt", "earth",
    "ground", "sand", "mud", "grass", "pebblestone", "woodchips",
)

/** `access` у схемі тайлів — рядок; «немає» і «приватне» — різні слова, але
 *  для водія однаковий наслідок: сюди не проїхати без домовленості. */
private val CLOSED = listOf("no", "private")

/**
 * Де заборона і покриття справді є новиною.
 *
 * Перша редакція фільтрувала лише за тегом — і в торговому центрі засипала
 * рисками ВСЮ стоянку: кожен паркувальний проїзд має `access=private`, і це
 * там норма, а не факт. Власник побачив «коричневі полоси» по всьому екрану
 * рівно через це.
 *
 * `service` (проїзди, двори, паркувальні ряди) і `path`/`footway` виключені:
 * там приватність і ґрунт — стан за замовчуванням. Лишились класи, якими
 * справді їдуть, і для яких «ґрунтівка» чи «не пустять» міняє рішення.
 */
private val ROADS_THAT_MATTER: Expression = Expression.match(
    *(
        listOf(Expression.get("class")) +
            listOf("motorway", "trunk", "primary", "secondary", "tertiary", "minor", "track")
                .flatMap { listOf(Expression.literal(it), Expression.literal(true)) } +
            listOf(Expression.literal(false))
        ).toTypedArray(),
)
