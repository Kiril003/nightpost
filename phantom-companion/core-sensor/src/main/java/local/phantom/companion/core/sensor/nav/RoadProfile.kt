package local.phantom.companion.core.sensor.nav

/**
 * What a road actually is, to the man on it.
 *
 * The router used to cost every way at its plain length: a courtyard driveway,
 * a locked service gate and Хрещатик were the same road at 1.3 m/s. That is the
 * whole of «маршрут тупий — веде через паркан і прибудинкову територію». OSM
 * has said otherwise all along, in tags the import discarded.
 *
 * Two answers, deliberately separated:
 *  - [blocked] — may not be used at all. Legal or physical: `access=private`,
 *    `foot=no`, a motorway under a pedestrian.
 *  - [costFactor] — usable but worse. A multiplier ≥ 1 on the way's length, so
 *    A*'s straight-line heuristic stays admissible and the route stays optimal
 *    under the penalty. A courtyard is not forbidden — a man may cut through
 *    one — it is merely the last thing to suggest to him.
 *
 * Pure and table-driven: every rule here is a JVM unit test.
 */
object RoadProfile {

    /**
     * ТРИ ПРОФІЛІ, А НЕ ДВА.
     *
     * Цей файл усе життя питав один булевий прапорець `foot`, і це мовчки
     * означало «все, що не пішки — це авто». У застосунку ж є ТРИ профілі:
     * [local.phantom.companion.core.net.map.RouteProfile] несе `BIKE` від
     * народження, чип «вело» стоїть на аркуші мапи, `KinematicsEngine` сам
     * перемикає на нього, коли тіло їде велосипедом, а голосові дієслова
     * розбирають «велосипедом». Усі вони доходили сюди як `foot = false`.
     *
     * ЩО ЦЕ КОШТУВАЛО, заміряно на національному пакеті (2 597 639 доріг):
     *  - `highway=cycleway` (2 664 дороги) лежить у [CAR_FORBIDDEN_CLASSES],
     *    тобто велосипедний маршрут коштував велодоріжку в
     *    [LocalRouter.BLOCKED_PENALTY] = 500× і ОБʼЇЖДЖАВ саме ту
     *    інфраструктуру, задля якої профіль існує;
     *  - `bicycle=no` (9 289 доріг) не читався ніде — маршрут вів туди, куди
     *    велосипедом їхати заборонено;
     *  - `bicycle=designated` (2 030) і `bicycle=yes` (29 075) не давали нічого;
     *  - час рахувався автомобільним темпом, а `maxspeed` — стеля для АВТО —
     *    оголошувався як [SpeedBasis.POSTED], тобто велосипедна тривалість
     *    називала себе «за знаками».
     *
     * Булеві перевантаження нижче лишаються і означають рівно те, що означали
     * (`true` → [FOOT], `false` → [CAR]), тож жоден наявний виклик і жоден
     * наявний тест не змінює поведінки.
     */
    enum class TravelMode { CAR, BIKE, FOOT }

    private fun modeOf(foot: Boolean) = if (foot) TravelMode.FOOT else TravelMode.CAR

    /** Access values that mean "not yours to walk through". */
    private val CLOSED_ACCESS = setOf("private", "no", "customers", "permit", "military")

    /** Values that mean "yes" in an access-style tag. */
    private val OPEN_ACCESS = setOf("yes", "permissive", "designated", "public", "destination")

    /**
     * АВТОМАГІСТРАЛЬ — and only автомагістраль.
     *
     * ПДР forbid pedestrians on автомагістралі; OSM tags 33 of them `motorway`.
     * `trunk` used to sit in this set too, and `trunk` is 16 936 objects — the
     * ordinary М- and Н-network, where walking is legal and normal. Blocking it
     * costs [LocalRouter.BLOCKED_PENALTY] = 500×, so the router accepted a path
     * ~250× longer over tracks and verges rather than touch asphalt.
     *
     * There is no careful discriminator to be careful with: `motorroad` is
     * tagged on 37 of 20 907 trunk ways (0.18%), `foot=no` on 2.2%. So the
     * asymmetry of harm decides it. Being hit by a vehicle on a shoulder is
     * visible, known and mitigable; a mine on an unmapped verge in a
     * de-occupied oblast is invisible and fatal — and OSM holds ZERO mine data,
     * so this app cannot route around one. Asphalt is the surface demined by
     * use.
     *
     * Allowed, therefore, and priced: [TRUNK_FOOT_PENALTY].
     */
    private val FOOT_FORBIDDEN_CLASSES = setOf("motorway", "motorway_link")

    private val TRUNK_CLASSES = setOf("trunk", "trunk_link")

    /** `sidewalk` values that mean OSM has actually recorded a pavement here. */
    private val HAS_PAVEMENT = setOf("yes", "both", "left", "right", "separate")

    private val CAR_FORBIDDEN_CLASSES =
        setOf("footway", "path", "steps", "pedestrian", "cycleway", "bridleway", "corridor")

