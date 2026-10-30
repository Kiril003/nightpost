package local.phantom.companion.feature.stream.ui.map

import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.BackgroundLayer
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer

/**
 * **Ґрунт — наш.**
 *
 * Геометрію землі везе чужий сервер (`tiles.openfreemap.org`), і цього поки не
 * змінити. Але вектор — це ДАНІ, а не картина: колір, вага і мова кожного шару
 * вирішуються тут, на пристрої, після завантаження стилю. Тож картографія
 * власна, навіть доки тайли чужі.
 *
 * До цього файлу ґрунт малювався так: світла основа не чіпалася ВЗАГАЛІ
 * (`if (!isDark) return` у [applyRoadHierarchy]'s ancestor), а темна діставала
 * три заміни кольору за підрядком в імені шару — вода в `#0F0F13`, «парк або
 * landuse» в `#15151A`. На чорному тлі `#0C0C0C` це означає, що річка, ліс,
 * поле й промзона мали ОДИН колір із землею: структури не було ніякої. Власник
 * назвав це точно — «ніби у будинка фундамент залили і так і покинули».
 *
 * Три правила, з яких складається все нижче:
 *
 * 1. **Колір веде поле тайла, а не імʼя шару.** `id.contains("water")` ловить
 *    `water`, `waterway`, `water_name` і `watershed` разом, і не ловить
 *    `ocean`. Тут скрізь `match(get("class"), …)` — те, що написано в даних.
 * 2. **Обидві полярності — рівні.** Жодного раннього виходу для дня.
 * 3. **Одна назва, українською.** Ліберті пише `{name:latin}\n{name:nonlatin}`,
 *    тобто «Chornobyl Чорнобиль» у два рядки на кожному місті. Підпис стає
 *    одним: `name:uk`, далі `name`, далі `name:en`.
 *
 * Прохід іде ПЕРШИМ у [MapCanvas]'s style callback — до `installHillshade` і
 * решти. Це не косметика порядку: у ту мить у стилі лежать ЛИШЕ чужі шари,
 * тож жоден власний шар (маршрут, пін, шари ППО, POI) сюди не потрапляє і не
 * може бути перефарбований наосліп.
 */
/**
 * Чи говорить стиль нашою мовою від народження.
 *
 * ATLAS-1: `asset://styles/obsidian-*.json` уже несе і колір, і кегель, і
 * шари, яких у вендора нема. Чотири проходи перефарбування ([applyGroundCartography],
 * [applyLabelVoice], [installGroundBackfill], [installGroundFacts]) над ним не
 * потрібні — і шкідливі: вони писали б поверх власної картографії числами,
 * підібраними під чужу.
 *
 * Питання ставиться СТИЛЮ, а не налаштуванням: інакше кожен із чотирьох
 * проходів мусив би сам знати про `TileProviderId`, `MapBasemap` і полярність,
 * і рівно один із них колись про це забув би. Тло Обсидіана має власний id
 * (контракт `tools/obsidian/Ids.kt`), тож стиль відповідає сам за себе.
 */
internal fun Style.speaksObsidian(): Boolean = getLayer(OBSIDIAN_GROUND_LAYER) != null

internal fun applyGroundCartography(style: Style, isDark: Boolean) {
    if (style.speaksObsidian()) return
    val p = groundPalette(isDark)
    val scaleRoads = style.getSource(TACTICAL_ROADS_MARK) == null

    style.layers.forEach { layer ->
        // Наші шари читають ті самі source-layer, що й чужі: тінь будівлі
        // (`phantom-building-shadow`) прийшла б сюди як звичайний `building` і
        // вийшла б звідси непрозорим другим будинком поруч зі справжнім.
        if (layer.id.startsWith(PHANTOM_LAYER_PREFIX)) return@forEach
        // Один зіпсований шар не має права лишити людину без мапи: чужий стиль
        // може принести шар, що не тримає властивість, яку ми йому даємо.
        runCatching {
            when (layer) {
                is BackgroundLayer ->
                    layer.setProperties(PropertyFactory.backgroundColor(p.land))
                is FillLayer -> paintFill(layer, p)
                is LineLayer -> paintLine(layer, p, scaleRoads)
                is SymbolLayer -> paintSymbol(layer, p)
                is RasterLayer -> paintRelief(layer, isDark)
                is FillExtrusionLayer -> hideVendorVolumes(layer)
                else -> Unit
            }
        }
    }

    if (scaleRoads) {
        style.addSource(org.maplibre.android.style.sources.GeoJsonSource(TACTICAL_ROADS_MARK, EMPTY_FC))
    }
}

