package local.phantom.companion.core.sensor.nav

/**
 * LEVIATHAN-1 — the router that works when the world does not: pure A* over
 * whatever road tiles [RoadTruth]-style caches already hold. No server, no
 * network, no key.
 *
 * Honesty contract, stated harder than usual because routing is life-safety
 * here (the no-network moment IS the air-raid moment):
 *
 *  - It routes only inside the loaded neighbourhood. Outside → null,
 *    never a straight line, never a guess.
 *  - Durations are estimates and say so via [LocalRoute.approximate] (true,
 *    always) — no traffic, no turn costs, no signals. Where OSM gives a posted
 *    `maxspeed` the estimate uses it; where it gives only a road class the
 *    estimate uses that class's pace; where it gives nothing the estimate is
 *    the old flat speed. [LocalRoute.speedBasis] says which of the three, so a
 *    measured minute and a guessed minute are never presented as one claim.
 *  - Snap failures (start or goal too far from any road) → null.
 *
 * The intended consumer is the evacuation engine's OSRM fallback; anything
 * that outgrows this contract belongs to NAV-Ω4's real offline extracts.
 */
class LocalRouter {

    data class LocalRoute(
        val points: List<Pair<Double, Double>>, // (lat, lon)
        val distanceMeters: Double,
        val durationSeconds: Double,
        /** Always true: an estimate over a partial graph — no traffic, no
         *  signals, no turn costs, whatever the speeds came from. */
        val approximate: Boolean = true,
        /** HORIZON II.1 — mean learned GNSS risk along this corridor, 0..1
         *  (0 when no integrity map was consulted). */
        val meanRisk: Double = 0.0,
        /**
         * РЕЄСТР — the weakest claim [durationSeconds] is entitled to. Strict:
         * one metre of guessed road makes the whole estimate ASSUMED, because
         * «за знаками» must never be said of a number that is partly invented.
         */
        val speedBasis: RoadProfile.SpeedBasis = RoadProfile.SpeedBasis.ASSUMED,
        /** Share of [distanceMeters] timed from a real posted `maxspeed`, 0..1.
         *  Measured, not scored — it is a length, divided by a length. */
        val postedShare: Double = 0.0,
        /**
         * УЗБІЧЧЯ — metres of this route walked OR RIDDEN on the national
         * network (`trunk`) where OSM records no pavement. Measured: the
         * chosen segments' own lengths, summed. Counted for the foot and bike
         * profiles alike — the hazard is the passing lorry, and it does not
         * ask what you are on. Zero for a car, where a trunk is simply a road.
         *
         * It is NOT a claim that a shoulder exists. Nothing in the data says
         * whether one does. The sentence this number entitles anyone to is
         * «частина шляху — узбіччям магістралі, чи є узбіччя — невідомо»;
         * «є узбіччя» is a claim no offline extract can support.
         */
        val trunkShoulderMeters: Double = 0.0,
        /**
         * БРІД — how many distinct ways on this route OSM marks `ford`.
         *
         * Counted, not measured in metres, and deliberately: a ford is an event
         * at a place, not a stretch of road, and «180 м броду» would read as a
         * quantity that means something. It does not.
         *
         * The strict limit of what this number entitles anyone to say is «на
         * шляху позначено брід». It is NOT a claim the crossing is passable,
         * and NOT a claim it is impassable. OSM carries no depth here worth
         * the name, no season, and above all no water level — and water level
         * is the one variable that changes within an hour. That is the same
         * line the project already drew on ice-crossing viability, and it is
         * drawn here for the same reason: the cost of being wrong is a life.
         *
         * Zero on every pack baked before 06.09.2026 — the `ford` key was
         * missing from [RoadWayCodec.routingKeys], so the tag never reached the
         * device. Zero therefore means «пакет цього не ніс» as often as it
         * means «бродів нема», and nothing built on this may present it as the
         * latter.
         */
        val fordCrossings: Int = 0,
        /** Road name for each point along the route (size == points.size). */
        val roadNames: List<String?> = emptyList(),
    ) {
        /**
         * True when [trunkShoulderMeters] is a walk and not a crossing. Every
         * junction with a trunk puts a few tens of metres of it into a route,
         * and a warning that fires on those is one the operator learns to
         * ignore within a day.
         */
        val walksTrunkShoulder: Boolean get() = trunkShoulderMeters >= TRUNK_SHOULDER_NOTICE_M
    }

