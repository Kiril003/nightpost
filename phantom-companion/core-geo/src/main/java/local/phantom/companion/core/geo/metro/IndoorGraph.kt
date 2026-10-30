package local.phantom.companion.core.geo.metro

import local.phantom.companion.core.geo.GeoMath
import java.util.PriorityQueue
import kotlin.math.abs

/** Доступність для маломобільних груп — тег OSM `wheelchair`. */
enum class Wheelchair {
    YES, LIMITED, NO, UNKNOWN;

    companion object {
        /** Розбір тегу; невідоме значення — [UNKNOWN], а не помилка. */
        fun parse(raw: String?): Wheelchair = when (raw?.trim()?.lowercase()) {
            "yes", "designated" -> YES
            "limited" -> LIMITED
            "no" -> NO
            else -> UNKNOWN
        }
    }
}

/**
 * Напрямок руху ескалатора відносно напрямку креслення лінії — тег `conveying`.
 *
 * Це не декоративна деталь: проти ескалатора не пройдеш. Маршрут, який
 * пропонує піднятись ескалатором, що їде вниз, — це не «на кілька секунд
 * довше», це неможливий маршрут, і людина з валізою в переході на це не має
 * потрапити.
 */
enum class Conveying {
    /** Не ескалатор або зупинений — прохідний в обидва боки як сходи. */
    NONE,

    /** Рухається за напрямком лінії. */
    FORWARD,

    /** Рухається проти напрямку лінії. */
    BACKWARD,

    /** Напрямок перемикають за пасажиропотоком — фактичний невідомий. */
    REVERSIBLE,
    ;

    companion object {
        fun parse(raw: String?): Conveying = when (raw?.trim()?.lowercase()) {
            "yes", "forward" -> FORWARD
            "backward" -> BACKWARD
            "reversible" -> REVERSIBLE
            else -> NONE
        }
    }
}

/** Що це за точка підземного простору. */
enum class IndoorNodeKind {
    /** Вхід з вулиці (`railway=subway_entrance`). */
    ENTRANCE,

    /** Платформа (центроїд полігона `railway=platform`). */
    PLATFORM,

    /** Вестибюль, касовий зал, перехід. */
    CONCOURSE,

    /** Технічна вершина геометрії — перетин або кінець шляху. */
    JUNCTION,
}

/** Тип звʼязку між точками. */
enum class IndoorEdgeKind {
    /** Горизонтальний прохід. */
    WALKWAY,

    /** Сходи. */
    STAIRS,

    /** Ескалатор — напрямок задає [IndoorEdge.conveying]. */
    ESCALATOR,

    /** Ліфт. Єдиний вертикальний звʼязок, доступний у візку. */
    ELEVATOR,

    /** Вихід на платформу. */
    PLATFORM_ACCESS,
}

/**
 * Вершина графа станції.
 *
 * @param level рівень OSM: цілий (`-1`) або дробовий (`-1.5` — проміжний
 *   майданчик між прольотами). Дробові рівні реально трапляються у вузлах
 *   пересадки, і округлення їх до цілого злипає різні поверхи в одну вершину.
 */
data class IndoorNode(
    val id: Long,
    val latDeg: Double,
    val lonDeg: Double,
    val level: Double,
    val kind: IndoorNodeKind,
    /** Номер виходу (`ref`) — те, що написано на табличці й у голосовій підказці. */
    val ref: String? = null,
    val name: String? = null,
    val wheelchair: Wheelchair = Wheelchair.UNKNOWN,
    /** Ідентифікатор вихідного обʼєкта OSM, якщо вершина походить з нього. */
    val osmId: Long? = null,
)

/**
 * Ребро графа станції.
 *
 * @param oneway односторонній прохід (турнікет на вихід, розділені потоки).
 * @param conveying для [IndoorEdgeKind.ESCALATOR] — напрямок руху відносно
 *   `from → to`.
 */
data class IndoorEdge(
    val fromId: Long,
    val toId: Long,
    val kind: IndoorEdgeKind,
    val lengthM: Double,
    val wheelchair: Wheelchair = Wheelchair.UNKNOWN,
    val conveying: Conveying = Conveying.NONE,
    val oneway: Boolean = false,
    val osmId: Long? = null,
)

/**
 * Налаштування пошуку шляху. Вартість рахується в СЕКУНДАХ, а не метрах —
 * інакше не порівняти сорок секунд очікування ліфта з тридцятьма метрами
 * коридору, а саме цей вибір і є суттю навігації в пересадковому вузлі.
 */
