package local.phantom.obsidian

/**
 * Кордони і підписи — усе, що на мапі є текстом або рамкою світу.
 *
 * ДВА ЗАКОНИ ЦЬОГО ФАЙЛУ.
 *
 * 1. Символьні шари йдуть суцільним хвостом у кінці стилю і ніде більше:
 *    контрибуції застосунку вставляють себе «під перший символьний шар»
 *    ([ID_FIRST_SYMBOL]), і символ, загублений десь серед заливок, обвалив би
 *    цей якір разом з екструзіями будівель.
 *
 * 2. Порядок символьних шарів — це пріоритет у зіткненнях, і він зворотний до
 *    інтуїції: рушій розставляє підписи згори вниз, тож ВИЩИЙ шар виграє
 *    місце. Тому країна остання, номер будинку перший: коли на екрані тісно,
 *    зникає номер, а не місто.
 *
 *    Драбина називається тут один раз і тримається [LABEL_LADDER], бо порядок
 *    у цьому списку — єдине, що її задає, і переставлений шар не падає ні
 *    збіркою, ні виразом. Знизу вгору: номер будинку, пік, вулиця, річка,
 *    водойма, район, село, містечко, місто, країна. Пік стояв ВИЩЕ за вулицю
 *    й річку — тобто назва вершини вигравала місце в назви вулиці, хоч
 *    малюється найтихішим чорнилом стилю; голос і пріоритет казали протилежне.
 *
 * Кегель кожного щабля прибитий до типографічних токенів — мапа не заводить
 * шостої типографіки, вона говорить голосами продукту.
 */

private val MICRO = Tokens.type("ph.type.micro").size
private val CAPTION = Tokens.type("ph.type.caption").size
private val BODY = Tokens.type("ph.type.body").size
private val TITLE = Tokens.type("ph.type.title").size

/** Ореол землі 1.2px — карто-якір із документа токенів. Він і робить читабельним
 *  світлий підпис на нічній землі й темний на денному папері. */
private const val HALO = 1.2

/** Назви місць — крупніший кегель, тож ореол ширший; розмиття 0,5 на всіх, щоб
 *  край ореолу не читався як контур. */
private const val PLACE_HALO = 1.4
private const val HALO_BLUR = 0.5

/**
 * Адмінкордони.
 *
 * Лінія з `disputed` — чиясь заява, а не кордон: у Криму це лінія окупації
 * через Перекоп і «морський кордон РФ» навколо півострова (`claimed_by=RU`).
 * Державною і морською вагою малюємо лише визнане; межі АР Крим і Севастополя
 * так, як їх визначає Україна (`claimed_by=UA`), — обласною.
 */
internal val RECOGNISED_LINE = neq(get("disputed"), 1)
internal val UKRAINE_CLAIM = all(eq(get("disputed"), 1), eq(get("claimed_by"), "UA"))

private val MARITIME_WIDTH = listOf<Pair<Number, Number>>(4 to 0.5, 10 to 1.2)
private val REGION_WIDTH = listOf<Pair<Number, Number>>(4 to 0.6, 10 to 1.4, 16 to 2.4)

internal fun boundaryLayers(ink: CartoInk): List<Json.Obj> = listOf(
    layer(
        id = "obsidian-boundary-maritime",
        type = "line",
        sourceLayer = SL_BOUNDARY,
        minzoom = 4,
        filter = all(eq(get("maritime"), 1), lte(get("admin_level"), 2), RECOGNISED_LINE),
        layout = jobj("line-join" to jstr("round")),
        paint = jobj(
            "line-color" to jstr(ink.boundaryMaritime),
            "line-dasharray" to dashRamp(4.5, 3.2, listOf(7.0, 10.0)) { curveAt(MARITIME_WIDTH, it) },
            "line-width" to zoomRamp(1.2, MARITIME_WIDTH),
        ),
    ),
    layer(
        id = "obsidian-boundary-region",
        type = "line",
        sourceLayer = SL_BOUNDARY,
        minzoom = 4,
        filter = all(
            neq(get("maritime"), 1),
            any(all(gte(get("admin_level"), 3), lte(get("admin_level"), 4), RECOGNISED_LINE), UKRAINE_CLAIM),
        ),
        layout = jobj("line-join" to jstr("round")),
        paint = jobj(
            "line-color" to jstr(ink.boundaryRegion),
            "line-dasharray" to dashRamp(4.0, 3.6, listOf(8.0, 13.0)) { curveAt(REGION_WIDTH, it) },
            "line-width" to zoomRamp(1.2, REGION_WIDTH),
        ),
    ),
    layer(
        id = "obsidian-boundary-country",
        type = "line",
        sourceLayer = SL_BOUNDARY,
        minzoom = 1,
        filter = all(neq(get("maritime"), 1), lte(get("admin_level"), 2), RECOGNISED_LINE),
        layout = jobj("line-join" to jstr("round"), "line-cap" to jstr("round")),
        paint = jobj(
            "line-color" to jstr(ink.boundaryCountry),
            "line-width" to zoomRamp(1.2, listOf(1.0 to 0.6, 6.0 to 1.4, 12.0 to 2.6, 18.0 to 4.0)),
        ),
    ),
)

