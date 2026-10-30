package local.phantom.obsidian

/**
 * Дорожня мережа — вісім щаблів, три смуги обводок, тунелі, мости, рейки і
 * два шари чесності.
 *
 * Ієрархія тримається порядком, а не тільки шириною: магістраль малюється
 * ОСТАННЬОЮ і тому перетинає все інше зверху, як у житті. Обводки згруповані
 * у три смуги (дрібні / середні / великі) замість обводки на кожен щабель:
 * так у чужого Liberty 48 дорожніх шарів, у нас — 15, а на перехресті смуги
 * все одно не ріжуть одна одну.
 *
 * Уночі дорога — світлова жила: рампа road-major → road-minor іде від майже
 * білого до сланцевого, і саме ця рампа несе ранг, коли ширини вже не досить.
 */

private data class Rank(
    val id: String,
    val classes: List<String>,
    val minZoom: Int,
    val scale: Double,
    val ink: (CartoInk) -> String,
)

/**
 * Щаблі рознесені рівним кроком ~0,10 замість колишніх нерівних 0,10-0,12: на
 * z15 це дає між сусідніми класами постійні ~0,85 px, тобто ієрархію, яку око
 * читає з витягнутої руки. Раніше `minor` і `service` тиснулись до стежки, і
 * квартал виходив однією сірою кашею — вердикт далекобійника.
 */
private val RANKS = listOf(
    Rank("obsidian-road-motorway", listOf("motorway"), 4, 1.00) { it.roadMotorway },
    Rank("obsidian-road-trunk", listOf("trunk"), 5, 0.86) { it.roadTrunk },
    Rank("obsidian-road-primary", listOf("primary"), 7, 0.76) { it.roadPrimary },
    Rank("obsidian-road-secondary", listOf("secondary"), 9, 0.66) { it.roadSecondary },
    Rank("obsidian-road-tertiary", listOf("tertiary"), 10, 0.56) { it.roadTertiary },
    Rank("obsidian-road-minor", listOf("minor"), 12, 0.46) { it.roadMinor },
    Rank("obsidian-road-service", listOf("service", "track"), 13, 0.32) { it.roadService },
    Rank("obsidian-road-path", listOf("path"), 14, 0.18) { it.roadPath },
)

private fun rank(id: String) = RANKS.first { it.id == id }

/** Ширина щабля 1.0 по зумах; решта — та сама крива, помножена на [Rank.scale]. */
private val BASE = listOf(
    4.0 to 0.5, 6.0 to 0.9, 8.0 to 1.5, 10.0 to 2.4, 12.0 to 3.6,
    14.0 to 6.0, 16.0 to 12.0, 18.0 to 24.0, 20.0 to 48.0, 22.0 to 92.0,
)

/** Половина обводки на кожен бік. Росте повільніше за дорогу — інакше на z20
 *  місто перетворюється на суцільний контур. */
private val PAD = listOf(
    4.0 to 0.4, 10.0 to 0.5, 12.0 to 0.8, 14.0 to 1.2,
    16.0 to 2.0, 18.0 to 3.0, 20.0 to 4.0, 22.0 to 5.0,
)

private const val ROAD_CURVE = 1.35

private const val TUNNEL_FROM = 11
private const val BRIDGE_FROM = 11
private const val UNPAVED_FROM = 12
private const val CLOSED_FROM = 13

/**
 * Пішохідна мережа не має ваги дороги і не має її вдавати.
 *
 * У київському кварталі стежок більше, ніж проїжджих, і на z14-16 саме вони
 * заливали екран: 1,6× ширини щабля плюс найтемніше чорнило рампи на денному
 * папері. Тепер тонше за власний щабель і напівпрозоре там, де важить мережа
 * для коліс; до z18 прозорість вертається — на глибокому зумі стежка знову
 * відповідь на «як пройти».
 */
private const val PATH_WEIGHT = 0.9

private val PATH_ALPHA = listOf<Pair<Number, Number>>(14 to 0.40, 16 to 0.58, 18.5 to 0.82)

private fun stopsFrom(minZoom: Int): List<Double> =
    (listOf(minZoom.toDouble()) + BASE.map { it.first }.filter { it > minZoom }).distinct()

private fun fill(r: Rank, z: Double, factor: Double) = curveAt(BASE, z) * r.scale * factor

private fun casing(r: Rank, z: Double, padFactor: Double) =
    curveAt(BASE, z) * r.scale + 2 * curveAt(PAD, z) * padFactor

private fun ramp(r: Rank, factor: Double = 1.0): Json =
    zoomRamp(ROAD_CURVE, stopsFrom(r.minZoom).map { z -> z to fill(r, z, factor) })