/* ── Полотно ─────────────────────────────────────────────────────────────── */

/**
 * Непрозорість тут не косметика, а умова того, що колір узагалі буде видно.
 *
 * Виміряно у стилі Liberty: `landcover_wood` малюється на `fill-opacity` 0.4,
 * `landcover_grass` на 0.3, `park` на 0.7. Числа підібрані під бежеве тло
 * `#f8f4f0`, де 40 % зеленого ще читаються. Над нашою землею `#0C0E13` те саме
 * означає, що ліс виходить на дві пʼятих шляху від чорного до темно-зеленого,
 * тобто його немає. Земля має свій колір на повну, а напівпрозорість лишається
 * тим шарам, що лежать ПОВЕРХ неї — тривозі, штриху, маршруту.
 */
private const val GROUND_OPAQUE = 1f

private fun paintFill(layer: FillLayer, p: GroundPalette) {
    when (layer.sourceLayer) {
        "water" -> layer.setProperties(
            PropertyFactory.fillOpacity(GROUND_OPAQUE),
            PropertyFactory.fillColor(
                match(
                    "class",
                    "ocean" to p.waterDeep,
                    "lake" to p.water,
                    "river" to p.water,
                    "swimming_pool" to p.water,
                    default = p.water,
                ),
            ),
        )

        "landcover" -> layer.setProperties(
            PropertyFactory.fillOpacity(GROUND_OPAQUE),
            PropertyFactory.fillColor(
                match(
                    "class",
                    "wood" to p.wood,
                    "forest" to p.wood,
                    "grass" to p.grass,
                    "meadow" to p.grass,
                    "farmland" to p.farmland,
                    "wetland" to p.wetland,
                    "sand" to p.sand,
                    "rock" to p.rock,
                    "ice" to p.ice,
                    default = p.grass,
                ),
            ),
        )

        "landuse" -> {
            layer.setProperties(
                PropertyFactory.fillOpacity(GROUND_OPAQUE),
                PropertyFactory.fillColor(
                    match(
                        "class",
                        "residential" to p.residential,
                        "suburb" to p.residential,
                        "neighbourhood" to p.residential,
                        "commercial" to p.commercial,
                        "retail" to p.commercial,
                        "industrial" to p.industrial,
                        "garages" to p.industrial,
                        "quarry" to p.rock,
                        "railway" to p.industrial,
                        "cemetery" to p.cemetery,
                        "hospital" to p.hospital,
                        "school" to p.school,
                        "university" to p.school,
                        "college" to p.school,
                        "kindergarten" to p.school,
                        "stadium" to p.pitch,
                        "pitch" to p.pitch,
                        "playground" to p.pitch,
                        "track" to p.pitch,
                        default = p.residential,
                    ),
                ),
            )
            // Вендор ховає тінт забудови на z12, а форма поселення на z13+
            // тримається на самих будинках. Знімок села на z16 — це рівно він:
            // земля в селі й поле за селом одного кольору.
            if (layer.id == VENDOR_LANDUSE_RESIDENTIAL) layer.maxZoom = VENDOR_GATE_OPEN
        }

        "park" -> layer.setProperties(
            PropertyFactory.fillOpacity(GROUND_OPAQUE),
            PropertyFactory.fillColor(p.wood),
        )

        "aeroway" -> layer.setProperties(
            PropertyFactory.fillOpacity(GROUND_OPAQUE),
            PropertyFactory.fillColor(p.aeroway),
        )

        BUILDING_SOURCE_LAYER -> {
            layer.setProperties(
                PropertyFactory.fillColor(p.building),
                // Вендорський обвід футпринта — світло-бежевий на обох
                // полярностях; із відкритими воротами нижче він світився б
                // навколо кожного будинку на нічній землі.
                PropertyFactory.fillOutlineColor(p.buildingEdge),
                // Будинок був ЄДИНИМ класом землі без власної непрозорості —
                // усі інші отримують GROUND_OPAQUE, а тут лишалась вендорська
                // рампа по зуму, майже нульова на нижніх рівнях. Разом із
                // відкритими воротами (z24) це дало прозорі будинки скрізь,
                // окрім тих кількох кварталів, де їх домальовує Кришталеве
                // місто: «очі не можуть зрозуміти, в який бік вони дивляться».
                PropertyFactory.fillOpacity(GROUND_OPAQUE),
            )
            // Вікно 13..14 лишало місто без тіла рівно там, де по ньому працюють.
            layer.maxZoom = VENDOR_GATE_OPEN
        }
    }
}