    /**
     * Route [fromLat],[fromLon] → [toLat],[toLon] over [graph], under one of
     * the three profiles of [RoadProfile.TravelMode]:
     *  - `FOOT` — pedestrian rules, oneway ignored, walking pace;
     *  - `BIKE` — cycle rules (`bicycle=*` access, cycleways preferred,
     *    `bicycle=use_sidepath` forbidden), oneway respected, cycling pace;
     *  - `CAR` — urban driving pace with oneway respected.
     *
     * HORIZON II.1 — when [integrity] and a non-zero [jammingAvoidance] are
     * given, corridors through learned jammer shadows are penalised: the
     * router will accept a detour up to (1 + avoidance)× longer to keep the
     * sky clean. The reported distance/duration remain the REAL numbers for
     * the path chosen (Law 1) — the penalty steers the choice, it never
     * inflates the estimate.
     */
    /**
     * Куди чіплятись — до найближчої дороги, якою цей профіль МОЖЕ їхати.
     *
     * Раніше тут стояло `near(..., limit = 1)`: найближча лінія будь-якого
     * роду. На пакетах без тегів це не шкодило, бо непроїзних доріг у них не
     * було взагалі — теги втрачені, отже проїзне все. На пакеті З тегами воно
     * вимикає навігацію рівно там, де люди починають і закінчують поїздки.
     *
     * Заміряно 30 серпня 2026 на щойно спеченому київському пакеті: маршрут
     * від дому власника (50.504668, 30.410170) до центру. Обидва кінці
     * чіплялись до `highway=footway` за 1 і 4 метри — тротуару під ногами.
     * Для машини це заборонений клас, тож перше ж ребро коштувало
     * [BLOCKED_PENALTY] = 500×, фронт A* розпухав і впирався у стелю
     * `MAX_ASTAR_NODES`. Результат: 11,36 км маршруту на старому пакеті і
     * **жодного** — на новому.
     *
     * Це і є пастка перепікання: ліки, накладені на цю ваду, зробили б
     * навігацію гіршою, ніж хвороба.
     *
     * Якщо проїзної дороги в радіусі немає взагалі — вертаємо найближчу, хоч
     * яку. Це свідомо: людина справді може стояти в глибині парку, і 500×
     * штрафу за перші метри чесніше за відмову вести. Гарантія тут одна —
     * НЕ ПОГІРШИТИ: без тегів `blocked` завжди false, тож вибір лишається
     * той самий, що й був.
     */
    private fun snapFor(
        graph: RoadGraph,
        lat: Double,
        lon: Double,
        mode: RoadProfile.TravelMode,
    ): RoadGraph.Candidate? {
        val near = graph.near(lat, lon, SNAP_RADIUS_M, limit = SNAP_CANDIDATES)
        return near.firstOrNull { !RoadProfile.blocked(it.way.tags, mode) }
            ?: near.firstOrNull()
    }

    /**
     * ЧОМУ маршруту немає — назване слово замість мовчазного `null`.
     *
     * Шість різних світів поверталися однаково, і застосунок перекладав усі
     * шість одним реченням: «Доріг цієї ділянки на пристрої немає — потрібен
     * дорожній пакет». Чотири з шести від пакета не залежать ніяк, тож порада
     * вела не туди саме тоді, коли людина стоїть і не має маршруту.
     *
     * Розділено рівно там, де різні ЛІКИ: «нема до чого причепитись» (людина в
     * полі за 120 м від будь-якої дороги) лікується підходом до дороги;
     * «пошук здався» — коротшою поїздкою або сервером; «пакета нема» —
     * пакетом. Одне речення на три хвороби — це не стислість, це мовчання.
     */
    enum class Refusal {
        /** Граф порожній — дороги сюди не завантажені. */
        EMPTY_GRAPH,

        /** Старт далі за [SNAP_RADIUS_M] від будь-якої дороги. */
        SNAP_FROM,

        /** Мета далі за [SNAP_RADIUS_M] від будь-якої дороги. */
        SNAP_TO,