/**
 * Драбина зіткнень знизу вгору. Хто нижче — зникає першим.
 *
 * Список і є порядком у стилі: [labelLayers] розкладає за ним, тож переставити
 * пріоритет можна тільки тут, а `LabelLadderTest` звіряє з ним випечений хвіст.
 */
internal val LABEL_LADDER = listOf(
    ID_FIRST_SYMBOL,
    ID_LABEL_POI,
    "obsidian-label-peak",
    "obsidian-label-road",
    ID_LABEL_TRANSIT,
    "obsidian-label-waterway",
    "obsidian-label-water",
    "obsidian-label-suburb",
    "obsidian-label-village",
    "obsidian-label-town",
    "obsidian-label-city",
    "obsidian-label-country",
)

internal val TRANSIT_CLASSES = arrayOf("railway", "bus", "ferry_terminal")

/** Класи без імені, яке людина шукала б: урни, стовпчики, ворота, паркування. */
private val POI_SILENT = arrayOf(
    "parking", "bicycle_parking", "waste_basket", "bollard", "gate", "lift_gate",
    "information", "post", "recycling", "toilets", "drinking_water", "entrance", "shelter",
)

/** Пункти контролю мапа не підписує — та сама межа, що й у картки місця. */
private val POI_UNNAMED = arrayOf("checkpoint", "bunker", "border_control")

internal fun labelLayers(ink: CartoInk): List<Json.Obj> {
    val byId = labelLayerPool(ink).associateBy { (it.at("id") as Json.Str).value }
    require(byId.keys == LABEL_LADDER.toSet()) {
        "драбина підписів розійшлась зі складом шарів: ${byId.keys} проти $LABEL_LADDER"
    }
    return LABEL_LADDER.map(byId::getValue)
}