/**
 * Natural Earth — єдиний растр у чужому стилі, і на нічній землі він отруйний.
 *
 * Liberty кладе `ne2_shaded` поверх тла з непрозорістю 0.6 на z0, згасаючи до
 * 0.1 на z6. Це кольорове зображення суходолу, намальоване під бежеве тло: на
 * нашій нічній землі воно світилося б бляклим піщаним материком, а вся робота
 * з палітрою нижче була б непомітна під ним.
 *
 * Не викидаємо: на малих наближеннях це єдиний рельєф, який у мапи взагалі є
 * (власний hillshade — платний і вимкнений до z14). Знебарвлюємо, притлумлюємо
 * і лишаємо як тінь під землею — рельєф є, кольору чужої палітри нема.
 */
private fun paintRelief(layer: RasterLayer, isDark: Boolean) {
    if (!isDark) return
    layer.setProperties(
        PropertyFactory.rasterSaturation(-1f),
        PropertyFactory.rasterBrightnessMax(0.34f),
        PropertyFactory.rasterOpacity(
            Expression.interpolate(
                Expression.exponential(1.5f),
                Expression.zoom(),
                Expression.stop(0, 0.30f),
                Expression.stop(6, 0.05f),
            ),
        ),
    )
}

private fun paintLine(layer: LineLayer, p: GroundPalette, scaleRoads: Boolean) {
    when (layer.sourceLayer) {
        "waterway" -> layer.setProperties(PropertyFactory.lineColor(p.waterway))

        // Обвід заповідника. Заливка лежить найнижчим шаром стилю (#2 із 111),
        // тож усередині парку далі малюються села й дороги — межа мусить бути
        // видимою лінією, інакше «де починається ліс» доводиться вгадувати.
        "park" -> layer.setProperties(PropertyFactory.lineColor(p.woodEdge))

        // Смуга і руліжка. Не дорога і не рейка: власного щабля не мають, але
        // фарбувати їх зеленню заповідника чужий стиль теж не повинен.
        "aeroway" -> layer.setProperties(PropertyFactory.lineColor(p.roadLocal))

        // Кордон — не декор. Державний іде окремою вагою від обласного, бо це
        // різні за наслідками лінії, а чужий стиль малює їх однаково тонко.
        "boundary" -> {
            layer.setProperties(
                PropertyFactory.lineColor(
                    Expression.step(
                        Expression.toNumber(Expression.get("admin_level")),
                        Expression.color(p.boundaryCountry),
                        Expression.stop(3, Expression.color(p.boundaryRegion)),
                    ),
                ),
            )
            layer.setFilter(narrowed(layer.filter, RECOGNISED_BORDER))
        }

        BUILDING_SOURCE_LAYER -> layer.setProperties(PropertyFactory.lineColor(p.buildingEdge))

        "transportation" -> paintRoad(layer, p, scaleRoads)
    }
}

private fun paintSymbol(layer: SymbolLayer, p: GroundPalette) {
    speakUkrainian(layer)
    if (layer.sourceLayer == "place") layer.setFilter(narrowed(layer.filter, NOT_OCCUPATION_REGION))

    // Самі вулиці приходять із z13.5, а їхні назви вендор пускав із z15: півтора
    // зуму безіменних ліній рівно там, де по них їдуть.
    if (layer.id == VENDOR_HIGHWAY_NAME_MINOR) layer.minZoom = STREET_NAME_MIN_ZOOM
    // Номери будинків (кадр A52 26.09, Острава): з z14.5 і з білим ореолом вони
    // брудно лягали на сірі дахи. Тепер — лише з z17.5, рідше, тихим чорнилом
    // без ореолу: номер читається на даху сам, розрив йому не потрібен.
    if (layer.sourceLayer == "housenumber") {
        layer.minZoom = HOUSENUMBER_MIN_ZOOM
        layer.setProperties(
            PropertyFactory.textSize(11.0f),
            PropertyFactory.textFont(MAP_TEXT_FONT),
            PropertyFactory.textPadding(HOUSENUMBER_PADDING),
            PropertyFactory.textColor(p.poiInk),
            PropertyFactory.textHaloWidth(0f),
        )
        return
    }
    // Одна вулиця — один-два підписи на екран, а не пʼять «Studentská» поспіль.
    if (layer.sourceLayer == "transportation_name") {
        layer.setProperties(PropertyFactory.symbolSpacing(STREET_NAME_SPACING))
    }

    val ink = when (layer.sourceLayer) {
        "place" -> p.placeInk
        "water_name" -> p.waterInk
        "transportation_name" -> p.roadNameInk
        "mountain_peak" -> p.placeInk
        "poi" -> p.poiInk
        else -> return
    }
    layer.setProperties(
        PropertyFactory.textColor(ink),
        PropertyFactory.textHaloColor(p.placeHalo),
        // Ореол — не тінь, а розрив: підпис міста лежить на дорозі, лісі й воді
        // водночас, і без нього читається лише на одному з трьох.
        PropertyFactory.textHaloWidth(1.4f),
        PropertyFactory.textHaloBlur(0.4f),
    )
}