    /**
     * Класи ґрунту під колесом і під ногою — і чому одного набору замало.
     *
     * Досі тут стояв ОДИН плаский набір [ROUGH_SURFACES] з одним множником на
     * профіль (1,35 пішки / 1,8 авто), і все, чого в наборі не було, коштувало
     * рівно нічого. Перепис національного пакета (13.09.2026, за унікальними
     * дорогами з 2 597 639) показує, що саме пропускалось:
     *
     *   paving_stones 33 044 · sett 5 270 · cobblestone 1 702 — бруківка.
     *       Для авто майже дрібниця, для ВЕЛОСИПЕДА — найгірше покриття, яке
     *       взагалі буває на асфальтованій вулиці.
     *   compacted 10 813 · fine_gravel 1 901 — утрамбована ґрунтівка. Була
     *       безкоштовною (немає в наборі), хоч це не асфальт.
     *   concrete:plates 3 306 · concrete:lanes 586 — плити. Те саме.
     *   wood 1 185 — дощаний настил: слизький під дощем.
     *   mud 850 · sand 11 859 — лежали в одному кошику з `gravel`, хоч пісок і
     *       болото гірші за жорству на порядок.
     *
     * Три ряди, по одному на профіль, у порядку [TravelMode]: CAR, BIKE, FOOT.
     *
     * ЩО ЗБЕРЕЖЕНО І ЩО ЗМІНЕНО, точно. Ряд [LOOSE] узятий не на око — він
     * СКЛАДЕНИЙ зі старих констант [CAR_ROUGH_PENALTY] і
     * [ROUGH_SURFACE_PENALTY], тож для семи з девʼяти членів старого
     * [ROUGH_SURFACES] (`ground` 103 368, `unpaved` 61 021, `gravel` 28 175,
     * `dirt` 14 228, `grass` 9 318, `earth` 1 238, `pebblestone` 546) число не
     * зрушилось ані на йоту, і жоден уже правильний маршрут не переставлено.
     *
     * Двоє переїхали НАВМИСНО: `sand` (11 859) і `mud` (850) лежали в одному
     * кошику з жорствою, хоч це різні речі — по жорстві їдуть, у піску
     * закопуються. Вони й тільки вони йдуть у [SOFT].
     */
    private enum class Ground(val car: Double, val bike: Double, val foot: Double) {
        /** Асфальт і бетон: еталон, 1,0 для всіх. */
        PAVED(1.0, 1.0, 1.0),

        /** Бруківка, плити, настил — твердо, але трясе. */
        PAVED_ROUGH(1.15, 1.90, 1.05),

        /** Утрамбоване: не асфальт, але й не багно. */
        COMPACTED(1.25, 1.40, 1.10),

        /** Жорства, ґрунт, трава — старий [ROUGH_SURFACES] без піску й болота,
         *  і буквально старими константами, щоб це було видно, а не обіцяно. */
        LOOSE(CAR_ROUGH_PENALTY, 2.40, ROUGH_SURFACE_PENALTY),

        /** Пісок і болото: те, де застрягають. */
        SOFT(2.60, 3.50, 1.80),
        ;

        fun of(mode: TravelMode): Double = when (mode) {
            TravelMode.CAR -> car
            TravelMode.BIKE -> bike
            TravelMode.FOOT -> foot
        }
    }

    private val SURFACE_GROUND: Map<String, Ground> = buildMap {
        listOf("asphalt", "concrete", "paved", "metal", "chipseal")
            .forEach { put(it, Ground.PAVED) }
        listOf("paving_stones", "sett", "cobblestone", "unhewn_cobblestone", "wood", "concrete:plates", "concrete:lanes", "bricks")
            .forEach { put(it, Ground.PAVED_ROUGH) }
        listOf("compacted", "fine_gravel", "gravel_turf")
            .forEach { put(it, Ground.COMPACTED) }
        listOf("ground", "dirt", "earth", "grass", "gravel", "pebblestone", "unpaved", "woodchips", "rock", "stone")
            .forEach { put(it, Ground.LOOSE) }
        listOf("sand", "mud", "salt", "snow", "ice")
            .forEach { put(it, Ground.SOFT) }
    }

    /**
     * Slow, unpleasant or unmade ground, foot profile — збережено як БУВ, бо
     * його читає [resolveSpeed] і зміна набору тихо змінила б темп ходьби.
     */
    private val ROUGH_SURFACES =
        setOf("ground", "dirt", "earth", "mud", "sand", "grass", "gravel", "pebblestone", "unpaved")

    /**
     * ГЛАДКІСТЬ — найбільший невикористаний сигнал у пакеті, і це заміряно.
     *
     * `smoothness` стоїть на **118 047** унікальних дорогах національного
     * пакета — БІЛЬШЕ, ніж `maxspeed` (123 590 — той самий порядок) і в
     * чотирнадцять разів більше за `sidewalk`. Ключ роками лежав у
     * [RoadWayCodec.routingKeys], тобто доїжджав до кожного телефона, і його
     * не читав ніхто.
     *
     * Він каже те, чого `surface` сказати не може: асфальт буває цілий і
     * буває розбитий, і в країні, де по трасах їздить важка техніка, це
     * різниця між дорогою і ямами. Розподіл у пакеті:
     *   intermediate 33 665 · good 30 024 · bad 27 535 · excellent 10 727 ·
     *   very_bad 7 484 · horrible 4 824 · very_horrible 3 027 · impassable 667
     *
     * Тобто **15 002 дороги** мають `very_bad` або гірше — це саме ті, які
     * маршрутизатор досі пропонував нарівні зі свіжим асфальтом.
     *
     * Три ряди, як і в [Ground]: яма, що коштує авто півмаршруту, пішоходу не
     * коштує майже нічого, а велосипеду — найдорожче з трьох.
     */
    private enum class Smooth(val car: Double, val bike: Double, val foot: Double) {
        EXCELLENT(1.0, 1.0, 1.0),
        GOOD(1.0, 1.0, 1.0),
        INTERMEDIATE(1.10, 1.30, 1.00),
        BAD(1.50, 2.00, 1.05),
        VERY_BAD(2.20, 3.00, 1.15),
        HORRIBLE(3.50, 4.50, 1.30),
        VERY_HORRIBLE(5.00, 6.00, 1.50),

        /**
         * `impassable` не має ряду для авто й велосипеда: вони сюди не
         * потраплять узагалі ([blocked]). Число лишається для пішохода — він
         * єдиний, хто справді може пройти те, чим не проїдеш.
         */
        IMPASSABLE(1.0, 1.0, 2.00),
        ;

        fun of(mode: TravelMode): Double = when (mode) {
            TravelMode.CAR -> car
            TravelMode.BIKE -> bike
            TravelMode.FOOT -> foot
        }
    }