data class IndoorRouteOptions(
    val allowStairs: Boolean = true,
    val allowEscalators: Boolean = true,
    val allowElevators: Boolean = true,
    /**
     * Жорсткий режим доступності: сходи й ескалатори відпадають повністю,
     * а все з `wheelchair=no` виключається — і ребра, і вершини.
     */
    val requireWheelchair: Boolean = false,
    val walkSpeedMps: Double = 1.2,
    /** Швидкість полотна ескалатора; додається до кроку пасажира. */
    val escalatorSpeedMps: Double = 0.65,
    /** Середнє очікування кабіни. */
    val elevatorWaitS: Double = 40.0,
    val elevatorPerLevelS: Double = 8.0,
    /** Плата за один рівень сходами — підйом коштує дорожче за метри. */
    val stairsPerLevelS: Double = 25.0,
    /**
     * Плата за ескалатор із невизначеним напрямком: він може виявитись
     * зустрічним, і маршрут доведеться перебудовувати вже на місці.
     */
    val reversibleEscalatorPenaltyS: Double = 20.0,
) {
    /** Чи прохідний тип ребра за цих налаштувань. */
    internal fun allows(kind: IndoorEdgeKind): Boolean = when (kind) {
        IndoorEdgeKind.STAIRS -> allowStairs && !requireWheelchair
        IndoorEdgeKind.ESCALATOR -> allowEscalators && !requireWheelchair
        IndoorEdgeKind.ELEVATOR -> allowElevators
        else -> true
    }
}

/** Один крок маршруту. */
data class IndoorRouteStep(
    val edge: IndoorEdge,
    val from: IndoorNode,
    val to: IndoorNode,
    /** Час на цей крок, секунди. */
    val seconds: Double,
)

/** Готовий маршрут усередині станції. */
data class IndoorRoute(val steps: List<IndoorRouteStep>) {
    val totalSeconds: Double get() = steps.sumOf { it.seconds }
    val totalLengthM: Double get() = steps.sumOf { it.edge.lengthM }

    /** Послідовність вершин, включно з початковою. */
    val nodeIds: List<Long>
        get() = if (steps.isEmpty()) emptyList() else listOf(steps.first().from.id) + steps.map { it.to.id }

    /** Скільки разів маршрут змінює рівень. */
    val levelChanges: Int get() = steps.count { it.from.level != it.to.level }

    fun uses(kind: IndoorEdgeKind): Boolean = steps.any { it.edge.kind == kind }
}

/**
 * Граф підземного простору станції — пішохідна навігація там, де класична
 * неможлива за визначенням: супутника немає, а помилка на один рівень або на
 * один вихід коштує людині десяти хвилин і зайвого підйому сходами.
 *
 * Граф свідомо не знає нічого про Android і про мережу: на вхід — вершини й
 * ребра, на вихід — маршрут. Розбір OSM живе окремо ([OsmIndoorParser]), щоб
 * логіка доступності перевірялась на синтетичній станції за мілісекунди.
 */