private fun labelLayerPool(ink: CartoInk): List<Json.Obj> = listOf(
    // Перший символьний шар стилю — якір [ID_FIRST_SYMBOL]. Номер будинку
    // програє все і всім, і саме тому стоїть тут.
    //
    // Тайл віддає `housenumber` лише на z14 і далі оверзумом, тож глибокий зум
    // — єдине місце, де цей шар взагалі має що малювати; z17 і є та глибина, на
    // якій питання «який це будинок» уперше стає питанням.
    //
    // Але «програє все і всім» рятує лише там, де є кому вигравати. На z17
    // конкурентів у номера майже немає — назва вулиці одна на квартал, назва
    // району одна на екран, — і номери, яких у кадрі сотні, засипали мапу
    // самими собою: вердикт власника «мапа засипана підписами / лише номери
    // будівель». Рушій не вміє обмежити кількість підписів; єдиний важіль —
    // [text-padding], розмір коробки зіткнення. Тому padding — рампа, а не
    // стала: на z17, де питають «де я», номери рідкі; на z19, де питають «який
    // це будинок», вони густі. Стеля кадру A52 (411×914 dp) на z17 падає з
    // 1053 підписів до 170 — числа тримає `LabelLadderTest`.
    layer(
        id = ID_FIRST_SYMBOL,
        type = "symbol",
        sourceLayer = SL_HOUSENUMBER,
        minzoom = 17,
        layout = jobj(
            "text-field" to get("housenumber"),
            "text-font" to FONT_STACK,
            "text-size" to zoomRamp(null, listOf(17.0 to MICRO, 19.0 to CAPTION)),
            "text-padding" to zoomRamp(null, listOf(17.0 to 16.0, 18.0 to 10.0, 19.0 to 6.0)),
        ),
        paint = jobj(
            "text-color" to jstr(ink.labelFaint),
            "text-halo-blur" to jnum(HALO_BLUR),
            "text-halo-color" to jstr(ink.labelHalo),
            "text-halo-width" to jnum(HALO),
        ),
    ),
    // Назви місць і зупинок — з того самого тайла, що вже малює будівлі.
    // Без них свіжа установка поза пакунком України, або коли Overpass лежить,
    // показувала самі коробки будинків. Лише текст: крапку малює наш пін, а він
    // вище в стилі й забирає місце першим, тож однойменний підпис тут зникає.
    layer(
        id = ID_LABEL_POI,
        type = "symbol",
        sourceLayer = SL_POI,
        minzoom = 15,
        filter = all(has("name"), not(classIn(*(POI_SILENT + TRANSIT_CLASSES))), not(oneOf(get("subclass"), *POI_UNNAMED))),
        layout = jobj(
            "symbol-sort-key" to numberOr("rank", 100),
            "text-field" to ukFirstName(),
            "text-font" to FONT_STACK,
            "text-max-width" to jnum(7),
            "text-padding" to zoomRamp(null, listOf(15.0 to 12.0, 16.0 to 8.0, 18.0 to 4.0)),
            "text-size" to zoomRamp(null, listOf(15.0 to MICRO, 18.0 to CAPTION)),
        ),
        paint = jobj(
            "text-color" to jstr(ink.labelMuted),
            "text-halo-blur" to jnum(HALO_BLUR),
            "text-halo-color" to jstr(ink.labelHalo),
            "text-halo-width" to jnum(HALO),
        ),
    ),
    layer(
        id = ID_LABEL_TRANSIT,
        type = "symbol",
        sourceLayer = SL_POI,
        minzoom = 15,
        filter = all(has("name"), classIn(*TRANSIT_CLASSES)),
        layout = jobj(
            "symbol-sort-key" to jarr(
                jstr("match"), get("subclass"),
                jarr(jstr("station"), jstr("subway"), jstr("halt"), jstr("bus_station"), jstr("ferry_terminal")), jnum(0),
                jnum(1),
            ),
            "text-field" to ukFirstName(),
            "text-font" to FONT_STACK,
            "text-max-width" to jnum(7),
            "text-padding" to jnum(6),
            "text-size" to zoomRamp(null, listOf(15.0 to MICRO, 17.0 to CAPTION)),
        ),
        paint = jobj(
            "text-color" to jstr(ink.labelInk),
            "text-halo-blur" to jnum(HALO_BLUR),
            "text-halo-color" to jstr(ink.labelHalo),
            "text-halo-width" to jnum(HALO),
        ),
    ),
    // Назва вулиці ПОВТОРЮЄТЬСЯ вздовж лінії, і крок повтору падає з зумом.
    //
    // Стала 260 px — це крок для оглядового зуму, де в кадр влазить район. На
    // z18 у кадрі лишається пів кварталу, і єдиний якір назви майже завжди
    // опинявся за краєм екрана: мапа останньої сотні метрів не називала жодної
    // вулиці (блокер гонтлету, 10 з 10). Тепер на глибині крок утричі коротший
    // — у кадрі гарантовано є найближче ім'я.
    layer(
        id = "obsidian-label-road",
        type = "symbol",
        sourceLayer = SL_TRANSPORTATION_NAME,
        minzoom = 12,
        filter = has("name"),
        layout = jobj(
            "symbol-placement" to jstr("line"),
            "symbol-sort-key" to roadLabelSortKey(),
            "symbol-spacing" to zoomRamp(null, listOf(12.0 to 260.0, 15.0 to 200.0, 17.0 to 130.0, 19.0 to 90.0)),
            "text-field" to ukFirstName(),
            "text-font" to FONT_STACK,
            "text-max-angle" to jnum(30),
            "text-padding" to jnum(2),
            "text-rotation-alignment" to jstr("map"),
            "text-size" to zoomRamp(null, listOf(12.0 to MICRO, 16.0 to CAPTION, 18.5 to BODY)),
        ),
        paint = jobj(
            "text-color" to jstr(ink.labelRoad),
            "text-halo-blur" to jnum(HALO_BLUR),
            "text-halo-color" to jstr(ink.labelHalo),
            "text-halo-width" to jnum(HALO),
        ),
    ),
    layer(
        id = "obsidian-label-waterway",
        type = "symbol",
        sourceLayer = SL_WATERWAY,
        minzoom = 12,
        filter = all(has("name"), classIn("river", "canal")),
        layout = jobj(
            "symbol-placement" to jstr("line"),
            "symbol-spacing" to jnum(400),
            "text-field" to ukFirstName(),
            "text-font" to FONT_STACK,
            "text-max-angle" to jnum(30),
            "text-rotation-alignment" to jstr("map"),
            "text-size" to jnum(CAPTION),
        ),
        paint = jobj(
            "text-color" to jstr(ink.labelWater),
            "text-halo-blur" to jnum(HALO_BLUR),
            "text-halo-color" to jstr(ink.labelHalo),
            "text-halo-width" to jnum(HALO),
        ),
    ),
    layer(
        id = "obsidian-label-water",
        type = "symbol",
        sourceLayer = SL_WATER_NAME,
        minzoom = 5,
        filter = has("name"),
        layout = jobj(
            "text-field" to ukFirstName(),
            "text-font" to FONT_STACK,
            "text-max-width" to jnum(6),
            "text-size" to zoomRamp(null, listOf(5.0 to MICRO, 12.0 to CAPTION)),
        ),
        paint = jobj(
            "text-color" to jstr(ink.labelWater),
            "text-halo-blur" to jnum(HALO_BLUR),
            "text-halo-color" to jstr(ink.labelHalo),
            "text-halo-width" to jnum(HALO),
        ),
    ),
    layer(
        id = "obsidian-label-peak",
        type = "symbol",
        sourceLayer = SL_MOUNTAIN_PEAK,
        minzoom = 11,
        filter = has("name"),
        layout = jobj(
            "symbol-sort-key" to numberOr("rank", 100),
            "text-field" to ukFirstName(),
            "text-font" to FONT_STACK,
            "text-size" to jnum(MICRO),
        ),
        paint = jobj(
            "text-color" to jstr(ink.labelFaint),
            "text-halo-blur" to jnum(HALO_BLUR),
            "text-halo-color" to jstr(ink.labelHalo),
            "text-halo-width" to jnum(HALO),
        ),
    ),
    // Район — малими капітелями з розрядкою: тихий голос, що називає територію,
    // а не точку, і не сперечається з назвою вулиці того ж кегля.
    place(ink, "obsidian-label-suburb", 12, ink.labelMuted, listOf(12.0 to MICRO, 16.0 to CAPTION), 0.08, true, "suburb", "quarter", "neighbourhood"),
    place(ink, "obsidian-label-village", 10, ink.labelMuted, listOf(10.0 to MICRO, 14.0 to CAPTION), 0.02, false, "village", "hamlet", "isolated_dwelling"),
    place(ink, "obsidian-label-town", 8, ink.labelInk, listOf(8.0 to CAPTION, 13.0 to BODY), 0.03, false, "town"),
    place(ink, "obsidian-label-city", 4, ink.labelInk, listOf(4.0 to CAPTION, 8.0 to BODY, 12.0 to TITLE), 0.04, false, "city"),
    layer(
        id = "obsidian-label-country",
        type = "symbol",
        sourceLayer = SL_PLACE,
        minzoom = 2,
        maxzoom = 8,
        filter = all(has("name"), classIn("country")),
        layout = jobj(
            "symbol-sort-key" to numberOr("rank", 100),
            "text-field" to ukFirstName(),
            "text-font" to FONT_STACK,
            "text-letter-spacing" to jnum(0.08),
            "text-max-width" to jnum(7),
            "text-padding" to jnum(6),
            "text-size" to zoomRamp(null, listOf(2.0 to CAPTION, 5.0 to BODY)),
            "text-transform" to jstr("uppercase"),
        ),
        paint = jobj(
            "text-color" to jstr(ink.labelMuted),
            "text-halo-blur" to jnum(HALO_BLUR),
            "text-halo-color" to jstr(ink.labelHalo),
            "text-halo-width" to jnum(PLACE_HALO),
        ),
    ),
)

private fun place(
    ink: CartoInk,
    id: String,
    minzoom: Int,
    color: String,
    size: List<Pair<Double, Double>>,
    tracking: Double,
    caps: Boolean,
    vararg classes: String,
) = layer(
    id = id,
    type = "symbol",
    sourceLayer = SL_PLACE,
    minzoom = minzoom,
    filter = all(has("name"), classIn(*classes)),
    layout = jobj(
        // Менший rank — важливіше місце, і рушій ставить менший ключ першим.
        // Так у смузі одного щабля обласний центр виграє в райцентру.
        "symbol-sort-key" to numberOr("rank", 100),
        "text-field" to ukFirstName(),
        "text-font" to FONT_STACK,
        "text-letter-spacing" to jnum(tracking),
        "text-max-width" to jnum(8),
        "text-padding" to jnum(4),
        "text-size" to zoomRamp(null, size),
        "text-transform" to (if (caps) jstr("uppercase") else null),
    ),
    paint = jobj(
        "text-color" to jstr(color),
        "text-halo-blur" to jnum(HALO_BLUR),
        "text-halo-color" to jstr(ink.labelHalo),
        "text-halo-width" to jnum(PLACE_HALO),
    ),
)