/* ── Крим — Україна ──────────────────────────────────────────────────────── */

/**
 * Лінія з `disputed` — чиясь заява, а не кордон: у Криму це лінія окупації
 * через Перекоп і «морський кордон РФ» навколо півострова. Liberty малює її
 * пунктиром `boundary_disputed`, тобто державним кордоном по Перекопу.
 */
internal val RECOGNISED_BORDER: Expression =
    Expression.neq(Expression.get("disputed"), Expression.literal(1))

/** Тайл несе дві точки `class=state` над півостровом: АР Крим і окупаційну. */
internal val NOT_OCCUPATION_REGION: Expression = Expression.all(
    Expression.neq(Expression.get("name"), Expression.literal("\u0420\u0435\u0441\u043f\u0443\u0431\u043b\u0438\u043a\u0430 \u041a\u0440\u044b\u043c")),
    Expression.neq(Expression.get("name:en"), Expression.literal("Republic of Crimea")),
)

internal fun narrowed(existing: Expression?, rule: Expression): Expression =
    if (existing != null) Expression.all(existing, rule) else rule

/* ── Мова підпису ────────────────────────────────────────────────────────── */

/**
 * Одна назва замість двох.
 *
 * Ліберті ставить у `text-field` два імені підряд («Chornobyl Чорнобиль»,
 * «ZHYTOMYR OBLAST ЖИТОМИРСЬКА ОБЛАСТЬ»), бо стиль розрахований на весь світ і
 * не знає, якою мовою читає той, хто дивиться. Ми знаємо.
 *
 * Переписуються ЛИШЕ ті шари, чий підпис уже посилається на імʼя — див.
 * [labelsAName]. Без цієї перевірки щити доріг (`{ref}`) і номери будинків
 * (`{housenumber}`) почали б писати назву вулиці замість числа.
 */
private fun speakUkrainian(layer: SymbolLayer) {
    if (!labelsAName(currentTextField(layer))) return
    layer.setProperties(
        PropertyFactory.textField(
            Expression.coalesce(
                Expression.get("name:uk"),
                Expression.get("name"),
                Expression.get("name:en"),
                Expression.literal(""),
            ),
        ),
        // Разом із полем — і стек шрифту. Підпис без нього успадковує
        // замовчування MapLibre, якого сервер гліфів OpenFreeMap не тримає:
        // кожен діапазон дає 404, і текст просто не малюється — без збою, без
        // помилки, самі порожні місця. Це вже ловили на виїзді 17.07.
        PropertyFactory.textFont(MAP_TEXT_FONT),
    )
}

private fun currentTextField(layer: SymbolLayer): String? {
    val field = layer.textField
    // `value` приходить як Any?: у нових стилях text-field це вже не рядок, а
    // `formatted`. Нам треба лише спитати, чи згадано в ньому імʼя.
    return if (field.isExpression) field.expression?.toString() else field.value?.toString()
}

/**
 * Чи цей підпис узагалі про імʼя. Виділено окремо, бо це єдине місце, де
 * помилка не видно на екрані одразу: щит із назвою вулиці замість номера
 * дороги виглядає як підпис, просто неправильний.
 */
internal fun labelsAName(textField: String?): Boolean =
    textField != null && textField.contains("name")

/* ── Палітра ─────────────────────────────────────────────────────────────── */