        /** Дороги є, звʼязку між цими точками в завантаженій окрузі немає. */
        NO_PATH,

        /**
         * Пошук упрів у стелю [RoadGraph.MAX_ASTAR_NODES]. Дорога може
         * існувати — ми просто не шукали далі. Вимірювання про НАС, а не
         * вирок місцевості.
         * З 25.09 [route] іде графом поворотів без стелі, і звідти цього слова
         * не буває; застосунок його ще читає (`RoadRefusal.SEARCH_CEILING`).
         */
        EXHAUSTED,

        /** Граф віддав шлях коротший за дві точки — лінії з цього не вийде. */
        DEGENERATE,
    }

    /**
     * ДВОЇСТИЙ ВХІД, і навмисно. Булева форма лишається рівно тим, чим була
     * (`true` → пішки, `false` → авто), тож жоден наявний виклик і жоден
     * наявний тест не змінює поведінки; нова форма приймає всі три профілі.
     *
     * Булевий параметр НЕ має типового значення — саме це й розводить дві
     * форми: виклик без шостого аргументу однозначно потрапляє в профільну,
     * виклик з `footProfile = …` — у булеву.
     */
    fun route(
        graph: RoadGraph,
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        footProfile: Boolean,
        integrity: GnssIntegrityMap? = null,
        jammingAvoidance: Double = 0.0,
        extraCostFactor: ((Double, Double) -> Double)? = null,
    ): LocalRoute? = route(
        graph, fromLat, fromLon, toLat, toLon,
        if (footProfile) RoadProfile.TravelMode.FOOT else RoadProfile.TravelMode.CAR,
        integrity, jammingAvoidance, extraCostFactor,
    )