    private val SMOOTHNESS: Map<String, Smooth> = mapOf(
        "excellent" to Smooth.EXCELLENT,
        "very_good" to Smooth.EXCELLENT,
        "good" to Smooth.GOOD,
        // `normal` — не словникове значення OSM, але в пакеті їх 8, і людина
        // писала його замість `good`. Синонім дешевший за мовчання.
        "normal" to Smooth.GOOD,
        "intermediate" to Smooth.INTERMEDIATE,
        "medium" to Smooth.INTERMEDIATE,
        "bad" to Smooth.BAD,
        "very_bad" to Smooth.VERY_BAD,
        "horrible" to Smooth.HORRIBLE,
        "very_horrible" to Smooth.VERY_HORRIBLE,
        "impassable" to Smooth.IMPASSABLE,
    )

    /**
     * ВЕЛОСИПЕД у [blocked]: класи, куди йому не можна за ПДР.
     *
     * Той самий список, що й пішоходу, і з тієї самої причини — автомагістраль
     * закрита обом. `corridor` — це шлях ВСЕРЕДИНІ будівлі; велосипед туди не
     * заїжджає, а пішохід може. `steps` тут НЕМАЄ навмисно: сходами велосипед
     * ведуть руками, і це незручність ([BIKE_STEPS_PENALTY]), а не заборона —
     * інакше міст через колію без пандуса розривав би мережу надвоє.
     */
    private val BIKE_FORBIDDEN_CLASSES = setOf("motorway", "motorway_link", "corridor")

    /**
     * `bicycle=use_sidepath` — це заборона, а не незручність, і тільки для
     * велосипеда.
     *
     * Значення означає буквально «тут їхати не можна, є окрема велодоріжка».
     * У пакеті таких 154 дороги. Заборона тут безпечна саме тому, що
     * [LocalRouter.BLOCKED_PENALTY] скінченний: якщо тієї велодоріжки в графі
     * поруч немає, маршрут не зникне — він просто вибере цю дорогу останньою.
     *
     * Для ПІШОГО те саме значення (`foot=use_sidepath`, 63 дороги) свідомо
     * лишається поза забороною: йти узбіччям там, де поруч є тротуар, — це
     * незручність, а не порушення, і зміна тут переставляла б пішохідні
     * маршрути без жодної нової підстави.
     */
    private val BIKE_CLOSED_ACCESS = CLOSED_ACCESS + "use_sidepath"

    fun blocked(tags: Map<String, String>, foot: Boolean): Boolean = blocked(tags, modeOf(foot))

    fun blocked(tags: Map<String, String>, mode: TravelMode): Boolean {
        val highway = tags["highway"]
        if (highway == "construction" || highway == "proposed" || highway == "raceway") return true

        // The specific mode always overrides the general `access`: a private
        // road tagged `foot=yes` is a public footpath through private land, and
        // OSM says so exactly that way.
        //
        // Для велосипеда ключ — `bicycle`, а запасний — `vehicle`, бо в OSM (і
        // в ПДР) велосипед Є транспортним засобом: `vehicle=no` закриває його
        // так само, як авто. `motor_vehicle` до нього НЕ застосовується —
        // пішохідна зона з `motor_vehicle=no` для велосипеда відкрита.
        val specific = when (mode) {
            TravelMode.FOOT -> tags["foot"]
            TravelMode.BIKE -> tags["bicycle"] ?: tags["vehicle"]
            TravelMode.CAR -> tags["motor_vehicle"] ?: tags["vehicle"]
        }
        if (specific in OPEN_ACCESS) return false
        if (specific in if (mode == TravelMode.BIKE) BIKE_CLOSED_ACCESS else CLOSED_ACCESS) return true

        if (tags["access"] in CLOSED_ACCESS) return true

        // НЕПРОХІДНО — єдине нове ПРАВИЛО-ЗАБОРОНА в цьому файлі, і воно
        // найобережніше з можливих: не ми вирішили, що тут не проїхати, це
        // написав картограф словом `impassable`. По країні таких доріг 667.
        // Пішохода це не стосується: він єдиний, хто проходить те, чим не
        // проїжджають, і саме тому в [Smooth.IMPASSABLE] у нього є число.
        if (mode != TravelMode.FOOT && tags["smoothness"] == "impassable") return true

        when (mode) {
            TravelMode.FOOT -> {
                if (highway in FOOT_FORBIDDEN_CLASSES) return true
                // `motorroad=yes` is the sign that turns an ordinary trunk into one
                // a man may not walk. It is on 0.18% of trunk ways — which is why
                // the other 99.82% must not be blocked on its behalf.
                if (tags["motorroad"] == "yes") return true
            }
            TravelMode.BIKE -> {
                if (highway in BIKE_FORBIDDEN_CLASSES) return true
                if (tags["motorroad"] == "yes") return true
            }
            TravelMode.CAR -> {
                if (highway in CAR_FORBIDDEN_CLASSES) return true
                if (tags["service"] == "parking_aisle" && tags["access"] in CLOSED_ACCESS) return true
            }
        }
        return false
    }

    /**
     * How much worse than its own length this way is, for [foot] or driving.
     *
     * The courtyard number is the one that matters. `highway=service` with
     * `service=driveway` is «прибудинкова територія»: legally passable, socially
     * not a route, and the thing the operator kept being sent through. At 3.5×
     * the router will still use one to reach a door twenty metres away — which
     * is correct, that is what driveways are for — and will not thread four of
     * them together to save a hundred metres of pavement.
     */
    fun costFactor(tags: Map<String, String>, foot: Boolean): Double = costFactor(tags, modeOf(foot))

