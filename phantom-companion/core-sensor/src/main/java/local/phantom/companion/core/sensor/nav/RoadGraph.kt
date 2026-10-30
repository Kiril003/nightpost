package local.phantom.companion.core.sensor.nav

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot

/**
 * ATLAS-2 — the road network as the matcher sees it: pure geometry, no
 * Android, no network. One way = one OSM polyline; the graph indexes their
 * segments on a coarse grid so a candidate query is O(cell), not O(city).
 *
 * Coordinates come in as (lat, lon) and are flattened to metres on a local
 * equirectangular plane anchored at the graph's own centroid — the graph
 * only ever spans a few kilometres, where the approximation error is
 * centimetres.
 */
data class RoadWay(
    val id: Long,
    /** (lat, lon) vertices, ≥ 2. */
    val points: List<Pair<Double, Double>>,
    val name: String? = null,
    val oneway: Boolean = false,
    /**
     * The OSM tags that decide whether this way may be walked or driven at all
     * — `highway`, `access`, `foot`, `service`, `surface`.
     *
     * They used to be dropped at import while Overpass was already sending
     * them, which is why an offline route could cross a courtyard, a driveway
     * or private land: the router had no way to tell any of them from a street.
     * See [RoadProfile], which turns them into a cost.
     */
    val tags: Map<String, String> = emptyMap(),
)

class RoadGraph(ways: List<RoadWay>) {

    /** HORIZON III.2 — one traversed edge from [reachable], with cost-to-reach. */
    data class ReachEdge(
        val aLat: Double,
        val aLon: Double,
        val bLat: Double,
        val bLon: Double,
        /** Metres from the reachability origin to the [b] endpoint. */
        val costM: Double,
    )

    /** One projection of a query point onto one road segment. */
    data class Candidate(
        val way: RoadWay,
        /** Snapped position. */
        val lat: Double,
        val lon: Double,
        /** Perpendicular distance query→snap, metres. */
        val perpM: Double,
        /** Metres from the way's first vertex to the snapped point. */
        val alongM: Double,
        /** Segment heading at the snap, degrees compass (way direction). */
        val headingDeg: Float,
        internal val wayIndex: Int,
    )

    private class IndexedWay(
        val way: RoadWay,
        /** Vertices in plane metres (e, n). */
        val e: DoubleArray,
        val n: DoubleArray,
        /** Cumulative metres from vertex 0 to vertex i. */
        val prefixM: DoubleArray,
        /** Vertex keys (rounded coords) for junction detection. */
        val nodeKeys: LongArray,
    )

    private val anchorLat: Double
    private val anchorLon: Double
    private val cosLat: Double
    private val indexed: List<IndexedWay>

    /** grid cell → indices into [indexed] whose segments touch the cell. */
    private val grid = HashMap<Long, MutableList<Int>>()

    /**
     * node key → the exact (wayIndex, vertexIndex) slots sitting on it, packed
     * the same way A* packs its node keys.
     *
     * Тут стояла мапа «вузол → НОМЕРИ ДОРІГ», і кожен стрибок через перехрестя
     * мусив перебрати всю сусідню дорогу, шукаючи ту вершину, що збіглася, —
     * O(вершин) на стрибок, а магістраль несе їх сотні. Слот і є те, чого
     * стрибок хотів; він відомий на побудові й більше не міняється.
     */
    private val nodeToSlots = HashMap<Long, LongArray>()

    val wayCount: Int get() = indexed.size

    init {
        val usable = ways.filter { it.points.size >= 2 }
        anchorLat = usable.firstOrNull()?.points?.first()?.first ?: 0.0
        anchorLon = usable.firstOrNull()?.points?.first()?.second ?: 0.0
        cosLat = cos(Math.toRadians(anchorLat)).coerceAtLeast(1e-6)

        indexed = usable.mapIndexed { wi, way ->
            val e = DoubleArray(way.points.size)
            val n = DoubleArray(way.points.size)
            val prefix = DoubleArray(way.points.size)
            val keys = LongArray(way.points.size)
            way.points.forEachIndexed { i, (lat, lon) ->
                e[i] = toE(lon)
                n[i] = toN(lat)
                keys[i] = nodeKey(lat, lon)
                if (i > 0) prefix[i] = prefix[i - 1] + hypot(e[i] - e[i - 1], n[i] - n[i - 1])
            }
            IndexedWay(way, e, n, prefix, keys)
        }

        indexed.forEachIndexed { wi, iw ->
            for (i in 0 until iw.e.size - 1) {
                // Register the segment in every cell its bounding box touches.
                val c0e = cellOf(iw.e[i]); val c1e = cellOf(iw.e[i + 1])
                val c0n = cellOf(iw.n[i]); val c1n = cellOf(iw.n[i + 1])
                for (ce in minOf(c0e, c1e)..maxOf(c0e, c1e)) {
                    for (cn in minOf(c0n, c1n)..maxOf(c0n, c1n)) {
                        grid.getOrPut(cellKey(ce, cn)) { mutableListOf() }
                            .let { if (it.isEmpty() || it.last() != wi) it.add(wi) }
                    }
                }
            }
        }

        val slots = HashMap<Long, MutableList<Long>>()
        indexed.forEachIndexed { wi, iw ->
            iw.nodeKeys.forEachIndexed { vi, k ->
                slots.getOrPut(k) { ArrayList(2) }.add(encodeSlot(wi, vi))
            }
        }
        slots.forEach { (k, v) -> nodeToSlots[k] = v.toLongArray() }
    }

    /**
     * Best projection onto every way within [radiusM] of (lat, lon), nearest
     * first, at most [limit].
     */
    fun near(lat: Double, lon: Double, radiusM: Double, limit: Int = 8): List<Candidate> {
        if (indexed.isEmpty()) return emptyList()
        val qe = toE(lon)
        val qn = toN(lat)
        val cellR = (radiusM / CELL_M).toInt() + 1
        val ce0 = cellOf(qe)
        val cn0 = cellOf(qn)
        val seen = HashSet<Int>()
        val out = ArrayList<Candidate>()
        for (ce in ce0 - cellR..ce0 + cellR) {
            for (cn in cn0 - cellR..cn0 + cellR) {
                val wayIdxs = grid[cellKey(ce, cn)] ?: continue
                for (wi in wayIdxs) {
                    if (!seen.add(wi)) continue
                    projectOnto(indexed[wi], wi, qe, qn)?.let { c ->
                        if (c.perpM <= radiusM) out.add(c)
                    }
                }
            }
        }
        out.sortBy { it.perpM }
        return if (out.size > limit) out.subList(0, limit).toList() else out
    }