    fun route(
        graph: RoadGraph,
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        mode: RoadProfile.TravelMode = RoadProfile.TravelMode.FOOT,
        integrity: GnssIntegrityMap? = null,
        jammingAvoidance: Double = 0.0,
        /** УКРИТТЯ — extra multiplicative cost (e.g. CoverGrid open-ground
         *  penalty); composed with the jamming field, steers only. */
        extraCostFactor: ((Double, Double) -> Double)? = null,
        /** Викликається РІВНО ОДИН раз і лише перед `null` — див. [Refusal]. */
        onRefusal: ((Refusal) -> Unit)? = null,
    ): LocalRoute? {
        if (graph.wayCount == 0) {
            onRefusal?.invoke(Refusal.EMPTY_GRAPH)
            return null
        }
        val onFoot = mode == RoadProfile.TravelMode.FOOT
        val from = snapFor(graph, fromLat, fromLon, mode) ?: run {
            onRefusal?.invoke(Refusal.SNAP_FROM)
            return null
        }
        val to = snapFor(graph, toLat, toLon, mode) ?: run {
            onRefusal?.invoke(Refusal.SNAP_TO)
            return null
        }

        val integrityField: ((Double, Double) -> Double)? =
            if (integrity != null && jammingAvoidance > 0.0) {
                { lat, lon -> integrity.costFactorAt(lat, lon, jammingAvoidance) }
            } else null
        val costField: ((Double, Double) -> Double)? =
            if (integrityField != null && extraCostFactor != null) {
                { lat, lon -> integrityField(lat, lon) * extraCostFactor(lat, lon) }
            } else integrityField ?: extraCostFactor

        // What the road IS, not just where it goes. Without this the router
        // costs a courtyard driveway and a street identically, which is exactly
        // how it kept routing the operator through прибудинкова територія.
        // Односторонній рух тримає і велосипед: він транспортний засіб, а
        // ключа `oneway:bicycle` (зустрічний велорух) у пакеті НЕМАЄ — на
        // національному пакеті таких доріг нуль, бо [RoadMeshStore] цього
        // ключа не пише. Отже про зустрічну смугу ми не знаємо нічого, і
        // вигадувати її означало б вести людину проти потоку.
        val traced = graph.routeTurnAware(
            from,
            to,
            respectOneway = !onFoot,
            segmentCostFactor = costField,
            wayCostFactor = { way ->
                if (RoadProfile.blocked(way.tags, mode)) {
                    BLOCKED_PENALTY
                } else {
                    RoadProfile.costFactor(way.tags, mode)
                }
            },
            onRefusal = { graphRefusal ->
                onRefusal?.invoke(
                    when (graphRefusal) {
                        RoadGraph.Refusal.EMPTY_GRAPH -> Refusal.EMPTY_GRAPH
                        RoadGraph.Refusal.NO_PATH -> Refusal.NO_PATH
                        RoadGraph.Refusal.EXHAUSTED -> Refusal.EXHAUSTED
                    },
                )
            },
        ) ?: return null
        val pts = traced.points
        if (pts.size < 2) {
            onRefusal?.invoke(Refusal.DEGENERATE)
            return null
        }

        // Each stretch is timed at ITS OWN road's speed. On a way that carries
        // no tags — every legacy mesh row, every way from a pack baked without
        // them — RoadProfile.speedMps returns exactly FOOT_SPEED_MPS /
        // CAR_SPEED_MPS, so an untagged route keeps the flat-speed duration it
        // has always had.
        var dist = 0.0
        var seconds = 0.0
        var postedM = 0.0
        var shoulderM = 0.0
        // За ІДЕНТИФІКАТОРОМ дороги, а не за відрізками: одна переправа лежить
        // у кількох відрізках підряд, і рахунок «скільки разів трапився тег»
        // назвав би один брід сімома.
        val fordWays = HashSet<Long>()
        var weakest = RoadProfile.SpeedBasis.POSTED
        for (i in 1 until pts.size) {
            val d = haversineM(pts[i - 1].first, pts[i - 1].second, pts[i].first, pts[i].second)
            val way = traced.segmentWays.getOrNull(i - 1)
            val tags = way?.tags.orEmpty()
            dist += d
            seconds += d / RoadProfile.speedMps(tags, mode)
            // Затримка на перехресті — це СВІТЛОФОР, а не автомобіль: червоне
            // однакове для того, хто стоїть на велосипеді, і для того, хто
            // поруч у машині. Тому вона рахується обом і не рахується пішому,
            // який переходить по переходу, поки потік стоїть.
            //
            // Затримку бере ГЕОМЕТРІЯ вузла (i-1 — точка, де ребро i-2
            // переходить у ребро i-1), а не рівність назв вулиць. Назва як ключ
            // рахувала затримку там, де маршрут їде прямо через перехрестя
            // з іншим імʼям, і не рахувала розвороту взагалі.
            if (!onFoot) {
                seconds += when (traced.turnKinds.getOrNull(i - 1)) {
                    RoadGraph.TurnKind.TURN -> INTERSECTION_TURN_DELAY_S
                    RoadGraph.TurnKind.U_TURN -> U_TURN_DELAY_S
                    else -> 0.0
                }
            }
            // Узбіччя магістралі рахується і велосипедисту: вантажівка, що
            // проходить у метрі, не питає, на чому ти їдеш.
            if (mode != RoadProfile.TravelMode.CAR && RoadProfile.isTrunkShoulder(tags)) shoulderM += d
            if (way != null && RoadProfile.marksFord(tags)) fordWays += way.id
            when (RoadProfile.speedBasis(tags, mode)) {
                RoadProfile.SpeedBasis.POSTED -> postedM += d
                RoadProfile.SpeedBasis.CLASS ->
                    if (weakest == RoadProfile.SpeedBasis.POSTED) weakest = RoadProfile.SpeedBasis.CLASS
                RoadProfile.SpeedBasis.ASSUMED -> weakest = RoadProfile.SpeedBasis.ASSUMED
            }
        }
        val names = ArrayList<String?>(pts.size)
        names.add(traced.segmentWays.firstOrNull()?.name)
        traced.segmentWays.forEach { names.add(it.name) }

        return LocalRoute(
            points = pts,
            distanceMeters = dist,
            durationSeconds = seconds,
            meanRisk = integrity?.meanRiskAlong(pts) ?: 0.0,
            speedBasis = weakest,
            postedShare = if (dist > 0.0) postedM / dist else 0.0,
            trunkShoulderMeters = shoulderM,
            fordCrossings = fordWays.size,
            roadNames = names,
        )
    }