/**
 * Ширина, що залежить і від зуму, і від класу.
 *
 * Форма важлива: `interpolate` мусить бути ЗОВНІ, а `match` — усередині
 * кожного стопа. Навпаки (match, що віддає interpolate) style-spec забороняє
 * — вираз зуму дозволений лише на верхньому рівні, і MapLibre відкидає такий
 * шар цілком, мовчки лишаючи дірку в мережі.
 *
 * Нуль нижче власного мінзуму щабля: смуга обводок починається з найранішого
 * зі своїх щаблів, і без цього обводка сервісного проїзду з'явилась би на зум
 * раніше за сам проїзд.
 */
private fun rankRamp(ranks: List<Rank>, from: Int, value: (Rank, Double) -> Double): Json {
    val floor = from.toDouble()
    // Стопи нижче мінзуму шару нікого не малюють, зате множаться на вісім
    // гілок `match` у кожному з них: без цієї відсічі тільки чотири
    // всещаблеві шари важили більше, ніж уся решта стилю.
    val stops = (BASE.map { it.first } + ranks.flatMap { listOf(it.minZoom - 0.5, it.minZoom.toDouble()) })
        .filter { it > floor }
        .plus(floor)
        .distinct()
        .sorted()
    return interpolate(
        ROAD_CURVE,
        zoom(),
        stops.map { z ->
            z to byRank(ranks) { r -> jnum(if (z < r.minZoom) 0.0 else value(r, z)) }
        },
    )
}

/** `match` по класу дороги. Останній щабель стає замовчуванням — `match` без
 *  default недійсний. */
private fun byRank(ranks: List<Rank>, value: (Rank) -> Json): Json {
    val head = ranks.dropLast(1).flatMap { listOf(jarr(it.classes.map(::jstr)), value(it)) }
    return jarr(listOf(jstr("match"), get("class")) + head + value(ranks.last()))
}

/**
 * Пріоритет НАЗВИ дороги в зіткненнях — з того самого [RANKS], що несе товщину
 * лінії. Менший ключ виграє, тож індекс щабля і є ключем: магістраль 0,
 * стежка 7.
 *
 * Досі назва дороги була єдиним іменованим шаром стилю БЕЗ `symbol-sort-key`
 * (пік, район, село, містечко, місто, країна його мають). Кадр A52 на z17 бере
 * 23 назви доріг, а тайл міста віддає їх сотні — отже, які саме 23 побачить
 * людина, вирішував порядок у тайлі. Товщина лінії вже казала, що магістраль
 * головніша за проїзд; підпис цього не знав.
 *
 * Свого списку класів тут немає свідомо: дві драбини важливості доріг
 * розійшлись би тихо, і розійшлись би саме там, де їх ніхто не звіряє.
 */
internal fun roadLabelSortKey(): Json =
    byRank(RANKS) { r -> jnum(RANKS.indexOfFirst { it.id == r.id }) }

private val ALL_CLASSES = RANKS.flatMap { it.classes }.toTypedArray()

private fun notBrunnel() = all(neq(get("brunnel"), "bridge"), neq(get("brunnel"), "tunnel"))

private fun surfaceRoad(vararg classes: String) = all(notBrunnel(), classIn(*classes))

internal fun roadLayers(ink: CartoInk): List<Json.Obj> = buildList {
    addAll(tunnelLayers(ink))
    add(pathLayer(ink))

    addAll(band(ink, "obsidian-road-casing-minor", listOf("obsidian-road-service", "obsidian-road-minor")))
    addAll(band(ink, "obsidian-road-casing-mid", listOf("obsidian-road-tertiary", "obsidian-road-secondary")))
    addAll(
        band(
            ink,
            "obsidian-road-casing-major",
            listOf("obsidian-road-primary", "obsidian-road-trunk", "obsidian-road-motorway"),
        ),
    )

    addAll(railLayers(ink))
    addAll(bridgeLayers(ink))
    addAll(honestyLayers(ink))
}

/**
 * Обводка смуги + заливки її щаблів, знизу вгору.
 *
 * Дрібна смуга носить ширший поділок обводки (1,15): житлова вулиця — єдиний
 * щабель, у якого своєї ширини на z14-16 не досить, щоб її було видно з-поміж
 * дворових проїздів, і саме обводка робить її вулицею. Смуга починається з
 * z12, тобто задовго до того зуму, на якому в неї питають дорогу.
 */