class IndoorGraph(
    nodes: List<IndoorNode>,
    /** Ребра. Дублікати `from→to` допустимі: два паралельні ескалатори — норма. */
    val edges: List<IndoorEdge>,
) {

    private val nodeById: Map<Long, IndoorNode> = nodes.associateBy { it.id }

    /** Список суміжності: вершина → ребра, що її торкаються. */
    private val incident: Map<Long, List<IndoorEdge>> = buildMap<Long, MutableList<IndoorEdge>> {
        for (e in edges) {
            getOrPut(e.fromId) { mutableListOf() } += e
            getOrPut(e.toId) { mutableListOf() } += e
        }
    }

    /** Усі вершини. */
    val nodes: Collection<IndoorNode> get() = nodeById.values

    fun node(id: Long): IndoorNode? = nodeById[id]

    /** Усі входи станції. */
    fun entrances(): List<IndoorNode> = nodeById.values.filter { it.kind == IndoorNodeKind.ENTRANCE }

    /** Вхід за номером на табличці. */
    fun entranceByRef(ref: String): IndoorNode? =
        entrances().firstOrNull { it.ref?.equals(ref, ignoreCase = true) == true }

    /**
     * Найближчий до точки вхід.
     *
     * @param accessibleOnly лишити тільки входи з `wheelchair=yes`. `limited`
     *   свідомо не проходить: «обмежено доступний» вхід, вибраний автоматично
     *   для людини у візку, — це відповідальність, яку алгоритм не має брати.
     */
    fun nearestEntrance(latDeg: Double, lonDeg: Double, accessibleOnly: Boolean = false): IndoorNode? =
        entrances()
            .filter { !accessibleOnly || it.wheelchair == Wheelchair.YES }
            .minByOrNull { distanceM(it.latDeg, it.lonDeg, latDeg, lonDeg) }

    /**
     * Найкоротший (за часом) шлях [fromId] → [toId].
     *
     * @return `null`, якщо шляху за таких обмежень немає — типово це і є
     *   відповідь «у візку сюди не потрапити», і вона має бути видимою, а не
     *   підміненою маршрутом сходами.
     */
    fun route(fromId: Long, toId: Long, options: IndoorRouteOptions = IndoorRouteOptions()): IndoorRoute? {
        val start = nodeById[fromId] ?: return null
        val goal = nodeById[toId] ?: return null
        if (!nodePassable(start, options) || !nodePassable(goal, options)) return null
        if (fromId == toId) return IndoorRoute(emptyList())

        val dist = HashMap<Long, Double>()
        val prev = HashMap<Long, IndoorRouteStep>()
        val visited = HashSet<Long>()
        val queue = PriorityQueue<Pair<Long, Double>>(compareBy { it.second })

        dist[fromId] = 0.0
        queue += fromId to 0.0

        while (queue.isNotEmpty()) {
            val (currentId, currentCost) = queue.poll()
            if (!visited.add(currentId)) continue
            if (currentId == toId) break
            val current = nodeById[currentId] ?: continue

            for (edge in incident[currentId].orEmpty()) {
                val nextId = when (currentId) {
                    edge.fromId -> edge.toId
                    else -> edge.fromId
                }
                val forward = currentId == edge.fromId
                if (nextId in visited) continue
                val next = nodeById[nextId] ?: continue
                if (!edgePassable(edge, forward, options)) continue
                if (!nodePassable(next, options)) continue

                val seconds = traversalSeconds(edge, current, next, forward, options)
                val newCost = currentCost + seconds
                if (newCost < (dist[nextId] ?: Double.MAX_VALUE)) {
                    dist[nextId] = newCost
                    prev[nextId] = IndoorRouteStep(edge, current, next, seconds)
                    queue += nextId to newCost
                }
            }
        }

        if (toId !in dist) return null
        val steps = ArrayDeque<IndoorRouteStep>()
        var cursor = toId
        while (cursor != fromId) {
            val step = prev[cursor] ?: return null
            steps.addFirst(step)
            cursor = step.from.id
        }
        return IndoorRoute(steps.toList())
    }

    /** Чи можна пройти ребром у заданому напрямку. */
    internal fun edgePassable(edge: IndoorEdge, forward: Boolean, options: IndoorRouteOptions): Boolean {
        if (!options.allows(edge.kind)) return false
        if (options.requireWheelchair && edge.wheelchair == Wheelchair.NO) return false
        if (options.requireWheelchair && edge.wheelchair == Wheelchair.UNKNOWN &&
            edge.kind == IndoorEdgeKind.ELEVATOR
        ) {
            // Ліфт без тегу доступності — усе одно ліфт; виключати його означало б
            // лишити без маршруту майже всі станції, де тег просто не проставлено.
            return !edge.oneway || forward
        }
        if (edge.oneway && !forward) return false
        if (edge.kind == IndoorEdgeKind.ESCALATOR) {
            return when (edge.conveying) {
                Conveying.FORWARD -> forward
                Conveying.BACKWARD -> !forward
                Conveying.REVERSIBLE, Conveying.NONE -> true
            }
        }
        return true
    }

    private fun nodePassable(node: IndoorNode, options: IndoorRouteOptions): Boolean =
        !(options.requireWheelchair && node.wheelchair == Wheelchair.NO)

    /** Час проходження ребра, секунди. */
    internal fun traversalSeconds(
        edge: IndoorEdge,
        from: IndoorNode,
        to: IndoorNode,
        forward: Boolean,
        options: IndoorRouteOptions,
    ): Double {
        val levels = abs(to.level - from.level)
        return when (edge.kind) {
            IndoorEdgeKind.WALKWAY, IndoorEdgeKind.PLATFORM_ACCESS ->
                edge.lengthM / options.walkSpeedMps

            IndoorEdgeKind.STAIRS ->
                edge.lengthM / (options.walkSpeedMps * 0.6) + levels * options.stairsPerLevelS

            IndoorEdgeKind.ESCALATOR -> {
                val moving = when (edge.conveying) {
                    Conveying.FORWARD -> forward
                    Conveying.BACKWARD -> !forward
                    else -> false
                }
                val speed = if (moving) options.walkSpeedMps + options.escalatorSpeedMps else options.walkSpeedMps * 0.6
                val penalty = if (edge.conveying == Conveying.REVERSIBLE) options.reversibleEscalatorPenaltyS else 0.0
                edge.lengthM / speed + penalty
            }

            IndoorEdgeKind.ELEVATOR ->
                options.elevatorWaitS + levels * options.elevatorPerLevelS
        }
    }

    companion object {
        /**
         * Відстань між точками, метри. Локальна рівнокутна проєкція ([GeoMath]):
         * станція ніколи не більша за кілометр, де похибка апроксимації —
         * міліметри, а гаверсинус коштував би тригонометрії на кожному ребрі
         * Дейкстри.
         */
        fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double =
            GeoMath.distanceM(lat1, lon1, lat2, lon2)
    }
}
