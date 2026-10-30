package local.phantom.obsidian

/**
 * Тонкий шар над [Json], що говорить мовою style-spec v8 і схеми OpenMapTiles.
 *
 * Тут немає жодного рішення про вигляд — лише форма. Рішення живуть у
 * `Layers*.kt`, дані про кольори — у [Tokens]/[CartoInk].
 */

/** Джерело одне на весь стиль: `installBuildingExtrusions` на пристрої бере
 *  `sources.firstOrNull { it is VectorSource }`, і другий вектор зробив би цей
 *  вибір випадковим. */
const val SOURCE_ID = "openmaptiles"

const val TILEJSON_URL = "https://tiles.openfreemap.org/planet"
/**
 * Підписи мапи більше не залежать від мережі.
 *
 * Тут стояла адреса гліф-сервера OpenFreeMap. Наслідок був не косметичний:
 * без мережі мапа лишалась БЕЗ ЖОДНОГО ПІДПИСУ — ні назв міст, ні вулиць.
 * Тобто «офлайн-мапа» показувала кольорові плями.
 *
 * Стек тут рівно один — «Noto Sans Regular» (див. [FONT_STACK]), тож усе, що
 * треба покласти в пакунок, — це діапазони гліфів для нього. Покладено вісім,
 * 748 КБ разом:
 *
 *   0-255      латиниця й пунктуація      256-511    Latin Extended-A
 *   512-767    Latin Extended-B, IPA      768-1023   діакритики, грека
 *   1024-1279  КИРИЛИЦЯ                   1280-1535  кирилиця, доповнення
 *   7680-7935  Latin Extended Additional  8192-8447  загальна пунктуація
 *
 * Усіх 256 діапазонів шрифту разом — 34 МБ, і це було б забагато для пакунка
 * заради письма, якого в українській мапі немає. Ціна вибору названа прямо:
 * підпис із гліфом поза цими межами (грецькі літери за межами діакритик,
 * арабиця, CJK, «№») малюється без цього знака: решта діапазонів лежить
 * порожніми файлами, бо діапазон, що не читається, зупиняє тайл цілком.
 */
const val GLYPHS_URL = "asset://fonts/{fontstack}/{range}.pbf"

/** Гліф-сервер OpenFreeMap віддає рівно цей стек; будь-який інший — 404 на
 *  кожному діапазоні, тобто підпис без тексту. Те саме правило, що
 *  `MapStyleConfig.MAP_TEXT_FONT` тримає для наших власних символьних шарів. */
val FONT_STACK: Json = jarr(jstr("Noto Sans Regular"))

const val SL_WATER = "water"
const val SL_WATERWAY = "waterway"
const val SL_LANDCOVER = "landcover"
const val SL_LANDUSE = "landuse"
const val SL_PARK = "park"
const val SL_BOUNDARY = "boundary"
const val SL_AEROWAY = "aeroway"
const val SL_TRANSPORTATION = "transportation"
const val SL_BUILDING = "building"
const val SL_HOUSENUMBER = "housenumber"
const val SL_PLACE = "place"
const val SL_WATER_NAME = "water_name"
const val SL_TRANSPORTATION_NAME = "transportation_name"
const val SL_MOUNTAIN_PEAK = "mountain_peak"
const val SL_POI = "poi"

fun layer(
    id: String,
    type: String,
    sourceLayer: String? = null,
    minzoom: Number? = null,
    maxzoom: Number? = null,
    filter: Json? = null,
    layout: Json.Obj? = null,
    paint: Json.Obj? = null,
): Json.Obj = jobj(
    "id" to jstr(id),
    "type" to jstr(type),
    "source" to sourceLayer?.let { jstr(SOURCE_ID) },
    "source-layer" to sourceLayer?.let { jstr(it) },
    "minzoom" to minzoom?.let { jnum(it) },
    "maxzoom" to maxzoom?.let { jnum(it) },
    "filter" to filter,
    "layout" to layout?.takeIf { it.entries.isNotEmpty() },
    "paint" to paint?.takeIf { it.entries.isNotEmpty() },
)

/* ── Вирази ──────────────────────────────────────────────────────────────── */

fun get(field: String): Json = jarr(jstr("get"), jstr(field))

fun zoom(): Json = jarr(jstr("zoom"))

fun geometryType(): Json = jarr(jstr("geometry-type"))

fun eq(left: Json, value: String): Json = jarr(jstr("=="), left, jstr(value))

fun eq(left: Json, value: Number): Json = jarr(jstr("=="), left, jnum(value))

fun neq(left: Json, value: String): Json = jarr(jstr("!="), left, jstr(value))

fun neq(left: Json, value: Number): Json = jarr(jstr("!="), left, jnum(value))

fun lte(left: Json, value: Number): Json = jarr(jstr("<="), left, jnum(value))