/**
 * Кожен колір землі, обидві полярності.
 *
 * Це не тема застосунку: скло, чорнило й акцент лишаються за
 * `LocalPhantomTheme`. Земля має власну палітру, бо її задача інша — не бути
 * гарною поверхнею під текстом, а дати оку розрізнити воду від лісу, поле від
 * промзони й національну дорогу від польової, з-під шару накладених значків,
 * удень і крізь червоне світло.
 */
internal data class GroundPalette(
    val land: Int,
    val water: Int,
    val waterDeep: Int,
    val waterway: Int,
    val wood: Int,
    val woodEdge: Int,
    val grass: Int,
    val farmland: Int,
    val wetland: Int,
    val sand: Int,
    val rock: Int,
    val ice: Int,
    val residential: Int,
    val commercial: Int,
    val industrial: Int,
    val cemetery: Int,
    val hospital: Int,
    val school: Int,
    val pitch: Int,
    val aeroway: Int,
    val building: Int,
    val buildingEdge: Int,
    val roadNational: Int,
    val roadRegional: Int,
    val roadLocal: Int,
    val roadCasing: Int,
    val roadCasingNational: Int,
    val rail: Int,
    val path: Int,
    val boundaryCountry: Int,
    val boundaryRegion: Int,
    val placeInk: Int,
    val placeHalo: Int,
    val waterInk: Int,
    val roadNameInk: Int,
    val poiInk: Int,
    /**
     * Покриття, яке тайл ЗНАЄ і про яке досі мовчав. Обидва — не декор, а
     * відповідь: «під колесами сипучка» і «сюди не пустять».
     *
     * Виміряно методом WCAG проти власної землі й проти білої серцевини дороги,
     * на якій вони лежать: ґрунт 4.50:1 і 4.99:1 удень, 6.08:1 уночі; заборона
     * 5.13:1 і 5.69:1 удень, 6.11:1 уночі. Обидва мусили пройти ОБИДВА пороги —
     * знак, видний на землі й невидний на самій дорозі, не знак.
     */
    val roadUnpavedInk: Int,
    val roadClosedInk: Int,
)

internal fun groundPalette(isDark: Boolean): GroundPalette = if (isDark) DARK_GROUND else LIGHT_GROUND

/**
 * Ніч.
 *
 * Земля синьо-сіра, а не чорна: на чистому чорному будь-який власний шар
 * світиться як неон, і мапа перестає бути землею під роботою. Вода тримає
 * помітну синь — на попередній палітрі `#0F0F13` вона була темніша за землю
 * навколо, тобто Дніпро на нічній мапі не існував.
 *
 * Чорнило доріг читається з [roadInk] — не передруковане сюди, бо саме воно
 * виміряне проти цих ґрунтів у MapRoadHierarchyTest.
 */
private val DARK_GROUND = GroundPalette(
    land = 0xFF0C0E13.toInt(),
    water = 0xFF0E2438.toInt(),
    waterDeep = 0xFF0A1C2C.toInt(),
    waterway = 0xFF1B4463.toInt(),
    wood = 0xFF11251B.toInt(),
    woodEdge = 0xFF1E3C2B.toInt(),
    grass = 0xFF13251A.toInt(),
    farmland = 0xFF17191A.toInt(),
    wetland = 0xFF13262A.toInt(),
    sand = 0xFF241F17.toInt(),
    rock = 0xFF1B1B1F.toInt(),
    ice = 0xFF17222E.toInt(),
    residential = 0xFF141821.toInt(),
    commercial = 0xFF1A1720.toInt(),
    industrial = 0xFF1C1A16.toInt(),
    cemetery = 0xFF15201C.toInt(),
    hospital = 0xFF251820.toInt(),
    school = 0xFF1B1D26.toInt(),
    pitch = 0xFF16281D.toInt(),
    aeroway = 0xFF1D2028.toInt(),
    building = 0xFF1C2029.toInt(),
    buildingEdge = 0xFF2A303B.toInt(),
    roadNational = ROAD_INK_NATIONAL,
    roadRegional = ROAD_INK_REGIONAL,
    roadLocal = ROAD_INK_LOCAL,
    roadCasing = ROAD_INK_CASING,
    // Уночі ієрархію несе світність серцевини, а не облямівка: під нею скрізь
    // одна й та сама майже-чорна межа.
    roadCasingNational = ROAD_INK_CASING,
    rail = 0xFF4C5361.toInt(),
    path = 0xFF6E5F49.toInt(),
    boundaryCountry = 0xFF8D9AAC.toInt(),
    boundaryRegion = 0xFF4E5866.toInt(),
    placeInk = 0xFFE8ECF3.toInt(),
    placeHalo = 0xFF05070B.toInt(),
    waterInk = 0xFF8FB6D6.toInt(),
    roadUnpavedInk = 0xFFB08A55.toInt(),
    roadClosedInk = 0xFFE0705C.toInt(),
    roadNameInk = 0xFFB9C0CB.toInt(),
    poiInk = 0xFF98A0AD.toInt(),
)