    fun costFactor(tags: Map<String, String>, mode: TravelMode): Double {
        var factor = 1.0
        val highway = tags["highway"]
        val service = tags["service"]

        if (highway == "service") {
            factor *= when (service) {
                "driveway" -> COURTYARD_PENALTY
                "parking_aisle" -> PARKING_AISLE_PENALTY
                "alley" -> ALLEY_PENALTY
                else -> SERVICE_PENALTY
            }
        }

        // `destination` is «доступ лише для тих, кому туди треба» — passable,
        // but never a through route.
        //
        // `motor_vehicle=destination` (2 513 доріг) звужено до АВТО, і це
        // виправлення, а не смак: тег говорить про машини, а штраф за нього
        // досі платив і пішохід. «Проїзд лише до місця призначення» не робить
        // вулицю гіршою під ногами.
        if (tags["access"] == "destination" ||
            (mode == TravelMode.CAR && tags["motor_vehicle"] == "destination")
        ) {
            factor *= DESTINATION_PENALTY
        }

        // ҐРУНТ І ГЛАДКІСТЬ — тепер для ВСІХ трьох профілів з однієї таблиці.
        // Доти `surface` читався двома окремими рядками з двома константами, а
        // `smoothness` не читався ніде; обидва ключі приїжджають у пакеті
        // (див. [RoadMeshStore] ROUTING_KEYS), тобто дані лежали на телефоні й
        // мовчали.
        factor *= groundOf(tags).of(mode)
        factor *= smoothOf(tags).of(mode)

        when (mode) {
            TravelMode.FOOT -> {
                if (isTrunkShoulder(tags)) factor *= TRUNK_FOOT_PENALTY
                if (highway == "track") factor *= TRACK_PENALTY
                if (highway == "steps") factor *= STEPS_PENALTY
                // A road with no pavement is walkable and horrible.
                if (highway in setOf("primary", "secondary") && tags["sidewalk"] == "no") {
                    factor *= NO_SIDEWALK_PENALTY
                }
            }
            // Один щабель на дорогу: [bikeClassPenalty] уже знає і про клас, і
            // про те, що на цій ділянці велосипед ведуть ([pushedCost]).
            TravelMode.BIKE -> factor *= bikeClassPenalty(highway, tags["bicycle"])
            TravelMode.CAR -> {
                factor *= classPenalty(highway)
                if (highway == "living_street") factor *= LIVING_STREET_PENALTY
                if (highway == "track") factor *= CAR_TRACK_PENALTY
            }
        }
        return factor
    }

    private fun groundOf(tags: Map<String, String>): Ground =
        SURFACE_GROUND[tags["surface"]] ?: Ground.PAVED

    private fun smoothOf(tags: Map<String, String>): Smooth =
        SMOOTHNESS[tags["smoothness"]] ?: Smooth.EXCELLENT

    /**
     * ВЕЛОСИПЕДНІ ЩАБЛІ — і чесно про те, чим вони є.
     *
     * Це НЕ вимір. Вимір тут неможливий: у пакеті немає ані `cycleway`, ані
     * `segregated`, ані `tracktype` — [RoadMeshStore] їх не пише, тож на
     * телефоні їх нема (перевірено на національному пакеті: нуль доріг з
     * кожним із трьох). Є рівно те, з чим можна працювати: `highway`,
     * `bicycle`, `surface`, `smoothness`.
     *
     * Тому це — ПЕРЕВАГА з оголошеним допуском, рівно як [CAR_CLASS_PENALTY]
     * про себе й каже: відношення двох щаблів — це те, у скільки разів довшу
     * дорогу маршрутизатор погодиться взяти, аби опинитись на кращій. Читати
     * так: велодоріжка (1,0) виграє в первинної (2,0), поки вона не довша за
     * неї вдвічі.
     *
     * Дві межі тримають таблицю:
     *  - знизу 1,0, бо [RoadGraph.routeTraced] однаково затискає множник до
     *    ≥ 1: «любити велодоріжку» технічно означає «дорожчити все інше», і
     *    тому велодоріжка — це еталон, а не знижка;
     *  - зверху 3,5 = [COURTYARD_PENALTY], бо все, що дорожче за двір, — це
     *    маршрут, який плететься дворами, аби не виїхати на вулицю.
     *
     * Два щаблі стоять поза цією стелею, і жоден з них — не смак. `trunk`:
     * там ціна помилки не в незручності. `steps`: його множник
     * [BIKE_STEPS_PENALTY] узагалі не перевага, а ВИВЕДЕНЕ відношення двох
     * швидкостей, які вже стоять у цьому файлі.
     *
     * Що каже `bicycle`:
     *  - `designated` (2 030 доріг) — це оголошена велоінфраструктура, хай
     *    навіть намальована по вулиці; щабель падає до 1,0.
     *  - `yes` (29 075) НЕ дає нічого, і це навмисно: дозвіл — не комфорт.
     *    «Тут можна їхати» нічого не каже про потік машин поруч.
     */
    private fun bikeClassPenalty(highway: String?, bicycle: String?): Double {
        pushedCost(highway, bicycle)?.let { return it }
        if (bicycle == "designated") return 1.0
        if (highway == null) return 1.0
        return BIKE_CLASS_PENALTY[highway] ?: BIKE_LOCAL_PENALTY
    }

    /**
     * ЙОГО ВЕДУТЬ РУКАМИ — один стан замість трьох окремих винятків, і жодного
     * числа зі стелі: ціна пішої ділянки під велосипедом — це відношення
     * велосипедного темпу до того, яким її насправді долають.
     *
     * Коли велосипед ведуть:
     *  - `highway=steps` (25 109) — несуть, завжди, хоч би що казав `bicycle`;
     *  - `bicycle=dismount` (1 466) — так написано;
     *  - тротуар і пішохідна зона (`footway` 310 724, `pedestrian` 2 965,
     *    `bridleway` 243), ЯКЩО `bicycle` прямо не дозволяє їхати. Дозволених
     *    у пакеті 8 810 з 310 724 тротуарів — тобто на 97% з них їдуть не
     *    законно, а пішки.
     *
     * ЧОМУ НЕ ЗАБОРОНА. Тротуарів у країні 310 724 — це 12% усієї мережі.
     * Заборона розірвала б її на клапті саме в містах, де велосипед і їде, і
     * то заради правила, яке людина обходить, зійшовши з сідла. «Повільно й
     * незручно» — це те, що тут відбувається насправді.
     *
     * ЧОМУ НЕ 2,0, ЯК БУЛО СПОЧАТКУ в цій хвилі. Бо 2,0 — це число зі стелі, а
     * 4,2 ÷ 1,3 — ні.
     */
    private fun pushedCost(highway: String?, bicycle: String?): Double? = when {
        highway == "steps" -> BIKE_STEPS_PENALTY
        bicycle == "dismount" -> BIKE_DISMOUNT_PENALTY
        highway in BIKE_PUSH_CLASSES && bicycle !in OPEN_ACCESS -> BIKE_DISMOUNT_PENALTY
        else -> null
    }