private fun band(ink: CartoInk, casingId: String, rankIds: List<String>): List<Json.Obj> {
    val ranks = rankIds.map(::rank)
    val major = casingId.endsWith("major")
    val pad = if (casingId.endsWith("minor")) 1.15 else 1.0
    val casingLayer = layer(
        id = casingId,
        type = "line",
        sourceLayer = SL_TRANSPORTATION,
        minzoom = ranks.minOf { it.minZoom },
        filter = surfaceRoad(*ranks.flatMap { it.classes }.toTypedArray()),
        layout = jobj("line-cap" to jstr("round"), "line-join" to jstr("round")),
        paint = jobj(
            "line-color" to jstr(if (major) ink.roadCasingMajor else ink.roadCasing),
            "line-width" to rankRamp(ranks, ranks.minOf { it.minZoom }) { r, z -> casing(r, z, pad) },
        ),
    )
    val fills = ranks.map { r ->
        layer(
            id = r.id,
            type = "line",
            sourceLayer = SL_TRANSPORTATION,
            minzoom = r.minZoom,
            filter = surfaceRoad(*r.classes.toTypedArray()),
            layout = jobj("line-cap" to jstr("round"), "line-join" to jstr("round")),
            paint = jobj("line-color" to jstr(r.ink(ink)), "line-width" to ramp(r)),
        )
    }
    return listOf(casingLayer) + fills
}

/** Стежка — пунктир без обводки: див. [PATH_WEIGHT]. */
private fun pathLayer(ink: CartoInk): Json.Obj {
    val r = rank("obsidian-road-path")
    return layer(
        id = r.id,
        type = "line",
        sourceLayer = SL_TRANSPORTATION,
        minzoom = r.minZoom,
        filter = surfaceRoad(*r.classes.toTypedArray()),
        layout = jobj("line-cap" to jstr("round")),
        paint = jobj(
            "line-color" to jstr(r.ink(ink)),
            "line-dasharray" to dashRamp(3.4, 2.6, listOf(15.5, 17.5, 19.5)) { fill(r, it, PATH_WEIGHT) },
            "line-opacity" to zoomRamp(null, PATH_ALPHA),
            "line-width" to ramp(r, factor = PATH_WEIGHT),
        ),
    )
}

private fun tunnelLayers(ink: CartoInk): List<Json.Obj> {
    val tunnel = eq(get("brunnel"), "tunnel")
    val filter = all(tunnel, classIn(*ALL_CLASSES))
    return listOf(
        layer(
            id = "obsidian-road-tunnel-casing",
            type = "line",
            sourceLayer = SL_TRANSPORTATION,
            minzoom = TUNNEL_FROM,
            filter = filter,
            layout = jobj("line-join" to jstr("round")),
            paint = jobj(
                "line-color" to jstr(ink.roadCasing),
                "line-width" to rankRamp(RANKS, TUNNEL_FROM) { r, z -> casing(r, z, 1.0) },
            ),
        ),
        layer(
            id = "obsidian-road-tunnel",
            type = "line",
            sourceLayer = SL_TRANSPORTATION,
            minzoom = TUNNEL_FROM,
            filter = filter,
            layout = jobj("line-join" to jstr("round")),
            paint = jobj(
                "line-color" to byRank(RANKS) { jstr(ink.tunnel(it.ink(ink))) },
                // Ширина тунелю залежить від класу, штрих — ні: представником
                // смуги береться середній щабель, бо під землею йдуть переважно
                // великі дороги, а пунктир на екрані мусить бути один.
                "line-dasharray" to dashRamp(6.0, 4.0, listOf(14.0, 17.0, 20.0)) {
                    fill(rank("obsidian-road-secondary"), it, 1.0)
                },
                "line-width" to rankRamp(RANKS, TUNNEL_FROM) { r, z -> fill(r, z, 1.0) },
            ),
        ),
    )
}

/** Міст важчий за землю навмисно: подвійна обводка — це те, чим око читає
 *  «тут дорога йде НАД чимось», без жодного напису. */
private fun bridgeLayers(ink: CartoInk): List<Json.Obj> {
    val bridge = eq(get("brunnel"), "bridge")
    val filter = all(bridge, classIn(*ALL_CLASSES))
    return listOf(
        layer(
            id = "obsidian-road-bridge-casing",
            type = "line",
            sourceLayer = SL_TRANSPORTATION,
            minzoom = BRIDGE_FROM,
            filter = filter,
            layout = jobj("line-cap" to jstr("butt"), "line-join" to jstr("round")),
            paint = jobj(
                "line-color" to jstr(ink.roadCasingMajor),
                "line-width" to rankRamp(RANKS, BRIDGE_FROM) { r, z -> casing(r, z, 1.9) },
            ),
        ),
        layer(
            id = "obsidian-road-bridge",
            type = "line",
            sourceLayer = SL_TRANSPORTATION,
            minzoom = BRIDGE_FROM,
            filter = filter,
            layout = jobj("line-cap" to jstr("round"), "line-join" to jstr("round")),
            paint = jobj(
                "line-color" to byRank(RANKS) { jstr(it.ink(ink)) },
                "line-width" to rankRamp(RANKS, BRIDGE_FROM) { r, z -> fill(r, z, 1.0) },
            ),
        ),
    )
}