/**
 * День.
 *
 * Тепла паперова земля замість стокової пастелі — рівний, малонасичений ґрунт,
 * на якому кольором говорять ЛИШЕ вода, ліс, поле і те, що несе наслідок.
 *
 * Дорога вдень несе ТРИ сигнали: ширину, облямівку і серцевину. Біла серцевина
 * давала на паперовій землі 1.07–1.11:1, тобто всю ієрархію тягла одна на всіх
 * облямівка; тепер національна серцевина тепла (бурштин на темнішому бурому
 * 2.86:1), обласна медова, а біле лишилось місцевій вулиці. Під червоним
 * фільтром із трьох сигналів лишається два.
 */
private val LIGHT_GROUND = GroundPalette(
    land = 0xFFF6F3EC.toInt(),
    water = 0xFF89B1CF.toInt(),
    waterDeep = 0xFF6EA2C8.toInt(),
    waterway = 0xFF77AACA.toInt(),
    wood = 0xFF9DC796.toInt(),
    woodEdge = 0xFF82B87A.toInt(),
    grass = 0xFFBED6AE.toInt(),
    // Поле — не газон (d=63.4 проти трави) і не забудова (d=15.9 проти тінту):
    // клас, що покриває більшість площі країни, мусить читатися як власний.
    farmland = 0xFFEDEBD3.toInt(),
    wetland = 0xFFAECCC7.toInt(),
    sand = 0xFFE8DDBF.toInt(),
    rock = 0xFFD9D7D1.toInt(),
    ice = 0xFFD7E3EA.toInt(),
    residential = 0xFFE7E4E0.toInt(),
    commercial = 0xFFE5DDE3.toInt(),
    industrial = 0xFFE0D9D4.toInt(),
    cemetery = 0xFFC9D8C9.toInt(),
    hospital = 0xFFE9D2D6.toInt(),
    school = 0xFFDCDBE6.toInt(),
    pitch = 0xFFAED3A5.toInt(),
    aeroway = 0xFFDCDDE1.toInt(),
    building = 0xFFD1C7BA.toInt(),
    buildingEdge = 0xFFB2A087.toInt(),
    roadNational = 0xFFF2BE6E.toInt(),
    roadRegional = 0xFFF9E3A6.toInt(),
    roadLocal = 0xFFFFFFFF.toInt(),
    roadCasing = 0xFF947C57.toInt(),
    roadCasingNational = 0xFF8F6B33.toInt(),
    rail = 0xFF9D8C7C.toInt(),
    path = 0xFF8F6B3F.toInt(),
    boundaryCountry = 0xFF817262.toInt(),
    boundaryRegion = 0xFFA29788.toInt(),
    placeInk = 0xFF3A342F.toInt(),
    placeHalo = 0xFFFFFFFF.toInt(),
    waterInk = 0xFF256594.toInt(),
    roadUnpavedInk = 0xFF8A6A3C.toInt(),
    roadClosedInk = 0xFFB3402F.toInt(),
    roadNameInk = 0xFF534C44.toInt(),
    poiInk = 0xFF6C655C.toInt(),
)

/* ── Дрібниці ────────────────────────────────────────────────────────────── */

/** `match(get(field), …)` без чотирьох рядків шуму на кожен виклик. */
internal fun match(field: String, vararg stops: Pair<String, Int>, default: Int): Expression =
    Expression.match(
        *(
            listOf(Expression.get(field)) +
                stops.flatMap { (k, v) -> listOf(Expression.literal(k), Expression.color(v)) } +
                listOf(Expression.color(default))
            ).toTypedArray(),
    )

/** Наші шари не належать перефарбу чужих — див. цикл [applyGroundCartography].
 *  Імена чужих шарів і драбина ширини живуть у MapGroundBackfill.kt. */
internal const val PHANTOM_LAYER_PREFIX = "phantom-"

private const val HOUSENUMBER_MIN_ZOOM = 17.5f
private const val HOUSENUMBER_PADDING = 12f
private const val STREET_NAME_SPACING = 480f