    /** Темп, яким ця ділянка долається насправді, або null — на ній їдуть. */
    private fun pushedSpeed(highway: String?, bicycle: String?): Double? = when {
        highway == "steps" -> STEP_SPEED
        bicycle == "dismount" -> LocalRouter.FOOT_SPEED_MPS
        highway in BIKE_PUSH_CLASSES && bicycle !in OPEN_ACCESS -> LocalRouter.FOOT_SPEED_MPS
        else -> null
    }

    private val BIKE_PUSH_CLASSES = setOf("footway", "pedestrian", "bridleway")

    private val BIKE_CLASS_PENALTY = mapOf(
        // Інфраструктура, заради якої профіль існує. Досі коштувала 500×.
        "cycleway" to 1.0,
        // Житлова мережа — те, чим велосипед і їде.
        "residential" to 1.15, "living_street" to 1.15, "unclassified" to 1.20,
        // Стежка: сама по собі не гірша за вулицю, а ґрунт під нею вже
        // оплачено окремо в [Ground] — тут не можна платити двічі.
        "path" to 1.15,
        "track" to 1.50,
        // Чим більший клас, тим щільніший потік поруч.
        "tertiary" to 1.35, "tertiary_link" to 1.35,
        "secondary" to 1.70, "secondary_link" to 1.70,
        "primary" to 2.00, "primary_link" to 2.00,
        // Магістраль: їхати законно, але це та сама смуга, де вантажівка
        // проходить у метрі. Половина від [TRUNK_FOOT_PENALTY] — пішохід
        // повільніший і йому нікуди подітись, велосипед іде потоком і сходить
        // на узбіччя; але порядок величини той самий, бо загроза та сама.
        "trunk" to 3.50, "trunk_link" to 3.50,
        // Тротуар, пішохідна зона й сходи сюди НЕ входять навмисно: там
        // велосипед ведуть, і ціну цього рахує [pushedCost] з двох швидкостей,
        // а не з цієї таблиці. Дозволений `bicycle=yes` на тротуарі повертає
        // його сюди — тоді це звичайна місцева дорога.
        "footway" to BIKE_LOCAL_PENALTY, "pedestrian" to BIKE_LOCAL_PENALTY,
        "bridleway" to BIKE_LOCAL_PENALTY,
    )

    /** Клас, якого таблиця не знає, — місцева дорога, не велодоріжка. */
    private const val BIKE_LOCAL_PENALTY = 1.20

    /**
     * УЗБІЧЧЯ — true when this way is the national network AND the data says
     * nothing about a pavement on it.
     *
     * Two claims, and the difference is the whole point. `sidewalk=both` on a
     * trunk is OSM stating a pavement exists — an urban trunk through a town is
     * then an ordinary street to walk, and [costFactor] charges it nothing.
     * Absent, the honest reading is not «є узбіччя» but «невідомо»: `sidewalk`
     * and `shoulder` are simply not mapped on most of the network. That is why
     * anything shown to the operator must say «частина шляху — узбіччям
     * магістралі» and must NOT say a shoulder is there.
     *
     * `sidewalk=no` lands here deliberately: it is knowledge that there is no
     * pavement, which is exactly the case [TRUNK_FOOT_PENALTY] is priced for.
     */
    fun isTrunkShoulder(tags: Map<String, String>): Boolean =
        tags["highway"] in TRUNK_CLASSES && tags["sidewalk"] !in HAS_PAVEMENT

    /**
     * БРІД — чи OSM позначив на цій дорозі переправу вбрід.
     *
     * ЦЕ ВЕСЬ ЗМІСТ ФУНКЦІЇ, і саме тому вона стоїть тут, а не всередині
     * [blocked] чи [costFactor]. Ні той, ні інший `ford` не читає, і це не
     * забутий рядок — це рішення.
     *
     * Чому не заборона. «Тут брід» не означає «тут не проїде»: більшість
     * бродів у країні щодня проїжджають, а по деяких ходять пішки. Заборонити
     * означало б вигадати непрохідність.
     *
     * Чому не штраф. Штраф — це теж твердження, лише тихе: він каже «ця дорога
     * гірша за іншу рівно в N разів», а N нема звідки взяти. Глибини OSM по
     * Україні практично не несе, сезону не несе, а рівень води — величина, що
     * змінюється за годину після дощу. Множник, узятий зі стелі, тихо
     * переставляв би маршрути в обидва боки і виглядав би як знання.
     *
     * Що лишається чесним і корисним: ПОКАЗАТИ, що переправа на шляху є, і
     * лишити рішення тому, хто стоїть на березі й бачить воду. Той самий
     * підхід проєкт уже свідомо обрав щодо криги (див. відмову оцінювати
     * придатність льодових переправ) — ціна помилки однакова.
     *
     * `ford=no` — це знання, що броду тут НЕМА (звичайно на мосту поруч зі
     * старим переїздом), тож воно не рахується як брід.
     */
    fun marksFord(tags: Map<String, String>): Boolean {
        val v = tags["ford"] ?: return false
        return v.isNotEmpty() && v != "no"
    }