fun gte(left: Json, value: Number): Json = jarr(jstr(">="), left, jnum(value))

fun has(field: String): Json = jarr(jstr("has"), jstr(field))

/** Числове поле з підстраховкою: `symbol-sort-key` не приймає `null`, а `rank`
 *  у тайлі є не в кожної точки. */
fun numberOr(field: String, fallback: Number): Json =
    jarr(jstr("to-number"), jarr(jstr("coalesce"), get(field), jnum(fallback)))

fun not(e: Json): Json = jarr(jstr("!"), e)

fun all(vararg parts: Json): Json = jarr(listOf(jstr("all")) + parts.toList())

fun any(vararg parts: Json): Json = jarr(listOf(jstr("any")) + parts.toList())

/**
 * Належність до набору через `match`, а не `in`: `match` є в style-spec з
 * першого дня і його розуміє кожна версія рушія, яку може принести пристрій.
 */
fun oneOf(field: Json, vararg values: String): Json = jarr(
    jstr("match"),
    field,
    jarr(values.map(::jstr)),
    jbool(true),
    jbool(false),
)

fun classIn(vararg values: String): Json = oneOf(get("class"), *values)

fun interpolate(base: Double?, input: Json, stops: List<Pair<Number, Json>>): Json {
    val curve = if (base == null) jarr(jstr("linear")) else jarr(jstr("exponential"), jnum(base))
    val flat = stops.flatMap { (z, v) -> listOf(jnum(z), v) }
    return jarr(listOf(jstr("interpolate"), curve, input) + flat)
}

fun zoomRamp(base: Double?, stops: List<Pair<Number, Number>>): Json =
    interpolate(base, zoom(), stops.map { (z, v) -> z to jnum(v) })

fun step(input: Json, first: Json, stops: List<Pair<Number, Json>>): Json {
    val flat = stops.flatMap { (at, v) -> listOf(jnum(at), v) }
    return jarr(listOf(jstr("step"), input, first) + flat)
}

/** Один підпис українською, далі — як у даних. Ліберті клеїть
 *  `{name:latin}\n{name:nonlatin}` і дає «Kyiv Київ» у два рядки. */
fun ukFirstName(): Json = jarr(jstr("coalesce"), get("name:uk"), get("name"))

fun dash(vararg segments: Number): Json = jarr(segments.map(::jnum))

/**
 * Голий масив в аргументі виразу — це для MapLibre ВИРАЗ, і він читає перший
 * елемент як ім'я оператора: «Expression name must be a string, but found
 * number instead». Властивість після цього відкидається ЦІЛКОМ і мовчки.
 */
fun literal(value: Json): Json = jarr(jstr("literal"), value)

/** Значення кусково-лінійної кривої в точці; за межами тримає крайній стоп. Та
 *  сама крива, з якої виростає `interpolate` — щоб пунктир і ширина не
 *  роз'їхались, коли хтось поправить одну з них. */
fun curveAt(stops: List<Pair<Number, Number>>, z: Double): Double {
    val curve = stops.map { (at, v) -> at.toDouble() to v.toDouble() }
    if (z <= curve.first().first) return curve.first().second
    if (z >= curve.last().first) return curve.last().second
    val hi = curve.indexOfFirst { it.first >= z }
    val (z0, v0) = curve[hi - 1]
    val (z1, v1) = curve[hi]
    return v0 + (v1 - v0) * (z - z0) / (z1 - z0)
}

/**
 * Пунктир, що не росте разом з лінією.
 *
 * `line-dasharray` міряється в ШИРИНАХ лінії і між стопами не інтерполюється —
 * style-spec дозволяє на ньому лише `step`. Тому кожна смуга зуму перераховує
 * штрих із тієї самої кривої ширини: ділимо бажану довжину в пікселях на
 * ширину лінії посеред смуги. Без цього стежка на z18 йшла сосисками
 * завдовжки з авто, а на z14 тим самим числом — суцільною лінією.
 *
 * [edges] — межі смуг; представником крайніх смуг береться зум за межею, бо
 * там крива вже пласка.
 */
fun dashRamp(dashPx: Double, gapPx: Double, edges: List<Double>, width: (Double) -> Double): Json {
    require(edges.isNotEmpty()) { "пунктир без жодної межі смуги" }
    fun band(at: Double): Json {
        val w = width(at)
        require(w > 0.0) { "нульова ширина лінії на z$at — пунктир поділився б на нуль" }
        return literal(dash(dashPx / w, gapPx / w))
    }
    val mid = (1 until edges.size).map { (edges[it - 1] + edges[it]) / 2 } + (edges.last() + 1.0)
    return step(zoom(), band(edges.first() - 1.0), edges.mapIndexed { i, e -> e to band(mid[i]) })
}