private val TIE_WIDTH = listOf<Pair<Number, Number>>(13 to 2.4, 18 to 7.0, 22 to 14.0)

private fun railLayers(ink: CartoInk): List<Json.Obj> = listOf(
    layer(
        id = "obsidian-rail",
        type = "line",
        sourceLayer = SL_TRANSPORTATION,
        minzoom = 9,
        filter = all(notBrunnel(), classIn("rail")),
        paint = jobj(
            "line-color" to jstr(ink.rail),
            "line-width" to zoomRamp(1.3, listOf(9.0 to 0.5, 14.0 to 1.2, 18.0 to 2.6, 22.0 to 5.0)),
        ),
    ),
    // Шпали: рідкий пунктир утричі ширший за саму рейку — класичний прийом,
    // який робить залізницю залізницею, а не просто сірою лінією.
    layer(
        id = "obsidian-rail-tie",
        type = "line",
        sourceLayer = SL_TRANSPORTATION,
        minzoom = 13,
        filter = all(notBrunnel(), classIn("rail")),
        paint = jobj(
            "line-color" to jstr(ink.railTie),
            "line-dasharray" to dashRamp(2.0, 9.0, listOf(16.0, 19.0)) { curveAt(TIE_WIDTH, it) },
            "line-width" to zoomRamp(1.3, TIE_WIDTH),
        ),
    ),
    layer(
        id = "obsidian-rail-service",
        type = "line",
        sourceLayer = SL_TRANSPORTATION,
        minzoom = 12,
        filter = all(notBrunnel(), classIn("transit")),
        paint = jobj(
            "line-color" to jstr(ink.railService),
            "line-width" to zoomRamp(1.3, listOf(12.0 to 0.4, 18.0 to 1.8)),
        ),
    ),
)

/**
 * Ґрунт і замок — не стилістика, а два питання екіпажу: «під колесами асфальт
 * чи ґрунт?» і «мене сюди пустять?». Обидва вже їдуть у тайлі полями `surface`
 * і `access`; чужий стиль читає з них нуль.
 *
 * ⚠ Дорога БЕЗ поля `surface` не «асфальт», а «невідомо», і жоден із цих шарів
 * її не чіпає. Мовчання не отримує сходинки — інакше мапа обіцяла б покриття,
 * якого не міряла.
 */
private fun honestyLayers(ink: CartoInk): List<Json.Obj> = listOf(
    layer(
        id = "obsidian-road-unpaved",
        type = "line",
        sourceLayer = SL_TRANSPORTATION,
        minzoom = UNPAVED_FROM,
        filter = all(
            classIn(*ALL_CLASSES),
            has("surface"),
            oneOf(
                get("surface"),
                "unpaved", "gravel", "fine_gravel", "compacted", "dirt", "earth",
                "ground", "sand", "mud", "grass", "pebblestone", "wood", "woodchips",
            ),
        ),
        layout = jobj("line-cap" to jstr("round")),
        paint = jobj(
            "line-color" to jstr(ink.roadUnpaved),
            "line-dasharray" to dashRamp(1.8, 3.0, listOf(15.0, 18.0)) {
                fill(rank("obsidian-road-minor"), it, 0.55)
            },
            "line-opacity" to jnum(0.85),
            "line-width" to rankRamp(RANKS, UNPAVED_FROM) { r, z -> fill(r, z, 0.55) },
        ),
    ),
    layer(
        id = "obsidian-road-closed",
        type = "line",
        sourceLayer = SL_TRANSPORTATION,
        minzoom = CLOSED_FROM,
        filter = all(classIn(*ALL_CLASSES), oneOf(get("access"), "no", "private")),
        layout = jobj("line-cap" to jstr("butt")),
        paint = jobj(
            "line-color" to jstr(ink.roadClosed),
            "line-dasharray" to dashRamp(1.6, 3.0, listOf(16.0, 19.0)) {
                fill(rank("obsidian-road-minor"), it, 0.8)
            },
            "line-width" to rankRamp(RANKS, CLOSED_FROM) { r, z -> fill(r, z, 0.8) },
        ),
    ),
)