    /**
     * ТРАСА — how much worse than the national network this class is, driving.
     *
     * Measured, because the received wisdom is wrong here: the Ukraine extract
     * holds 33 `highway=motorway` and 16 936 `highway=trunk`. The М- and
     * Н-roads — every corridor between two oblast capitals — are `trunk`. A
     * profile that reserves the top tier for `motorway` reserves it for
     * thirty-three objects and ranks the actual national network with regional
     * roads; the router then answers a 300 km corridor with a patchwork of
     * village streets because they happened to be shorter.
     *
     * The correction can only run downwards: [RoadGraph.routeTraced] clamps
     * every way factor to ≥ 1, so preferring a trunk means charging everything
     * else, and the ratio is a detour tolerance — at 1.6 the router will accept
     * a national road up to 1.6× the length of the residential alternative.
     * The ceiling is this file's own speed table, which implies 3.4×; a router
     * costing DISTANCE must stay well under what a router costing TIME would
     * claim, because below `trunk` the class is a guess on 94% of ways.
     *
     * Two rules earn their lines:
     *  - a way with NO `highway` at all stays 1.0 — the migration promise, and
     *    the reason this cannot be pushed higher: an untagged way is already
     *    priced as generously as a trunk, so the wider the ladder the more the
     *    router prefers the roads it knows least. Re-baked packs carry a class
     *    on every way, which is what actually closes that gap.
     *  - a `highway` value the table does not know is LOCAL, not national. We
     *    know what an М-road is tagged; anything else is not one.
     */
    private fun classPenalty(highway: String?): Double {
        if (highway == null) return 1.0
        return CAR_CLASS_PENALTY[highway] ?: LOCAL_ROAD_PENALTY
    }

    /**
     * РЕЄСТР — звідки взялася швидкість, за якою пораховано час.
     *
     * Час, виведений із реального знака, і час, виведений із припущення, — це
     * два різні твердження, і той, хто їде, має право знати, яке з них перед
     * ним. Тому [speedMps] і [speedBasis] рахуються з ОДНОГО рішення й не
     * можуть розійтися.
     */
    enum class SpeedBasis {
        /** `maxspeed` на самій дорозі — знак, а не здогад. */
        POSTED,

        /** Знака немає, але клас дороги відомий: таблиця нижче. */
        CLASS,

        /** Про дорогу не відомо нічого — рівно та сама пласка швидкість,
         *  що й до появи тегів. Це припущення, і воно назване припущенням. */
        ASSUMED,
    }

    /** Metres per second this way actually allows, replacing the flat guess. */
    fun speedMps(tags: Map<String, String>, foot: Boolean): Double = speedMps(tags, modeOf(foot))

    /** Which claim [speedMps] is entitled to make for this way. */
    fun speedBasis(tags: Map<String, String>, foot: Boolean): SpeedBasis = speedBasis(tags, modeOf(foot))

    fun speedMps(tags: Map<String, String>, mode: TravelMode): Double = resolveSpeed(tags, mode).first

    fun speedBasis(tags: Map<String, String>, mode: TravelMode): SpeedBasis = resolveSpeed(tags, mode).second

    private fun resolveSpeed(tags: Map<String, String>, mode: TravelMode): Pair<Double, SpeedBasis> {
        return when (mode) {
            // A posted limit is a car's ceiling; nobody walks faster for it.
            TravelMode.FOOT -> when {
                tags["highway"] == "steps" -> STEP_SPEED to SpeedBasis.CLASS
                tags["surface"] in ROUGH_SURFACES -> ROUGH_FOOT_SPEED to SpeedBasis.CLASS
                else -> LocalRouter.FOOT_SPEED_MPS to SpeedBasis.ASSUMED
            }
            TravelMode.BIKE -> bikeSpeed(tags)
            TravelMode.CAR -> {
                val posted = postedKmh(tags["maxspeed"])
                if (posted != null && posted in 1..130) {
                    posted * KMH_TO_MPS * URBAN_REALISM to SpeedBasis.POSTED
                } else {
                    val byClass = CLASS_PACE_MPS[tags["highway"]]
                    if (byClass != null) byClass to SpeedBasis.CLASS
                    else LocalRouter.CAR_SPEED_MPS to SpeedBasis.ASSUMED
                }
            }
        }
    }

    /**
     * ТЕМП ВЕЛОСИПЕДА — і чому [CLASS_PACE_MPS] тут не при чому.
     *
     * Та таблиця — автомобільна, і підігнана вона на автомобільних треках:
     * велосипед не їде по trunk 58 км/год. `maxspeed` теж не про нього — це
     * стеля для машини, і саме тому велосипедна тривалість, порахована старим
     * шляхом, оголошувала себе [SpeedBasis.POSTED], тобто «за знаками».
     *
     * Тому темп будується з того, що велосипеда справді стосується:
     * [LocalRouter.BIKE_SPEED_MPS], поділена на ті самі [Ground] і [Smooth],
     * які вже оплачують ту саму дорогу в [costFactor]. Одне джерело на два
     * споживачі — вартість і час не можуть розійтися.
     *
     * ТРИ ВИНЯТКИ, кожен з них — не оцінка, а факт:
     *  - `highway=steps`: велосипед сходами НЕСУТЬ, і швидше за [STEP_SPEED] це
     *    не робиться;
     *  - `bicycle=dismount`: написано «зійти» — далі темп пішохідний;
     *  - знак НИЖЧЕ за велосипедний темп (у пакеті `maxspeed=5` стоїть на
     *    3 225 дорогах, `10` — на 2 918): 5 км/год стосується і велосипеда, бо
     *    він транспортний засіб. Тоді — і тільки тоді — підстава [SpeedBasis.POSTED],
     *    бо саме знак вирішив число. Без [URBAN_REALISM]: той множник
     *    описує машину, що не доїжджає до стелі в потоці, а не знак у дворі.
     *
     * ПІДСТАВА рахується строго: [SpeedBasis.CLASS] тільки тоді, коли дані
     * справді ЗМІНИЛИ число. Дорога без тегів дістає рівно
     * [LocalRouter.BIKE_SPEED_MPS] і чесне [SpeedBasis.ASSUMED] — та сама
     * обіцянка міграції, що її тримають два інші профілі.
     */
    private fun bikeSpeed(tags: Map<String, String>): Pair<Double, SpeedBasis> {
        val base = LocalRouter.BIKE_SPEED_MPS
        val ridden = pushedSpeed(tags["highway"], tags["bicycle"])
            // Штовхати велосипед завжди можна, тож темп не падає нижче за
            // пішохідний, хоч який поганий ґрунт.
            ?: (base / (groundOf(tags).bike * smoothOf(tags).bike))
                .coerceAtLeast(LocalRouter.FOOT_SPEED_MPS)
        val posted = postedKmh(tags["maxspeed"])
        if (posted != null && posted in 1..130) {
            val limit = posted * KMH_TO_MPS
            if (limit < ridden) return limit to SpeedBasis.POSTED
        }
        return ridden to if (ridden == base) SpeedBasis.ASSUMED else SpeedBasis.CLASS
    }

