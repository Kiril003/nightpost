package local.phantom.companion.core.sensor.nav

/**
 * Граф поворотів: стан пошуку — напрямлена ділянка між двома вузлами, тож
 * поворот і розворот оплачуються точно, за кутом, а не наближенням з `prev`.
 * Пошук — A* без стелі; памʼять обмежена розміром коридору, не розкриттями.
 *
 * CCH над цим графом пробували (гілка cloud/routing-ch, 88bac81) і зняли:
 * на kyiv-v5 доповнення коридору — 3,6–8,7 млн ребер (120–284 МБ) при
 * налаштуванні 0,5–1,7 с на ноутбуці, а поле глушіння міняє ваги з кожним
 * фіксом. Виграш запиту — десятки мілісекунд.
 */
internal class TurnGraph private constructor(
    val graph: RoadGraph,
    val wayFirstPiece: IntArray,
    val pieceWay: IntArray,
    val pieceA: IntArray,
    val pieceB: IntArray,
    val firstOut: IntArray,
    val outTail: IntArray,
    val outHead: IntArray,
    val outKind: ByteArray,
    val outHop: DoubleArray,
    val firstIn: IntArray,
    val inEdge: IntArray,
    val buildMs: Long,
) {

    val arcCount: Int get() = pieceWay.size * 2
    val transitionCount: Int get() = outHead.size

    private val transVehicle: DoubleArray by lazy {
        DoubleArray(outHead.size) { outHop[it] + RoadGraph.turnPenaltyM(KINDS[outKind[it].toInt()]) }
    }
    private val transFoot: DoubleArray by lazy { DoubleArray(outHead.size) }

    val topologyBytes: Long
        get() = 4L * (wayFirstPiece.size + pieceWay.size * 3 + firstOut.size + firstIn.size) +
            outHead.size.toLong() * (4 + 4 + 4 + 1 + 8 + 4 + 8)

    val searchBytes: Long get() = arcCount.toLong() * (8 + 4 + 8 + 4 + 4)

    /** Ваги одного профілю й одного стану полів. */
    class Metric(
        val respectOneway: Boolean,
        /** Ціна пройти дугу цілком; [INF] — проти одностороннього руху. */
        val arcCost: DoubleArray,
        /** Ціна переходу (стрибок + поворот), за індексом переходу. */
        val trans: DoubleArray,
        val wayFactors: DoubleArray,
        val field: ((Double, Double) -> Double)?,
    )

    /** Задача одного запиту в термінах дуг — спільна для пошуку й оракула Дейкстри. */
    class Problem(
        val sourceArcs: IntArray,
        val sourceCost: DoubleArray,
        val targetArcs: IntArray,
        /** Ціна від ПОЧАТКУ цільової дуги до точки виходу. */
        val targetCost: DoubleArray,
        /** Пряма відстань уздовж однієї ділянки, коли вхід і вихід на ній, або [INF]. */
        val directCost: Double,
        val directArc: Int,
    )

    class ArcPath(val arcs: IntArray, val direct: Boolean, val cost: Double)

    // ---- ваги ---------------------------------------------------------------

    private fun segCost(wi: Int, k: Int, m: Metric): Double =
        (graph.prefixAt(wi, k + 1) - graph.prefixAt(wi, k)) * m.wayFactors[wi] *
            graph.segmentFactor(wi, k, m.field)

    fun metric(
        respectOneway: Boolean,
        wayFactors: DoubleArray,
        field: ((Double, Double) -> Double)?,
    ): Metric {
        val pieces = pieceWay.size
        val arcCost = DoubleArray(pieces * 2)
        val proto = Metric(respectOneway, arcCost, DoubleArray(0), wayFactors, field)
        for (p in 0 until pieces) {
            val wi = pieceWay[p]
            var c = 0.0
            for (k in pieceA[p] until pieceB[p]) c += segCost(wi, k, proto)
            arcCost[2 * p] = c
            arcCost[2 * p + 1] = if (respectOneway && graph.wayAt(wi).oneway) INF else c
        }
        return Metric(respectOneway, arcCost, if (respectOneway) transVehicle else transFoot, wayFactors, field)
    }

    /** Ціна від початку ділянки до точки [alongM] уздовж напрямку «вперед». */
    private fun costFromPieceStart(p: Int, alongM: Double, m: Metric): Double {
        val wi = pieceWay[p]
        val s = graph.segmentOf(wi, alongM)
        var c = 0.0
        for (k in pieceA[p] until s) c += segCost(wi, k, m)
        return c + (alongM - graph.prefixAt(wi, s)) * m.wayFactors[wi] * graph.segmentFactor(wi, s, m.field)
    }

    /** Ціна від точки [alongM] до кінця ділянки (уперед). */
    private fun costToPieceEnd(p: Int, alongM: Double, m: Metric): Double {
        val wi = pieceWay[p]
        val s = graph.segmentOf(wi, alongM)
        var c = (graph.prefixAt(wi, s + 1) - alongM) * m.wayFactors[wi] * graph.segmentFactor(wi, s, m.field)
        for (k in s + 1 until pieceB[p]) c += segCost(wi, k, m)
        return c
    }

    fun pieceOf(wi: Int, alongM: Double): Int {
        val s = graph.segmentOf(wi, alongM)
        var lo = wayFirstPiece[wi]
        var hi = wayFirstPiece[wi + 1] - 1
        // Остання ділянка з A ≤ s.
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (pieceA[mid] <= s) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun problem(from: RoadGraph.Candidate, to: RoadGraph.Candidate, m: Metric): Problem {
        val p = pieceOf(from.wayIndex, from.alongM)
        val q = pieceOf(to.wayIndex, to.alongM)
        val srcA = ArrayList<Int>(2)
        val srcC = ArrayList<Double>(2)
        srcA += 2 * p; srcC += costToPieceEnd(p, from.alongM, m)
        if (m.arcCost[2 * p + 1] < INF) {
            srcA += 2 * p + 1; srcC += costFromPieceStart(p, from.alongM, m)
        }
        val tgtA = ArrayList<Int>(2)
        val tgtC = ArrayList<Double>(2)
        tgtA += 2 * q; tgtC += costFromPieceStart(q, to.alongM, m)
        if (m.arcCost[2 * q + 1] < INF) {
            tgtA += 2 * q + 1; tgtC += costToPieceEnd(q, to.alongM, m)
        }
        var direct = INF
        var directArc = -1
        if (p == q) {
            val cf = costFromPieceStart(p, to.alongM, m) - costFromPieceStart(p, from.alongM, m)
            if (to.alongM >= from.alongM) {
                direct = cf; directArc = 2 * p
            } else if (m.arcCost[2 * p + 1] < INF) {
                direct = -cf; directArc = 2 * p + 1
            }
        }
        return Problem(
            srcA.toIntArray(), srcC.toDoubleArray(), tgtA.toIntArray(), tgtC.toDoubleArray(),
            direct, directArc,
        )
    }


    private var sDist = DoubleArray(0)
    private var sPred = IntArray(0)
    private var sTgt = DoubleArray(0)
    private var sTgtArc = IntArray(0)
    private var sStamp = IntArray(0)
    private var sEpoch = 0

    fun route(
        from: RoadGraph.Candidate,
        to: RoadGraph.Candidate,
        respectOneway: Boolean,
        wayFactors: DoubleArray,
        field: ((Double, Double) -> Double)?,
    ): ArcPath? {
        val m = metric(respectOneway, wayFactors, field)
        return search(problem(from, to, m), m, to)
    }

    // Евристика — пряма від кінця дуги до точки виходу: кожна вага ≥ довжини ≥ прямої.
    @Synchronized
    fun search(pr: Problem, m: Metric, to: RoadGraph.Candidate): ArcPath? {
        val n = arcCount
        if (sDist.size != n) {
            sDist = DoubleArray(n); sPred = IntArray(n); sTgt = DoubleArray(n)
            sTgtArc = IntArray(n); sStamp = IntArray(n); sEpoch = 0
        }
        if (++sEpoch == Int.MAX_VALUE) { sStamp.fill(0); sEpoch = 1 }
        val ep = sEpoch
        fun init(a: Int) {
            if (sStamp[a] != ep) {
                sStamp[a] = ep; sDist[a] = INF; sPred[a] = -1; sTgt[a] = INF; sTgtArc[a] = -1
            }
        }
        // Точка виходу в площині графа.
        val tw = to.wayIndex
        val ts = graph.segmentOf(tw, to.alongM)
        val tl = graph.prefixAt(tw, ts + 1) - graph.prefixAt(tw, ts)
        val tf = if (tl > 0) ((to.alongM - graph.prefixAt(tw, ts)) / tl).coerceIn(0.0, 1.0) else 0.0
        val tE = graph.planeE(tw, ts) + tf * (graph.planeE(tw, ts + 1) - graph.planeE(tw, ts))
        val tN = graph.planeN(tw, ts) + tf * (graph.planeN(tw, ts + 1) - graph.planeN(tw, ts))
        fun h(a: Int): Double {
            val p = a shr 1
            val v = if (a and 1 == 0) pieceB[p] else pieceA[p]
            val wi = pieceWay[p]
            return kotlin.math.hypot(graph.planeE(wi, v) - tE, graph.planeN(wi, v) - tN)
        }
        for (i in pr.targetArcs.indices) {
            val tq = pr.targetArcs[i]
            for (k in firstIn[tq] until firstIn[tq + 1]) {
                val e = inEdge[k]
                val tail = outTail[e]
                init(tail)
                val w = m.trans[e] + pr.targetCost[i]
                if (w < sTgt[tail]) { sTgt[tail] = w; sTgtArc[tail] = tq }
            }
        }
        val heap = MinHeap()
        for (i in pr.sourceArcs.indices) {
            val a = pr.sourceArcs[i]
            init(a)
            if (pr.sourceCost[i] < sDist[a]) {
                sDist[a] = pr.sourceCost[i]
                heap.push(sDist[a] + h(a), a)
            }
        }
        var best = pr.directCost
        var bestArc = -1
        while (heap.size > 0) {
            val f = heap.minKey()
            val a = heap.pop()
            if (f >= best) break
            val g = sDist[a]
            if (f > g + h(a) + 1e-9) continue // несвіжий запис
            if (sTgt[a] < INF && g + sTgt[a] < best) { best = g + sTgt[a]; bestArc = a }
            for (e in firstOut[a] until firstOut[a + 1]) {
                val b = outHead[e]
                val w = m.trans[e] + m.arcCost[b]
                if (w >= INF) continue
                init(b)
                val nd = g + w
                if (nd < sDist[b]) {
                    sDist[b] = nd; sPred[b] = a
                    heap.push(nd + h(b), b)
                }
            }
        }
        if (bestArc < 0) {
            return if (pr.directCost < INF) ArcPath(intArrayOf(pr.directArc), true, pr.directCost) else null
        }
        val chain = IntArrayList()
        var a = bestArc
        while (a >= 0) { chain.add(a); a = sPred[a] }
        val out = IntArrayList(chain.size + 1)
        for (i in chain.size - 1 downTo 0) out.add(chain[i])
        out.add(sTgtArc[bestArc])
        return ArcPath(out.toArray(), direct = false, cost = best)
    }

    /** Двійкова купа (ключ double, значення int) без упаковки. */
    private class MinHeap {
        private var k = DoubleArray(64)
        private var v = IntArray(64)
        var size = 0
            private set

        fun push(key: Double, value: Int) {
            if (size == k.size) { k = k.copyOf(size * 2); v = v.copyOf(size * 2) }
            var i = size++
            while (i > 0) {
                val p = (i - 1) shr 1
                if (k[p] <= key) break
                k[i] = k[p]; v[i] = v[p]; i = p
            }
            k[i] = key; v[i] = value
        }

        fun minKey(): Double = k[0]

        fun pop(): Int {
            val top = v[0]
            val lk = k[--size]; val lv = v[size]
            var i = 0
            while (true) {
                var c = 2 * i + 1
                if (c >= size) break
                if (c + 1 < size && k[c + 1] < k[c]) c++
                if (k[c] >= lk) break
                k[i] = k[c]; v[i] = v[c]; i = c
            }
            if (size > 0) { k[i] = lk; v[i] = lv }
            return top
        }
    }

    internal class IntArrayList(cap: Int = 16) {
        private var a = IntArray(cap)
        var size = 0
            private set

        fun add(x: Int) {
            if (size == a.size) a = a.copyOf(a.size * 2)
            a[size++] = x
        }

        fun pop(): Int = a[--size]
        operator fun get(i: Int): Int = a[i]
        fun toArray(): IntArray = a.copyOf(size)
    }

    companion object {
        const val INF = Double.MAX_VALUE / 4

        private val KINDS = RoadGraph.TurnKind.values()

        fun build(graph: RoadGraph): TurnGraph {
            val t0 = System.nanoTime()
            val w = graph.wayCount
            // 1. Ділянки: дорога ріжеться в кожному вузлі графа і на кінцях.
            val wayFirstPiece = IntArray(w + 1)
            val pw = IntArrayList(); val pa = IntArrayList(); val pb = IntArrayList()
            for (wi in 0 until w) {
                wayFirstPiece[wi] = pw.size
                val nv = graph.vertexCount(wi)
                var last = 0
                for (vi in 1 until nv) {
                    val split = vi == nv - 1 || (graph.slotsAt(graph.nodeKeyAt(wi, vi))?.size ?: 0) >= 2
                    if (split) { pw.add(wi); pa.add(last); pb.add(vi); last = vi }
                }
            }
            wayFirstPiece[w] = pw.size
            val pieceWay = pw.toArray(); val pieceA = pa.toArray(); val pieceB = pb.toArray()
            val arcs = pieceWay.size * 2

            fun pieceStartingAt(wi: Int, v: Int): Int {
                var lo = wayFirstPiece[wi]; var hi = wayFirstPiece[wi + 1] - 1
                while (lo <= hi) {
                    val mid = (lo + hi) ushr 1
                    val x = pieceA[mid]
                    if (x < v) lo = mid + 1 else if (x > v) hi = mid - 1 else return mid
                }
                return -1
            }
            fun pieceEndingAt(wi: Int, v: Int): Int {
                var lo = wayFirstPiece[wi]; var hi = wayFirstPiece[wi + 1] - 1
                while (lo <= hi) {
                    val mid = (lo + hi) ushr 1
                    val x = pieceB[mid]
                    if (x < v) lo = mid + 1 else if (x > v) hi = mid - 1 else return mid
                }
                return -1
            }

            // Напрямок, яким дуга ВХОДИТЬ у свій кінець: від найближчої вершини,
            // що далі за піввміру [RoadGraph.MIN_DIR_M2] (як відмотує A*).
            fun dirIn(arc: Int): DoubleArray {
                val p = arc shr 1
                val wi = pieceWay[p]
                val fwd = arc and 1 == 0
                val end = if (fwd) pieceB[p] else pieceA[p]
                val step = if (fwd) -1 else 1
                val stop = if (fwd) pieceA[p] else pieceB[p]
                var k = end + step
                var de = 0.0; var dn = 0.0
                while (true) {
                    de = graph.planeE(wi, end) - graph.planeE(wi, k)
                    dn = graph.planeN(wi, end) - graph.planeN(wi, k)
                    if (de * de + dn * dn > RoadGraph.MIN_DIR_M2 || k == stop) break
                    k += step
                }
                return doubleArrayOf(de, dn)
            }
            fun dirOut(arc: Int): DoubleArray {
                val p = arc shr 1
                val wi = pieceWay[p]
                val fwd = arc and 1 == 0
                val a = if (fwd) pieceA[p] else pieceB[p]
                val b = if (fwd) a + 1 else a - 1
                return doubleArrayOf(
                    graph.planeE(wi, b) - graph.planeE(wi, a),
                    graph.planeN(wi, b) - graph.planeN(wi, a),
                )
            }

            // 2. Переходи — лише у вузлах графа (≥ 2 слоти).
            val tTail = IntArrayList(); val tHead = IntArrayList()
            val tKind = ArrayList<Byte>(); val tHop = ArrayList<Double>()
            val done = HashSet<Long>()
            for (wi in 0 until w) {
                val nv = graph.vertexCount(wi)
                for (vi in 0 until nv) {
                    val key = graph.nodeKeyAt(wi, vi)
                    val slots = graph.slotsAt(key) ?: continue
                    if (slots.size < 2 || !done.add(key)) continue
                    val ms = slots.size
                    val sw = IntArray(ms) { (slots[it] shr 24).toInt() }
                    val sv = IntArray(ms) { (slots[it] and 0xFFFFFF).toInt() }
                    // Замикання стрибків: A* стрибає між РІЗНИМИ дорогами вузла,
                    // скільки завгодно разів підряд, платячи сходження кожного.
                    val hop = Array(ms) { DoubleArray(ms) { INF } }
                    for (i in 0 until ms) {
                        hop[i][i] = 0.0
                        for (j in 0 until ms) if (sw[i] != sw[j]) {
                            hop[i][j] = RoadGraph.hierarchyDropPenalty(graph.wayAt(sw[i]), graph.wayAt(sw[j]))
                        }
                    }
                    for (k in 0 until ms) for (i in 0 until ms) for (j in 0 until ms) {
                        val c = hop[i][k] + hop[k][j]
                        if (c < hop[i][j]) hop[i][j] = c
                    }
                    val inArcs = Array(ms) { i ->
                        val l = IntArrayList(2)
                        pieceEndingAt(sw[i], sv[i]).let { if (it >= 0) l.add(2 * it) }
                        pieceStartingAt(sw[i], sv[i]).let { if (it >= 0) l.add(2 * it + 1) }
                        l.toArray()
                    }
                    val outArcs = Array(ms) { i ->
                        val l = IntArrayList(2)
                        pieceStartingAt(sw[i], sv[i]).let { if (it >= 0) l.add(2 * it) }
                        pieceEndingAt(sw[i], sv[i]).let { if (it >= 0) l.add(2 * it + 1) }
                        l.toArray()
                    }
                    for (i in 0 until ms) for (e in inArcs[i]) {
                        val din = dirIn(e)
                        for (j in 0 until ms) {
                            if (hop[i][j] >= INF) continue
                            for (f in outArcs[j]) {
                                if (f == e) continue
                                val dout = dirOut(f)
                                val kind = RoadGraph.turnKind(din[0], din[1], dout[0], dout[1])
                                tTail.add(e); tHead.add(f)
                                tKind.add(kind.ordinal.toByte()); tHop.add(hop[i][j])
                            }
                        }
                    }
                }
            }
            val tn = tTail.size
            val tailArr = tTail.toArray(); val headArr = tHead.toArray()
            // CSR за хвостом.
            val firstOut = IntArray(arcs + 1)
            for (i in 0 until tn) firstOut[tailArr[i] + 1]++
            for (a in 0 until arcs) firstOut[a + 1] += firstOut[a]
            val fill = firstOut.copyOf()
            val outTail = IntArray(tn); val outHead = IntArray(tn)
            val outKind = ByteArray(tn); val outHop = DoubleArray(tn)
            for (i in 0 until tn) {
                val pos = fill[tailArr[i]]++
                outTail[pos] = tailArr[i]; outHead[pos] = headArr[i]
                outKind[pos] = tKind[i]; outHop[pos] = tHop[i]
            }
            val firstIn = IntArray(arcs + 1)
            for (i in 0 until tn) firstIn[outHead[i] + 1]++
            for (a in 0 until arcs) firstIn[a + 1] += firstIn[a]
            val fillIn = firstIn.copyOf()
            val inEdge = IntArray(tn)
            for (i in 0 until tn) inEdge[fillIn[outHead[i]]++] = i
            val t1 = System.nanoTime()
            return TurnGraph(
                graph, wayFirstPiece, pieceWay, pieceA, pieceB,
                firstOut, outTail, outHead, outKind, outHop, firstIn, inEdge,
                (t1 - t0) / 1_000_000,
            )
        }
    }
}