    /**
     * Approximate on-network distance between two candidates, metres — the
     * transition witness for the HMM. Exact along-geometry distance on the
     * same way; via the nearest SHARED junction node across ways; and an
     * honest penalty when no connection is known (which includes the case
     * where the true route leaves the fetched neighbourhood).
     */
    fun pathDistanceM(a: Candidate, b: Candidate): Double {
        // A candidate carries an index into the graph that produced it. Handed
        // one from a different graph — the matcher keeps hypotheses across
        // fixes while the neighbourhood is refetched — that index means
        // nothing here, and indexing blind threw on every fix once the new
        // graph was smaller. Unknown connection, not a crash: RoadMatcher owns
        // the real fix by dropping stale hypotheses; this only guarantees a
        // GPS fix can never take the app down.
        if (a.wayIndex !in indexed.indices || b.wayIndex !in indexed.indices) {
            return unconnectedPenaltyM(a, b)
        }
        if (a.wayIndex == b.wayIndex) return kotlin.math.abs(b.alongM - a.alongM)
        val wa = indexed[a.wayIndex]
        val wb = indexed[b.wayIndex]
        var best = Double.MAX_VALUE
        // Shared junction nodes: walk A's vertices, test membership in B's.
        val bWays = HashSet<Long>().apply { wb.nodeKeys.forEach { add(it) } }
        for (i in wa.nodeKeys.indices) {
            val k = wa.nodeKeys[i]
            if (k !in bWays) continue
            val viaA = kotlin.math.abs(wa.prefixM[i] - a.alongM)
            var viaB = Double.MAX_VALUE
            for (j in wb.nodeKeys.indices) {
                if (wb.nodeKeys[j] == k) {
                    val d = kotlin.math.abs(wb.prefixM[j] - b.alongM)
                    if (d < viaB) viaB = d
                }
            }
            val total = viaA + viaB
            if (total < best) best = total
        }
        if (best != Double.MAX_VALUE) return best
        // No shared node in the loaded graph: either genuinely unconnected
        // here, or connected through geometry we did not fetch. Penalized,
        // not forbidden.
        return unconnectedPenaltyM(a, b)
    }

    private fun unconnectedPenaltyM(a: Candidate, b: Candidate): Double {
        val gc = hypot(toE(b.lon) - toE(a.lon), toN(b.lat) - toN(a.lat))
        return gc * UNCONNECTED_FACTOR + UNCONNECTED_BASE_M
    }