    /**
     * The ladder itself. Ramps ride with their parent — a router that dodged
     * the slip road could not reach the road it serves.
     *
     * `living_street`, `service` and `track` sit on the bottom rung and then
     * pay their own penalties ON TOP, which is deliberate: it keeps every
     * margin this file already had. A driveway stayed 3.5× a street after the
     * ladder because both moved; had the bottom rung been left at 1.0 the
     * courtyard fix would have been quietly halved, and a `living_street` at
     * 1.4 would have become CHEAPER than the ordinary street beside it.
     */
    private val CAR_CLASS_PENALTY = mapOf(
        "motorway" to 1.0, "motorway_link" to 1.0, "trunk" to 1.0, "trunk_link" to 1.0,
        "primary" to 1.15, "primary_link" to 1.15,
        "secondary" to 1.30, "secondary_link" to 1.30,
        // 1.45, not 1.40: two legs of a national road round a corner are √2 ≈
        // 1.414 times the diagonal through the village, and at 1.40 the router
        // still took the diagonal.
        "tertiary" to 1.45, "tertiary_link" to 1.45,
    )

    /** Anything below tertiary, plus every class the table does not recognise. */
    private const val LOCAL_ROAD_PENALTY = 1.60

    /** ТЕМП, а не стеля — і в назві стоїть саме `PACE`, бо на цьому й
     *  спіткнулись. Числа тут УЖЕ реалістичні: множити їх на [URBAN_REALISM]
     *  не можна, інакше пробіг оплачується двічі.
     *
     *  ЩО ТУТ БУЛО НЕ ТАК. Обмеження з тегу множилось на 0,7, а ця таблиця —
     *  ні. Тож траса з написаним «90» рахувалась як 63 км/год, а точнісінько
     *  така сама траса БЕЗ тегу — як 90. Одна дорога, і без даних вона
     *  «швидша» на 43%. Заміряно 05.09 на свіжому пакеті України з
     *  географічним відкладанням (109 квадратів 1°×1°, пʼять згорток):
     *  стара таблиця завищувала на **100,0% метрів trunk** поза навчанням,
     *  медіана помилки 27 км/год. Загальне число виглядало пристойно лише
     *  тому, що завищення на житлових гасило заниження на проїздах.
     *
     *  ЯК ПІДІБРАНО. Не за медіаною швидкості, а за **гармонійним** темпом
     *  (сума відстані / сума справжнього часу), бо єдиний споживач таблиці —
     *  `тривалість = довжина / темп`, а час не усереднюється лінійно.
     *  Медіанна підгонка давала гарніші 3,8 км/год помилки й ГІРШИЙ час:
     *  −8,67 хв на 10 км проти −6,86 у старої. Гармонійна дає +0,44 —
     *  єдина з трьох, що не оптимістична.
     *
     *  Це SPEED-таблиця, не перевага: «краще по trunk» — це
     *  [CAR_CLASS_PENALTY], і плутати їх не можна. */
    /**
     * Обмеження з тегу, включно з тими, що написані не цифрами.
     *
     * `takeWhile { it.isDigit() }` брав лише провідні цифри, тож усе, що
     * починається з літери, летіло в смітник. Перепис свіжого пакета України
     * (05.09, за унікальними дорогами): **1 923 дороги / 1 025,7 км** мали
     * законне обмеження й не діставались маршрутизатору —
     * `UA:urban` 939 доріг, `RU:rural` 209 доріг але 328 км, `UA:rural` 302,
     * `RU:urban` 436, `RO:urban` 33, `RO:trunk` 3, `BY:rural` 1.
     *
     * Чужі префікси — це не чужа країна, а помилка меню в редакторі: люди
     * ставили пресет сусідньої держави на українську дорогу, здебільшого
     * поблизу кордонів і на сході. Тому декодуємо значення й беремо
     * **менше** з нього та українського закону для того самого контексту:
     * `RU:urban` = 60 стає 50, `RO:trunk` = 100 стає 90. Ніколи не швидше за
     * місцевий закон через те, що хтось клацнув не той рядок у списку.
     *
     * Нижню межу опущено з 5 до 1: значення `1`, `2`, `3` (18 доріг) раніше
     * відкидались, і дорога падала в таблицю класів, яка **швидша** за те,
     * що написано на знаку. Відкидати обмеження заради вищої швидкості — це
     * помилка в бік, у який нам не можна.
     */
    /** Лише для сторожа: таблиця мусить бути перевірна ззовні, інакше межу
     *  «не швидше за закон» нікому тримати. */
    internal fun classPaceForTest(): Map<String, Double> = CLASS_PACE_MPS

    internal fun postedKmh(raw: String?): Int? {
        val v = raw?.trim()?.lowercase() ?: return null
        val digits = v.takeWhile { it.isDigit() }.toIntOrNull()
        if (digits != null) return digits
        val zone = IMPLICIT_ZONES[v] ?: return null
        val ukrainian = UA_ZONES[v.substringAfter(':')] ?: return zone
        return minOf(zone, ukrainian)
    }

    /** Що означає кожен пресет у своїй країні. */
    private val IMPLICIT_ZONES = mapOf(
        "ua:urban" to 50, "ua:rural" to 90, "ua:motorway" to 130,
        "ru:urban" to 60, "ru:rural" to 90, "ru:motorway" to 110,
        "ro:urban" to 50, "ro:rural" to 90, "ro:trunk" to 100, "ro:motorway" to 130,
        "by:urban" to 60, "by:rural" to 90, "by:motorway" to 110,
        "pl:urban" to 50, "pl:rural" to 90, "pl:motorway" to 140,
    )