    /**
     * HORIZON II.1 — «маршрут чистого неба»: both the fastest route and the
     * cleanest-sky route, so the caller can offer the operator the choice.
     * Returns null if even the fastest cannot be found; [cleanSky] falls back
     * to [fastest] when avoidance changes nothing (already clean, or no
     * detour exists inside the loaded graph).
     */
    fun cleanSky(
        graph: RoadGraph,
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        integrity: GnssIntegrityMap,
        mode: RoadProfile.TravelMode = RoadProfile.TravelMode.FOOT,
        jammingAvoidance: Double = DEFAULT_AVOIDANCE,
    ): CleanSkyResult? {
        val fastest = route(graph, fromLat, fromLon, toLat, toLon, mode, integrity, 0.0)
            ?: return null
        val clean = route(graph, fromLat, fromLon, toLat, toLon, mode, integrity, jammingAvoidance)
            ?: fastest
        return CleanSkyResult(fastest = fastest, cleanSky = clean)
    }

    data class CleanSkyResult(val fastest: LocalRoute, val cleanSky: LocalRoute) {
        /** True when avoiding jamming actually bought lower risk (a real choice). */
        val differs: Boolean
            get() = cleanSky.meanRisk < fastest.meanRisk - 1e-4 &&
                cleanSky.points != fastest.points
        val extraMeters: Double get() = cleanSky.distanceMeters - fastest.distanceMeters
    }

    companion object {
        /** A start further than this from any road is not routable locally. */
        const val SNAP_RADIUS_M = 120.0

        /**
         * Скільки найближчих ліній переглянути, шукаючи проїзну.
         *
         * Не «трохи більше за один», а стільки, щоб покрити весь радіус
         * [SNAP_RADIUS_M]. Вісім було мало і це заміряно: біля дому власника
         * і на Майдані ВСІ вісім найближчих ліній — тротуари, тож пошук
         * проїзної серед них не знаходив жодної. Пішохідна площа не має
         * восьми доріг для машини поблизу — вона взагалі не має доріг для
         * машини, доки не вийдеш за її межу.
         */
        const val SNAP_CANDIDATES = 64

        const val FOOT_SPEED_MPS = 1.3
        const val CAR_SPEED_MPS = 7.5 // honest urban average, not highway

        /**
         * ВЕЛОСИПЕД — 4,2 м/с ≈ 15,1 км/год, і число не з голови.
         *
         * 15 км/год — це базовий темп, яким рахує сам `routed-bike`, тобто той
         * публічний OSRM, на який вказує
         * [local.phantom.companion.core.net.map.RouteProfile.BIKE] і яким цей
         * застосунок показує велосипедний маршрут, коли мережа є. Офлайнова
         * оцінка мусить збігатися з онлайновою для ТОГО САМОГО профілю,
         * інакше зникла мережа мовчки переписувала б тривалість.
         *
         * Як і два сусіди, це ПРИПУЩЕННЯ, і воно назване припущенням:
         * дорога без тегів дістає рівно це число і [RoadProfile.SpeedBasis.ASSUMED].
         */
        const val BIKE_SPEED_MPS = 4.2

        /** Real urban delay per turn / signal intersection (~12 seconds). */
        const val INTERSECTION_TURN_DELAY_S = 12.0

        /**
         * Розворот коштує довше за поворот, і саме стільки, скільки його
         * оцінює сам пошук: 200 м штрафу міським темпом 7,5 м/с. Два числа про
         * одну річ мусять зійтись, інакше маршрут обирали б за однією ціною, а
         * хвилини людині рахували за іншою.
         */
        const val U_TURN_DELAY_S = 27.0

        /** A fully-jammed tile costs ~2.2× its length — worth a real detour,
         *  not so much that the router walks a mile to dodge one bad block. */
        const val DEFAULT_AVOIDANCE = 1.2

        /**
         * Forbidden, but finitely so. A hard exclusion would make the router
         * answer «немає маршруту» to a man standing on the private road he
         * lives on; at 500× it will still walk him out of his own courtyard and
         * will never thread a route through somebody else's.
         */
        const val BLOCKED_PENALTY = 500.0

        /** Below this a trunk in the path is a junction being crossed, not a
         *  highway being walked — see [LocalRoute.walksTrunkShoulder]. */
        const val TRUNK_SHOULDER_NOTICE_M = 150.0
    }
}