    private fun projectOnto(iw: IndexedWay, wi: Int, qe: Double, qn: Double): Candidate? {
        var bestD2 = Double.MAX_VALUE
        var bestE = 0.0
        var bestN = 0.0
        var bestAlong = 0.0
        var bestHeading = 0f
        for (i in 0 until iw.e.size - 1) {
            val ax = iw.e[i]; val ay = iw.n[i]
            val bx = iw.e[i + 1]; val by = iw.n[i + 1]
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 <= 0.0) 0.0 else (((qe - ax) * dx + (qn - ay) * dy) / len2).coerceIn(0.0, 1.0)
            val px = ax + t * dx; val py = ay + t * dy
            val d2 = (qe - px) * (qe - px) + (qn - py) * (qn - py)
            if (d2 < bestD2) {
                bestD2 = d2
                bestE = px; bestN = py
                bestAlong = iw.prefixM[i] + kotlin.math.sqrt(len2) * t
                bestHeading = bearingOf(dx, dy)
            }
        }
        if (bestD2 == Double.MAX_VALUE) return null
        return Candidate(
            way = iw.way,
            lat = fromN(bestN),
            lon = fromE(bestE),
            perpM = kotlin.math.sqrt(bestD2),
            alongM = bestAlong,
            headingDeg = bestHeading,
            wayIndex = wi,
        )
    }

    /**
     * LEVIATHAN-1 — offline routing over this graph: A* from [from] to [to]
     * (both candidates from [near]), walking way geometry vertex-to-vertex
     * and hopping between ways at shared junction nodes. Returns the (lat,
     * lon) polyline including the snapped entry/exit points, or null when no
     * connection exists inside the loaded tiles — an honest «не бачу шляху в
     * завантаженій окрузі», never a straight line.
     *
     * This is deliberately NOT a planner-grade router: no turn costs, no
     * oneway enforcement unless asked, no hierarchies. It exists for the
     * moment the network is gone and the operator needs a path to a shelter
     * across the district the matcher has already cached.
     */
    fun routeBetween(
        from: Candidate,
        to: Candidate,
        respectOneway: Boolean = false,
        maxExpandedNodes: Int = MAX_ASTAR_NODES,
        /**
         * HORIZON II.1 — the clean-sky cost. A dimensionless multiplier ≥ 1
         * evaluated at each along-way segment's MIDPOINT (lat, lon); the edge
         * costs `length × factor`. Because every edge stays ≥ its length, the
         * straight-line heuristic remains admissible and A* stays optimal.
         * Null = pure shortest path (the LEVIATHAN-1 behaviour).
         */
        segmentCostFactor: ((Double, Double) -> Double)? = null,
        /**
         * The cost of the ROAD, as opposed to the cost of the place it passes
         * through: a multiplier ≥ 1 per way, from its OSM tags. This is what
         * keeps a route out of courtyards and driveways ([RoadProfile]).
         * Evaluated once per way per call, not per edge.
         */
        wayCostFactor: ((RoadWay) -> Double)? = null,
    ): List<Pair<Double, Double>>? = routeTraced(
        from, to, respectOneway, maxExpandedNodes, segmentCostFactor, wayCostFactor,
    )?.points

    /**
     * Що сталось у точці маршруту — за КУТОМ між вхідним і вихідним ребром,
     * не за назвою вулиці.
     *
     * Назва як ключ повороту брехала в обидва боки: перехрестя, де вулиця
     * міняє імʼя, а їхати треба прямо, коштувало як поворот; розворот на місці
     * коштував нуль, бо імʼя те саме. Кут не має жодної з цих сліпих плям.
     */
    enum class TurnKind {
        /** Відхилення менше за [STRAIGHT_MAX_DEG] — це та сама дорога далі. */
        STRAIGHT,

        /** Звичайний поворот на перехресті. */
        TURN,

        /**
         * Розворот: назад тією ж горловиною. ОКРЕМИЙ випадок, а не «великий
         * поворот» — він коштує часу, ризику і подекуди закону, і маршрут не
         * має права брати його як трохи крутіший правий.
         */
        U_TURN,
    }

    /** A route plus the road behind each of its segments. */
    data class Traced(
        val points: List<Pair<Double, Double>>,
        /** The way segment i (points[i] → points[i+1]) runs on; size = points.size - 1. */
        val segmentWays: List<RoadWay>,
        /**
         * Що сталось У точці [points] з тим самим індексом. Кінці завжди
         * [TurnKind.STRAIGHT] — з одного боку немає ребра. Порожній список
         * означає «не рахували», а не «поворотів немає».
         */
        val turnKinds: List<TurnKind> = emptyList(),
    )

    /**
     * Класифікація кожної точки маршруту за геометрією, з одним застереженням:
     * поворотом вважається лише те, що сталось у ВУЗЛІ графа. Згин посеред
     * кварталу — це кривина дороги, а не перехрестя, і брати за нього як за
     * поворот означало б повернути ту саму ваду з іншого боку.
     */
    private fun classifyTurns(pts: List<Pair<Double, Double>>): List<TurnKind> {
        if (pts.size < 3) return List(pts.size) { TurnKind.STRAIGHT }
        val out = ArrayList<TurnKind>(pts.size)
        out.add(TurnKind.STRAIGHT)
        for (i in 1 until pts.size - 1) {
            val slots = nodeToSlots[nodeKey(pts[i].first, pts[i].second)]
            if (slots == null || slots.size < 2) {
                out.add(TurnKind.STRAIGHT)
                continue
            }
            val ae = toE(pts[i].second) - toE(pts[i - 1].second)
            val an = toN(pts[i].first) - toN(pts[i - 1].first)
            val be = toE(pts[i + 1].second) - toE(pts[i].second)
            val bn = toN(pts[i + 1].first) - toN(pts[i].first)
            out.add(turnKind(ae, an, be, bn))
        }
        out.add(TurnKind.STRAIGHT)
        return out
    }

    /**
     * ЧОМУ шляху немає — окремим словом, а не одним `null` на всі випадки.
     *
     * `null` від [routeTraced] означав одразу три різні світи, і споживач
     * (`AppContainer.offlineMeshRoute`) переводив усі три в одне речення:
     * «Доріг цієї ділянки на пристрої немає — потрібен дорожній пакет».
     * Людині з уже встановленим пакетом Києва на 54 МБ це казали тоді, коли
     * пошук просто впирався у власну стелю. Порада була не просто марна —
     * вона вела не туди.
     *
     * Це НЕ нове значення в чужому переліку, а власний перелік цього шару:
     * граф знає рівно те, що знає граф, і не має права називати причину
     * словами застосунку.
     */
    enum class Refusal {
        /** У графі немає жодної дороги — читати не було чого. */
        EMPTY_GRAPH,

        /**
         * Фронт вичерпався, а мети не досяг: у ЗАВАНТАЖЕНІЙ окрузі звʼязку
         * між цими точками справді немає. Єдиний випадок, який чесно означає
         * «доріг тут нема».
         */
        NO_PATH,

        /**
         * Пошук здався на стелі [MAX_ASTAR_NODES]. Дорога може існувати — ми
         * просто не шукали далі. Це твердження про НАС, а не про місцевість,
         * і плутати його з [NO_PATH] означає брехати про світ.
         */
        EXHAUSTED,
    }

    /**
     * [routeBetween], keeping the attribution the caller cannot recover from a
     * bare polyline: which OSM way each segment runs on. That is what lets a
     * duration be built from each road's own speed instead of one flat guess,
     * and lets the caller say whether the number came from posted limits or
     * from an assumption ([RoadProfile.speedBasis]).
     */
    fun routeTraced(
        from: Candidate,
        to: Candidate,
        respectOneway: Boolean = false,
        maxExpandedNodes: Int = MAX_ASTAR_NODES,
        segmentCostFactor: ((Double, Double) -> Double)? = null,
        wayCostFactor: ((RoadWay) -> Double)? = null,
        /** Викликається РІВНО ОДИН раз і лише перед `null` — див. [Refusal]. */
        onRefusal: ((Refusal) -> Unit)? = null,
    ): Traced? {
        if (indexed.isEmpty()) {
            onRefusal?.invoke(Refusal.EMPTY_GRAPH)
            return null
        }
        val wayFactors = DoubleArray(indexed.size) { i ->
            wayCostFactor?.invoke(indexed[i].way)?.coerceAtLeast(1.0) ?: 1.0
        }
        if (from.wayIndex == to.wayIndex && segmentCostFactor == null &&
            wayFactors[from.wayIndex] == 1.0 &&
            !(respectOneway && indexed[from.wayIndex].way.oneway && to.alongM < from.alongM)
        ) {
            // Same way, nothing penalising it: walk the geometry between the
            // two projections.
            //
            // The way factor has to be checked here, not only inside A*. A
            // courtyard that touches both the start and the destination puts
            // both snaps on ONE way, and this shortcut then returned it whole
            // without ever pricing it — which is precisely how the operator
            // kept being routed through прибудинкова територія even after the
            // penalties existed. A per-way factor cannot change the path
            // WITHIN a way, but it decides whether to be on that way at all.
            return tracedSameWay(from, to)
        }

        // Node space: (wayIndex, vertexIndex) encoded in one Long.
        fun enc(wi: Int, vi: Int): Long = (wi.toLong() shl 24) or vi.toLong()
        val goalE = toE(to.lon)
        val goalN = toN(to.lat)

        val dist = HashMap<Long, Double>()
        val prev = HashMap<Long, Long>()
        val open = java.util.PriorityQueue<Pair<Long, Double>>(compareBy { it.second })

        fun heuristic(wi: Int, vi: Int): Double {
            val iw = indexed[wi]
            return hypot(iw.e[vi] - goalE, iw.n[vi] - goalN)
        }

        fun seed(wi: Int, vi: Int, g: Double) {
            val k = enc(wi, vi)
            if (g < (dist[k] ?: Double.MAX_VALUE)) {
                dist[k] = g
                open.add(k to g + heuristic(wi, vi))
            }
        }

        // Entry: the snapped point connects to its segment's two endpoints.
        val fw = indexed[from.wayIndex]
        val entryDir = HashMap<Long, DoubleArray>(4)
        seedEntry(
            from, fw, ::seed, respectOneway, segmentCostFactor, wayFactors[from.wayIndex],
        ) { vi, de, dn -> entryDir[enc(from.wayIndex, vi)] = doubleArrayOf(de, dn) }

        // Goal set: the exit segment's endpoints, with their extra tail cost.
        // On a respected oneway the exit tail must itself travel forward, so
        // only the vertex BEHIND the exit point may close the route — arriving
        // at the far endpoint would walk the last metres against the arrow.
        val tw = indexed[to.wayIndex]
        val exit = exitVertices(to, tw)
        // The exit tail runs along the exit segment; tax it too (see seedEntry).
        val exitF = wayFactors[to.wayIndex] * if (segmentCostFactor != null) {
            edgeFactor(tw, exit.a, exit.b, segmentCostFactor)
        } else 1.0
        val onewayExit = respectOneway && tw.way.oneway
        val goalA = exit.a
        val goalB = if (onewayExit) exit.a else exit.b
        val tailA = exit.tailA * exitF
        val tailB = if (onewayExit) exit.tailA * exitF else exit.tailB * exitF

        // A virtual GOAL node absorbs the exit tails. Relaxing an exit vertex
        // into GOAL (weight = its tail) and terminating only when GOAL is
        // POPPED is what keeps A* optimal under edge penalties: a cheap-h exit
        // vertex must not close the route before a costlier-h detour that is
        // actually shorter has had its chance (the LEVIATHAN break-on-first
        // was correct only for the un-penalised, tail-free short case).
        val goalKey = Long.MAX_VALUE
        var expanded = 0
        while (open.isNotEmpty()) {
            val (key, f) = open.poll()
            val g = dist[key] ?: continue

            if (key == goalKey) break // popped in f-order → optimal

            if (f - heuristicOf(key, goalE, goalN) > g + 1e-6) continue // stale entry

            // Рахувати ПІСЛЯ відсіву несвіжих, а не до нього. Ця черга не вміє
            // зменшувати ключ — вона щоразу кладе ще один запис, — тож до
            // лічильника йшли й ті записи, які тут же відкидаються. Бюджет на
            // 60 000 «розкриттів» витрачався на дублікати, і стеля падала
            // вдвічі раніше за обіцяну.
            if (++expanded > maxExpandedNodes) {
                onRefusal?.invoke(Refusal.EXHAUSTED)
                return null
            }

            val wi = (key shr 24).toInt()
            val vi = (key and 0xFFFFFF).toInt()

            // Exit vertices connect to GOAL through their tail (h(GOAL)=0).
            if (wi == to.wayIndex && (vi == goalA || vi == goalB)) {
                val tail = if (vi == goalA) tailA else tailB
                relax(key, goalKey, g + tail, dist, prev, open) { 0.0 }
            }

            val iw = indexed[wi]
            // Один пошук у мапі на все розкриття. Він потрібен двічі — степінь
            // вузла вирішує, чи це взагалі перехрестя, а самі слоти несуть
            // стрибки, — і колись стояв лише перед стрибками, з `?: continue`
            // після ребер. Порядок тут навмисний: рух уздовж дороги мусить
            // лишитись можливим у вузлі, якого в мапі немає.
            val slots = nodeToSlots[iw.nodeKeys[vi]]
            // Поворот беруть НЕ на стрибку через перехрестя — стрибок нульової
            // довжини й напрямку не має, — а на першому ж кроці після нього,
            // коли вихідне ребро нарешті відоме. Саме тому розворот (стрибок
            // на зустрічну лінію того самого вузла й крок назад) тут видно, а
            // за назвами вулиць його не було видно ніколи.
            val atJunction = respectOneway && slots != null && slots.size >= 2
            if (vi + 1 < iw.e.size) {
                val step = (iw.prefixM[vi + 1] - iw.prefixM[vi]) *
                    edgeFactor(iw, vi, vi + 1, segmentCostFactor) * wayFactors[wi]
                val turn = if (atJunction) turnCostM(prev, entryDir, key, wi, vi, vi + 1) else 0.0
                relax(key, enc(wi, vi + 1), g + step + turn, dist, prev, open) { heuristic(wi, vi + 1) }
            }
            if (vi - 1 >= 0 && !(respectOneway && iw.way.oneway)) {
                val step = (iw.prefixM[vi] - iw.prefixM[vi - 1]) *
                    edgeFactor(iw, vi - 1, vi, segmentCostFactor) * wayFactors[wi]
                val turn = if (atJunction) turnCostM(prev, entryDir, key, wi, vi, vi - 1) else 0.0
                relax(key, enc(wi, vi - 1), g + step + turn, dist, prev, open) { heuristic(wi, vi - 1) }
            }
            // Junction hops: transfer to every way sharing this node. Сам
            // стрибок коштує лише СХОДИННЯ З МАГІСТРАЛІ — це твердження про
            // класи доріг, а не про кут, і плутати його з поворотом було
            // половиною старої вади.
            if (slots == null) continue
            for (slot in slots) {
                val wj = (slot shr 24).toInt()
                if (wj == wi) continue
                val vj = (slot and 0xFFFFFF).toInt()
                val junctionCost = if (respectOneway) {
                    hierarchyDropPenalty(iw.way, indexed[wj].way)
                } else 0.0
                relax(key, slot, g + junctionCost, dist, prev, open) { heuristic(wj, vj) }
            }
        }
        // GOAL is reached only if it has a predecessor (an exit vertex).
        val reached = prev[goalKey] ?: run {
            onRefusal?.invoke(Refusal.NO_PATH)
            return null
        }

        // Rebuild: exit vertex ← vertex chain ← entry point (the GOAL
        // sentinel carries no geometry and is dropped).
        val chain = ArrayList<Long>()
        var cur: Long? = reached
        while (cur != null) {
            chain.add(cur)
            cur = prev[cur]
        }
        chain.reverse()
        val pts = ArrayList<Pair<Double, Double>>(chain.size + 2)
        val segWays = ArrayList<RoadWay>(chain.size + 1)
        pts.add(from.lat to from.lon)
        for (k in chain) {
            val wi = (k shr 24).toInt()
            val vi = (k and 0xFFFFFF).toInt()
            val iw = indexed[wi]
            pts.add(fromN(iw.n[vi]) to fromE(iw.e[vi]))
            // A junction hop keeps the same coordinates, so the segment it
            // closes has no length and its attribution cannot be wrong.
            segWays.add(iw.way)
        }
        pts.add(to.lat to to.lon)
        segWays.add(indexed[to.wayIndex].way)
        val keep = keptIndices(pts)
        val kept = keep.map { pts[it] }
        return Traced(
            points = kept,
            segmentWays = keep.drop(1).map { segWays[it - 1] },
            turnKinds = classifyTurns(kept),
        )
    }

    /**
     * Ціна повороту у вузлі [vi] дороги [wi], якщо звідти ступити на [nextVi].
     *
     * Вхідний напрямок доводиться відновлювати назад по [prev]: стрибок через
     * перехрестя не має ні довжини, ні напрямку, а їх може бути кілька підряд
     * (три дороги в одному вузлі). Відкочуємось, доки ребро не набуде
     * довжини, яку ще можна назвати напрямком.
     *
     * Наближення, і назване: у просторі станів A* стан — це (дорога, вершина)
     * без напрямку, тож «звідки прийшли» береться з поточного [prev], який
     * пізніше може змінитись. Евристика лишається допустимою за будь-якого
     * результату — поворот тільки ДОДАЄ вартості, а пряма відстань її не
     * враховує, — тож оптимальність A* під загрозу не ставиться; неточним може
     * бути лише сам рахунок у рідкісному вузлі, який переоцінили пізніше.
     */
    private fun turnCostM(
        prev: HashMap<Long, Long>,
        entryDir: HashMap<Long, DoubleArray>,
        key: Long,
        wi: Int,
        vi: Int,
        nextVi: Int,
    ): Double {
        val iw = indexed[wi]
        var ae = 0.0
        var an = 0.0
        var cur = key
        var hops = 0
        while (true) {
            val p = prev[cur]
            if (p == null) {
                // Вершини, засіяні входом, НЕ мають попередника — їх кладе
                // `seed`, а не `relax`. Без цієї гілки розворот на першому ж
                // перехресті після старту коштував нуль, бо «звідки приїхали»
                // не було де прочитати; напрямок входу відомий, просто лежить
                // окремо.
                val d = entryDir[cur] ?: return 0.0
                ae = d[0]
                an = d[1]
                break
            }
            val pw = (p shr 24).toInt()
            val pv = (p and 0xFFFFFF).toInt()
            if (pw !in indexed.indices) return 0.0
            ae = iw.e[vi] - indexed[pw].e[pv]
            an = iw.n[vi] - indexed[pw].n[pv]
            if (ae * ae + an * an > MIN_DIR_M2) break
            cur = p
            if (++hops > MAX_HOP_BACKTRACK) return 0.0
        }
        return turnPenaltyM(turnKind(ae, an, iw.e[nextVi] - iw.e[vi], iw.n[nextVi] - iw.n[vi]))
    }

    @Volatile
    private var turnGraphCache: TurnGraph? = null

    internal fun turnGraph(): TurnGraph =
        turnGraphCache ?: synchronized(this) {
            turnGraphCache ?: TurnGraph.build(this).also { turnGraphCache = it }
        }

    internal val hasTurnGraph: Boolean get() = turnGraphCache != null

    internal fun vertexCount(wi: Int): Int = indexed[wi].e.size
    internal fun planeE(wi: Int, vi: Int): Double = indexed[wi].e[vi]
    internal fun planeN(wi: Int, vi: Int): Double = indexed[wi].n[vi]
    internal fun prefixAt(wi: Int, vi: Int): Double = indexed[wi].prefixM[vi]
    internal fun nodeKeyAt(wi: Int, vi: Int): Long = indexed[wi].nodeKeys[vi]
    internal fun slotsAt(key: Long): LongArray? = nodeToSlots[key]
    internal fun wayAt(wi: Int): RoadWay = indexed[wi].way

    internal fun segmentFactor(wi: Int, k: Int, factor: ((Double, Double) -> Double)?): Double =
        edgeFactor(indexed[wi], k, k + 1, factor)

    internal fun segmentOf(wi: Int, alongM: Double): Int {
        val p = indexed[wi].prefixM
        var seg = 0
        while (seg + 1 < p.size && p[seg + 1] < alongM) seg++
        return seg.coerceAtMost((p.size - 2).coerceAtLeast(0))
    }

    /** [routeTraced] по графу поворотів: ті самі ваги, поворот точно за кутом, стелі немає. */
    internal fun routeTurnAware(
        from: Candidate,
        to: Candidate,
        respectOneway: Boolean = false,
        segmentCostFactor: ((Double, Double) -> Double)? = null,
        wayCostFactor: ((RoadWay) -> Double)? = null,
        onRefusal: ((Refusal) -> Unit)? = null,
    ): Traced? {
        if (indexed.isEmpty()) {
            onRefusal?.invoke(Refusal.EMPTY_GRAPH)
            return null
        }
        if (from.wayIndex !in indexed.indices || to.wayIndex !in indexed.indices) {
            onRefusal?.invoke(Refusal.NO_PATH)
            return null
        }
        val wayFactors = DoubleArray(indexed.size) { i ->
            wayCostFactor?.invoke(indexed[i].way)?.coerceAtLeast(1.0) ?: 1.0
        }
        if (from.wayIndex == to.wayIndex && segmentCostFactor == null &&
            wayFactors[from.wayIndex] == 1.0 &&
            !(respectOneway && indexed[from.wayIndex].way.oneway && to.alongM < from.alongM)
        ) {
            return tracedSameWay(from, to)
        }
        val path = turnGraph().route(from, to, respectOneway, wayFactors, segmentCostFactor)
            ?: run {
                onRefusal?.invoke(Refusal.NO_PATH)
                return null
            }
        val pts = ArrayList<Pair<Double, Double>>()
        val segWays = ArrayList<RoadWay>()
        pts.add(from.lat to from.lon)
        fun vertex(wi: Int, vi: Int) {
            val iw = indexed[wi]
            pts.add(fromN(iw.n[vi]) to fromE(iw.e[vi]))
            segWays.add(iw.way)
        }
        val idx = turnGraph()
        val arcs = path.arcs
        if (path.direct) {
            val a = arcs[0]
            val wi = idx.pieceWay[a shr 1]
            val p = indexed[wi].prefixM
            if (a and 1 == 0) {
                for (v in p.indices) if (p[v] > from.alongM && p[v] < to.alongM) vertex(wi, v)
            } else {
                for (v in p.indices.reversed()) if (p[v] < from.alongM && p[v] > to.alongM) vertex(wi, v)
            }
        } else {
            for ((i, a) in arcs.withIndex()) {
                val piece = a shr 1
                val wi = idx.pieceWay[piece]
                val pa = idx.pieceA[piece]
                val pb = idx.pieceB[piece]
                val fwd = a and 1 == 0
                val first = i == 0
                val last = i == arcs.size - 1
                var lo: Int
                var hi: Int
                if (fwd) {
                    lo = if (first) segmentOf(wi, from.alongM) + 1 else pa + 1
                    hi = if (last) segmentOf(wi, to.alongM) else pb
                    for (v in lo..hi) vertex(wi, v)
                } else {
                    hi = if (first) segmentOf(wi, from.alongM) else pb - 1
                    lo = if (last) segmentOf(wi, to.alongM) + 1 else pa
                    for (v in hi downTo lo) vertex(wi, v)
                }
            }
        }
        pts.add(to.lat to to.lon)
        segWays.add(indexed[to.wayIndex].way)
        val keep = keptIndices(pts)
        val kept = keep.map { pts[it] }
        return Traced(
            points = kept,
            segmentWays = keep.drop(1).map { segWays[it - 1] },
            turnKinds = classifyTurns(kept),
        )
    }

    private fun heuristicOf(key: Long, goalE: Double, goalN: Double): Double {
        val wi = (key shr 24).toInt()
        val vi = (key and 0xFFFFFF).toInt()
        val iw = indexed[wi]
        return hypot(iw.e[vi] - goalE, iw.n[vi] - goalN)
    }

    /** The cost multiplier for one along-way edge, sampled at its midpoint. */
    private fun edgeFactor(
        iw: IndexedWay,
        a: Int,
        b: Int,
        factor: ((Double, Double) -> Double)?,
    ): Double {
        if (factor == null) return 1.0
        val midLat = fromN((iw.n[a] + iw.n[b]) / 2.0)
        val midLon = fromE((iw.e[a] + iw.e[b]) / 2.0)
        return factor(midLat, midLon).coerceAtLeast(1.0)
    }

    /**
     * HORIZON III.2 — reachability for isochrones. Dijkstra from (fromLat,
     * fromLon) over the loaded graph, returning every traversed edge with the
     * metres-travelled to reach its far endpoint, out to [maxCostM]. The
     * caller buckets these into time bands and colours them; the graph owns
     * the traversal so the presentation layer never touches vertex internals.
     */
    fun reachable(
        fromLat: Double,
        fromLon: Double,
        maxCostM: Double,
        respectOneway: Boolean = false,
    ): List<ReachEdge> {
        if (indexed.isEmpty()) return emptyList()
        val start = near(fromLat, fromLon, 120.0, limit = 1).firstOrNull() ?: return emptyList()
        fun enc(wi: Int, vi: Int): Long = (wi.toLong() shl 24) or vi.toLong()

        // Dijkstra computes the cost-to-reach for every vertex; the edges are
        // emitted in a SECOND pass. Emitting during relaxation was wrong: a
        // seed pre-populates its segment's far vertex, so the near edge into it
        // was never `< dist` and silently dropped — the roads closest to the
        // operator vanished from the isochrone.
        val dist = HashMap<Long, Double>()
        val open = java.util.PriorityQueue<Pair<Long, Double>>(compareBy { it.second })

        val sw = indexed[start.wayIndex]
        var seg = 0
        while (seg + 1 < sw.prefixM.size && sw.prefixM[seg + 1] < start.alongM) seg++
        for (v in intArrayOf(seg, (seg + 1).coerceAtMost(sw.e.size - 1))) {
            val d = kotlin.math.abs(sw.prefixM[v] - start.alongM)
            if (d <= maxCostM) {
                dist[enc(start.wayIndex, v)] = d
                open.add(enc(start.wayIndex, v) to d)
            }
        }

        while (open.isNotEmpty()) {
            val (key, g) = open.poll()
            if (g > (dist[key] ?: Double.MAX_VALUE) + 1e-6) continue
            val wi = (key shr 24).toInt()
            val vi = (key and 0xFFFFFF).toInt()
            val iw = indexed[wi]

            fun consider(nv: Int) {
                val ng = g + kotlin.math.abs(iw.prefixM[nv] - iw.prefixM[vi])
                if (ng > maxCostM) return
                val nk = enc(wi, nv)
                if (ng < (dist[nk] ?: Double.MAX_VALUE)) {
                    dist[nk] = ng
                    open.add(nk to ng)
                }
            }
            if (vi + 1 < iw.e.size) consider(vi + 1)
            if (vi - 1 >= 0 && !(respectOneway && iw.way.oneway)) consider(vi - 1)

            val slots = nodeToSlots[iw.nodeKeys[vi]] ?: continue
            for (nk in slots) {
                if ((nk shr 24).toInt() == wi) continue
                if (g < (dist[nk] ?: Double.MAX_VALUE)) {
                    dist[nk] = g
                    open.add(nk to g)
                }
            }
        }

        // Second pass: every along-way edge with BOTH endpoints reached is a
        // reachable stretch, coloured by the costlier of its two ends (the
        // moment along it the operator can still be within budget).
        val out = ArrayList<ReachEdge>()
        for (wi in indexed.indices) {
            val iw = indexed[wi]
            for (i in 0 until iw.e.size - 1) {
                val du = dist[enc(wi, i)] ?: continue
                val dv = dist[enc(wi, i + 1)] ?: continue
                val cost = maxOf(du, dv)
                if (cost <= maxCostM) {
                    out.add(
                        ReachEdge(
                            aLat = fromN(iw.n[i]), aLon = fromE(iw.e[i]),
                            bLat = fromN(iw.n[i + 1]), bLon = fromE(iw.e[i + 1]),
                            costM = cost,
                        ),
                    )
                }
            }
        }
        return out
    }

    private inline fun relax(
        fromKey: Long,
        toKey: Long,
        g: Double,
        dist: HashMap<Long, Double>,
        prev: HashMap<Long, Long>,
        open: java.util.PriorityQueue<Pair<Long, Double>>,
        h: () -> Double,
    ) {
        if (g < (dist[toKey] ?: Double.MAX_VALUE)) {
            dist[toKey] = g
            prev[toKey] = fromKey
            open.add(toKey to g + h())
        }
    }

    private fun seedEntry(
        from: Candidate,
        iw: IndexedWay,
        seed: (Int, Int, Double) -> Unit,
        respectOneway: Boolean,
        factor: ((Double, Double) -> Double)?,
        wayFactor: Double,
        /** Напрямок, яким рух заходить у засіяну вершину — див. [turnCostM]. */
        noteDir: (Int, Double, Double) -> Unit,
    ) {
        // The segment the entry point sits on: prefix index by alongM.
        var seg = 0
        while (seg + 1 < iw.prefixM.size && iw.prefixM[seg + 1] < from.alongM) seg++
        // The entry tail runs along THIS segment, so it pays THIS segment's
        // cost factor — without it, a route whose whole length is entry+exit
        // tail (both endpoints on one segment) would bypass the penalty
        // entirely and clean-sky routing would never fire on a short hop.
        val f = wayFactor * if (factor != null && seg + 1 < iw.e.size) {
            edgeFactor(iw, seg, seg + 1, factor)
        } else 1.0
        val backCost = (from.alongM - iw.prefixM[seg]) * f
        val fwdCost = (iw.prefixM.getOrElse(seg + 1) { iw.prefixM.last() } - from.alongM) * f
        val pe = toE(from.lon)
        val pn = toN(from.lat)
        if (!(respectOneway && iw.way.oneway)) {
            seed(from.wayIndex, seg, backCost)
            noteDir(seg, iw.e[seg] - pe, iw.n[seg] - pn)
        }
        if (seg + 1 < iw.e.size) {
            seed(from.wayIndex, seg + 1, fwdCost)
            noteDir(seg + 1, iw.e[seg + 1] - pe, iw.n[seg + 1] - pn)
        }
    }

    private data class Exit(val a: Int, val b: Int, val tailA: Double, val tailB: Double)

    private fun exitVertices(to: Candidate, iw: IndexedWay): Exit {
        var seg = 0
        while (seg + 1 < iw.prefixM.size && iw.prefixM[seg + 1] < to.alongM) seg++
        val a = seg
        val b = (seg + 1).coerceAtMost(iw.e.size - 1)
        return Exit(
            a = a,
            b = b,
            tailA = to.alongM - iw.prefixM[a],
            tailB = kotlin.math.abs(iw.prefixM[b] - to.alongM),
        )
    }

    private fun sameWayPath(from: Candidate, to: Candidate): List<Pair<Double, Double>> {
        val iw = indexed[from.wayIndex]
        val lo = minOf(from.alongM, to.alongM)
        val hi = maxOf(from.alongM, to.alongM)
        val mids = ArrayList<Pair<Double, Double>>()
        for (i in iw.prefixM.indices) {
            if (iw.prefixM[i] > lo && iw.prefixM[i] < hi) {
                mids.add(fromN(iw.n[i]) to fromE(iw.e[i]))
            }
        }
        if (from.alongM > to.alongM) mids.reverse()
        return dedupeConsecutive(
            buildList {
                add(from.lat to from.lon)
                addAll(mids)
                add(to.lat to to.lon)
            },
        )
    }

    private fun tracedSameWay(from: Candidate, to: Candidate): Traced {
        val pts = sameWayPath(from, to)
        val way = indexed[from.wayIndex].way
        return Traced(pts, List((pts.size - 1).coerceAtLeast(0)) { way }, classifyTurns(pts))
    }

    private fun keptIndices(pts: List<Pair<Double, Double>>): List<Int> =
        pts.indices.filter { i ->
            i == 0 || haversineM(pts[i - 1].first, pts[i - 1].second, pts[i].first, pts[i].second) > 0.5
        }

    private fun dedupeConsecutive(pts: List<Pair<Double, Double>>): List<Pair<Double, Double>> =
        keptIndices(pts).map { pts[it] }

    private fun toE(lon: Double) = Math.toRadians(lon - anchorLon) * EARTH_RADIUS_M * cosLat
    private fun toN(lat: Double) = Math.toRadians(lat - anchorLat) * EARTH_RADIUS_M
    private fun fromE(e: Double) = anchorLon + Math.toDegrees(e / (EARTH_RADIUS_M * cosLat))
    private fun fromN(n: Double) = anchorLat + Math.toDegrees(n / EARTH_RADIUS_M)

    private fun cellOf(m: Double): Int = floor(m / CELL_M).toInt()
    private fun cellKey(ce: Int, cn: Int): Long = (ce.toLong() shl 32) xor (cn.toLong() and 0xFFFFFFFFL)

    companion object {
        private const val CELL_M = 120.0

        /** ОДНЕ кодування вузла A* на весь файл: [routeTraced] і [reachable]
         *  пакують свої ключі так само, і [nodeToSlots] мусить збігтися з
         *  ними байт у байт, інакше стрибок через перехрестя влучає в чужу
         *  дорогу. */
        internal fun encodeSlot(wi: Int, vi: Int): Long = (wi.toLong() shl 24) or vi.toLong()

        /** OSM ways sharing a junction share the node's exact coordinates;
         *  1e-5° (~1.1 m) of rounding absorbs any serializer wobble. */
        internal fun nodeKey(lat: Double, lon: Double): Long {
            val la = Math.round(lat * 1e5)
            val lo = Math.round(lon * 1e5)
            return (la shl 32) xor (lo and 0xFFFFFFFFL)
        }

        private const val UNCONNECTED_FACTOR = 2.0
        private const val UNCONNECTED_BASE_M = 60.0

        /**
         * Стеля розкриттів A*, і вона була ЗАМАЛА для звичайного міста.
         *
         * Коментар тут стверджував, що впертись у неї означає «мета поза
         * завантаженим світом». Заміряно 16.09.2026 на справжньому київському
         * пакеті (`kyiv-v5`, 147 373 дороги в межах 50,30–50,60 / 30,30–30,75),
         * профіль АВТО, шість реальних пар по місту:
         *
         *   Святошин → Лівобережна   60k: НЕМАЄ · 180k: 21,18 км за 374 мс
         *   Голосіїв → Виноградар    60k: НЕМАЄ · 180k: 24,70 км за 366 мс
         *   дім власника → Майдан    60k: 13,00 км
         *   Позняки → Майдан         60k: 14,46 км
         *
         * Тобто **чотири з шести** звичайних поїздок по Києву не мали маршруту
         * взагалі — і людині показувалось не «пошук здався», а
         * [RoadRefusal.NO_DATA], «доріг тут немає», при встановленому пакеті
         * Києва на 54 МБ. Стеля, названа «мета поза світом», насправді ловила
         * мету за вісім кілометрів.
         *
         * 200 000 — з запасом над виміряними 180 000. Ціна заміряна тут же:
         * ~20 МБ фронту (≈100 байт на розкриття) і ~0,4 с на цій машині.
         *
         * Чого воно СВІДОМО не покриває: Київ → Біла Церква (86 км, найдовше,
         * що взагалі дозволяє рамка 0,75° в `offlineMeshRoute`) вимагає 600 000
         * і ~60 МБ понад уже великий граф — на телефоні це OOM. Така поїздка
         * чесно впирається в стелю й тепер КАЖЕ про це ([Refusal.EXHAUSTED]),
         * а не вдає, що доріг немає.
         */
        internal const val MAX_ASTAR_NODES = 200_000

        /**
         * Поворот коштує пошукові рівно стільки, скільки застосунок за нього
         * НАРАХОВУЄ людині: [LocalRouter.INTERSECTION_TURN_DELAY_S] = 12 с
         * міським темпом 7,5 м/с. Було 25 м (3,3 с) — утричі з половиною менше
         * за власну ж заявлену затримку, тобто пошук обирав маршрут за однією
         * ціною повороту, а хвилини рахував за іншою.
         *
         * Чому це не косметика, заміряно 18.09 на `kyiv-v5`, поїздка дім
         * власника → Майдан: на 25 м пошук ішов 13 608 м за 1667 с, на 90 м —
         * 12 995 м за 1449 с. Тією ж дорогою, що її обирала стара мірка за
         * назвами: її вигадані штрафи випадково гнали маршрут туди, куди
         * чесна ціна повороту жене й сама. Прибрати вигадку, не поправивши
         * число, означало б зняти підпору й лишити яму.
         */
        private const val BASE_TURN_PENALTY_M = 90.0
        private const val HIERARCHY_DROP_PENALTY_M = 50.0

        /**
         * Розворот коштує ЯВНО, і число назване тут, а не виведене з повороту.
         *
         * 200 м — це ≈27 с міського темпу ([LocalRouter.CAR_SPEED_MPS]),
         * тобто приблизно те, чого розворот і коштує: очікування вікна в
         * зустрічному потоці. Обрано, не заміряно — як і 25 м звичайного
         * повороту поруч. Порядок важливіший за друге після коми: раніше тут
         * стояв НУЛЬ, бо вулиця по обидва боки розвороту зветься однаково.
         */
        private const val U_TURN_PENALTY_M = 200.0

        /** Нижче цього кута маршрут їде прямо, а не повертає. */
        private const val STRAIGHT_MAX_DEG = 30.0

        /** Вище цього кута це вже розворот, а не крутий поворот. */
        private const val U_TURN_MIN_DEG = 150.0

        private val COS_STRAIGHT = cos(Math.toRadians(STRAIGHT_MAX_DEG))
        private val COS_U_TURN = cos(Math.toRadians(U_TURN_MIN_DEG))

        /** Квадрат довжини, нижче якої ребро не має напрямку — та сама піввміра
         *  (0,5 м), якою [keptIndices] відсіює точки-двійники. */
        internal const val MIN_DIR_M2 = 0.25

        /** Скільки стрибків нульової довжини поспіль ще варто відмотувати. */
        private const val MAX_HOP_BACKTRACK = 8

        /**
         * ОДИН вирок про кут на весь файл: A* міряє його в метрах площини,
         * [classifyTurns] — у координатах маршруту, і розійтись вони не мають
         * права, бо тоді нарахована вартість і показана людині кількість
         * поворотів говорили б про різні повороти.
         */
        internal fun turnKind(ae: Double, an: Double, be: Double, bn: Double): TurnKind {
            val la = kotlin.math.sqrt(ae * ae + an * an)
            val lb = kotlin.math.sqrt(be * be + bn * bn)
            if (la <= 0.0 || lb <= 0.0) return TurnKind.STRAIGHT
            val cos = ((ae * be + an * bn) / (la * lb)).coerceIn(-1.0, 1.0)
            return when {
                cos >= COS_STRAIGHT -> TurnKind.STRAIGHT
                cos <= COS_U_TURN -> TurnKind.U_TURN
                else -> TurnKind.TURN
            }
        }

        internal fun turnPenaltyM(kind: TurnKind): Double = when (kind) {
            TurnKind.STRAIGHT -> 0.0
            TurnKind.TURN -> BASE_TURN_PENALTY_M
            TurnKind.U_TURN -> U_TURN_PENALTY_M
        }

        private val MAJOR_HIGHWAYS = setOf(
            "motorway", "motorway_link", "trunk", "trunk_link",
            "primary", "primary_link", "secondary", "secondary_link", "tertiary", "tertiary_link"
        )
        private val MINOR_HIGHWAYS = setOf(
            "residential", "service", "living_street", "unclassified", "track"
        )

        /**
         * Схід із магістралі в дворовий проїзд — твердження про КЛАСИ доріг, і
         * воно лишається тим, чим було. Повороту тут більше немає: кут не має
         * стосунку до того, що мережа стала дрібнішою, а раніше обидва сиділи в
         * одній функції за спільним ключем — рівністю назв.
         */
        internal fun hierarchyDropPenalty(from: RoadWay, to: RoadWay): Double =
            if (from.tags["highway"] in MAJOR_HIGHWAYS && to.tags["highway"] in MINOR_HIGHWAYS) {
                HIERARCHY_DROP_PENALTY_M
            } else {
                0.0
            }
    }
}