    /** Український закон для того самого контексту — стеля для чужого пресету. */
    private val UA_ZONES = mapOf("urban" to 50, "rural" to 90, "motorway" to 130, "trunk" to 90)

    private val CLASS_PACE_MPS = mapOf(
        // Змінено. Поруч: скільки відкладених кілометрів під числом, розкид
        // між згортками, і в який бік число тепер помиляється.
        "trunk" to 16.2, //         9 533 км · розкид 0,49 · завищує 17,7% (було 100,0%)
        "residential" to 8.3, //   15 720 км · розкид 0,64 · завищує 14,8%
        "tertiary" to 9.9, //      13 260 км · розкид 0,90 · завищує 38,0%
        "service" to 3.0, //        2 349 км · розкид 0,33 · завищує 21,9%

        // ПЕРЕВІРЕНО Й ЛИШЕНО. Підгонка на 23 тисячах відкладених кілометрів
        // дала майже ті самі числа (13,1 / 11,1 / 7,2). Константа, яку
        // випробували, — інша річ, ніж константа, якої не чіпали.
        "primary" to 13.0, //      10 773 км, підгонка 13,1
        "secondary" to 11.0, //     9 126 км, підгонка 11,1
        "unclassified" to 7.0, //   3 130 км, підгонка 7,2

        // НЕ ЧІПАЄМО: доказів замало. Зʼїзди мають 10–186 км на клас, і два з
        // них підгонка тягне в бік оптимізму — саме туди, куди не можна.
        // Пандус — не та дорога, у яку він вливається: при 25,0 триста метрів
        // зʼїзду рахувались як 90 км/год.
        "motorway" to 25.0, "motorway_link" to 12.5, "trunk_link" to 12.5,
        "primary_link" to 13.0, "secondary_link" to 11.0, "tertiary_link" to 9.0,

        // 4,0 -> 3,4. Це число змінив не перепис, а власний сторож нижче:
        // 4,0 м/с — це 14,4 км/год, а житлова зона за ПДР 20 км/год, тобто зі
        // своїм же реалізмом стеля 3,9. Клас їхав швидше за закон. Підгонка
        // дає 3,4 на 120 км — доказів на власне число мало, але напрям
        // обережний, а стара величина була просто незаконна.
        "living_street" to 3.4,

        // `track` тут НЕМАЄ навмисно, і це не пропуск. Ґрунтівки — 45% усієї
        // прогалини (496 014 км), а розмічених під них 500. Додати клас сюди
        // означало б підняти впевненість застосунку саме там, де він знає
        // найменше; хай падає в ASSUMED і несе чесну засторогу маршруту.
    )

    /**
     * The price of the shoulder, fixed at both ends by numbers already in this
     * file rather than by taste.
     *
     * Floor: the dearest way a pedestrian may legally be sent down today is a
     * parking aisle on unmade ground — [PARKING_AISLE_PENALTY] ×
     * [ROUGH_SURFACE_PENALTY] = 5.4. A trunk must cost MORE, or the router
     * offers a highway shoulder in preference to somebody's yard.
     *
     * Ceiling: a restricted courtyard is [COURTYARD_PENALTY] ×
     * [DESTINATION_PENALTY] = 8.75. Past that the router starts threading yards
     * together to dodge asphalt again — the 250× absurdity returning in
     * miniature.
     *
     * 7.0 sits between, and is 71× under [LocalRouter.BLOCKED_PENALTY]: a
     * pavement, a path or any parallel street of comparable length always wins,
     * and the longest detour the router will now accept to leave the trunk is
     * ~4.7× over rough track instead of ~333×.
     */
    private const val TRUNK_FOOT_PENALTY = 7.0

    private const val COURTYARD_PENALTY = 3.5
    private const val PARKING_AISLE_PENALTY = 4.0
    private const val ALLEY_PENALTY = 1.6
    private const val SERVICE_PENALTY = 1.8
    private const val DESTINATION_PENALTY = 2.5
    private const val TRACK_PENALTY = 1.5
    private const val ROUGH_SURFACE_PENALTY = 1.35
    private const val STEPS_PENALTY = 1.8
    private const val NO_SIDEWALK_PENALTY = 1.6
    private const val LIVING_STREET_PENALTY = 1.4
    private const val CAR_TRACK_PENALTY = 3.0
    private const val CAR_ROUGH_PENALTY = 1.8

    // Оголошені ПЕРЕД велосипедними множниками навмисно: ті виведені з цих, а
    // константа не може посилатися вперед.
    private const val STEP_SPEED = 0.6
    private const val ROUGH_FOOT_SPEED = 1.0

    /**
     * СХОДИ ПІД ВЕЛОСИПЕДОМ — не перевага, а арифметика.
     *
     * Сходи не заборонені велосипеду навмисно ([BIKE_FORBIDDEN_CLASSES]):
     * пішохідний міст через колію без пандуса — це 25 109 обʼєктів `steps` у
     * країні, і заборона рвала б мережу там, де людина просто зносить
     * велосипед руками. Скільки це коштує, рахувати не треба: велосипед їде
     * [LocalRouter.BIKE_SPEED_MPS], сходами його несуть [STEP_SPEED], і
     * відношення цих двох і є множник. Жодного числа зі стелі.
     */
    private const val BIKE_STEPS_PENALTY = LocalRouter.BIKE_SPEED_MPS / STEP_SPEED

    /**
     * `bicycle=dismount` — те саме виведення: далі ділянку йдуть пішки, тож
     * ціна — відношення велосипедного темпу до пішохідного, 1 466 доріг.
     */
    private const val BIKE_DISMOUNT_PENALTY =
        LocalRouter.BIKE_SPEED_MPS / LocalRouter.FOOT_SPEED_MPS

    internal const val KMH_TO_MPS = 1000.0 / 3600.0

    /** Posted limits are a ceiling, not a journey — city driving averages well under. */
    internal const val URBAN_REALISM = 0.7
}
