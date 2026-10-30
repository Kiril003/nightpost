package local.phantom.companion.core.sensor.nav

import java.util.Random

/**
 * ЗАМІННИК київського пакета, і названий замінником.
 *
 * Справжній `kyiv-v5` (165 250 доріг) лежить на ноутбуці власника
 * (`~/phantom_ai/road-mesh-bake/…`) і в репозиторій не входить; дорожнього
 * графа України в `core-data/src/main/assets/country/` теж немає — там
 * назви вулиць і точки, не геометрія. Тому шість поїздок тут їдуть по
 * СИНТЕТИЧНОМУ місту на тій самій рамці 50,30–50,60 / 30,30–30,75 і з тими
 * самими координатами кінців, що в `scripts/extract_trip_corridors.py`.
 *
 * Що в ньому подібне до справжнього міста, свідомо:
 *  - густина: ~110 тис. доріг і ~0,6 млн вершин на рамці (у kyiv-v5 — 147 тис.
 *    доріг у тій самій рамці); вулиця ріжеться на дороги по 3–6 кварталів,
 *    кожен квартал — три відрізки з проміжними вершинами, як форма в OSM;
 *  - класи: магістралі з `maxspeed`, другорядні, житлові; частина житлових —
 *    односторонні; тупикові підʼїзди (`service=driveway`) і діагональні
 *    тротуари (`footway`), яких у справжньому OSM більшість доріг;
 *  - Дніпро: смуга без вулиць, через яку ведуть лише мости — справжній
 *    природний роздільник, як у Києві;
 *  - 8 % кварталів без ребра — щоб мережа не була ідеальною ґраткою.
 *
 * Чого в ньому НЕМА і що з цього випливає: справжньої ієрархії вулиць,
 * розвʼязок, дворів складної форми. Числа замірів на ньому — порядок
 * величини, а не київські числа; прилад [TurnGraphMeasureTest] уміє
 * міряти й справжні коридори, коли вони є на диску.
 */
internal object SyntheticCity {
    const val MIN_LAT = 50.30
    const val MAX_LAT = 50.60
    const val MIN_LON = 30.30
    const val MAX_LON = 30.75
    private const val D_LAT = 0.00117 // ≈130 м
    private const val D_LON = 0.00183 // ≈130 м на 50,45°
    private const val RIVER_W = 30.535
    private const val RIVER_E = 30.565

    /** Шість поїздок — СЛОВО В СЛОВО з `scripts/extract_trip_corridors.py`. */
    val TRIPS = listOf(
        Trip("Святошин->Лівобережна", 50.4578, 30.3560, 50.4515, 30.5990),
        Trip("Голосіїв->Виноградар", 50.3930, 30.4960, 50.5090, 30.3840),
        Trip("дім власника->Майдан", 50.504668, 30.410170, 50.4501, 30.5234),
        Trip("Позняки->Майдан", 50.3960, 30.6300, 50.4501, 30.5234),
        Trip("Оболонь->Либідська", 50.5110, 30.4980, 50.4130, 30.5230),
        Trip("Теремки->Лукʼянівка", 50.3600, 30.4560, 50.4720, 30.4720),
    )

    data class Trip(val name: String, val fromLat: Double, val fromLon: Double, val toLat: Double, val toLon: Double)

    private val cache = HashMap<Long, List<RoadWay>>()

    @Synchronized
    fun ways(seed: Long = 17_09_2026L): List<RoadWay> = cache.getOrPut(seed) { build(seed) }

    /** Коридор так, як його читає `AppContainer.offlineMeshRoute`: рамка кінців + 0,02°. */
    fun corridor(t: Trip, seed: Long = 17_09_2026L, pad: Double = 0.02): List<RoadWay> {
        val s = minOf(t.fromLat, t.toLat) - pad
        val n = maxOf(t.fromLat, t.toLat) + pad
        val w = minOf(t.fromLon, t.toLon) - pad
        val e = maxOf(t.fromLon, t.toLon) + pad
        return ways(seed).filter { way -> way.points.any { it.first in s..n && it.second in w..e } }
    }

    private fun build(seed: Long): List<RoadWay> {
        val rnd = Random(seed)
        val rows = ((MAX_LAT - MIN_LAT) / D_LAT).toInt() + 1
        val cols = ((MAX_LON - MIN_LON) / D_LON).toInt() + 1
        val lat = Array(rows) { DoubleArray(cols) }
        val lon = Array(rows) { DoubleArray(cols) }
        for (i in 0 until rows) for (j in 0 until cols) {
            lat[i][j] = r5(MIN_LAT + i * D_LAT + (rnd.nextDouble() - 0.5) * D_LAT * 0.3)
            lon[i][j] = r5(MIN_LON + j * D_LON + (rnd.nextDouble() - 0.5) * D_LON * 0.3)
        }
        fun river(j: Int) = MIN_LON + j * D_LON in RIVER_W..RIVER_E
        fun cls(k: Int) = when {
            k % 16 == 0 -> "primary"
            k % 8 == 4 -> "secondary"
            else -> "residential"
        }
        fun bridgeRow(i: Int) = i % 32 == 16
        // Ребро існує? Магістралі й другорядні — завжди (крім річки).
        val hEdge = Array(rows) { i ->
            BooleanArray(cols - 1) { j ->
                val inRiver = river(j) || river(j + 1)
                when {
                    inRiver -> bridgeRow(i)
                    cls(i) != "residential" -> true
                    else -> rnd.nextDouble() >= 0.08
                }
            }
        }
        val vEdge = Array(rows - 1) { i ->
            BooleanArray(cols) { j ->
                when {
                    river(j) -> false
                    cls(j) != "residential" -> true
                    else -> rnd.nextDouble() >= 0.08
                }
            }
        }
        val out = ArrayList<RoadWay>(120_000)
        var id = 1L
        // Проміжні вершини ребра — форма дороги, спільна для вулиці й підʼїзду.
        fun shape(aLat: Double, aLon: Double, bLat: Double, bLon: Double, t: Double, wob: Double) =
            r5(aLat + (bLat - aLat) * t + wob * 0.00004) to r5(aLon + (bLon - aLon) * t - wob * 0.00006)

        fun emitLine(
            n: Int,
            present: (Int) -> Boolean,
            node: (Int) -> Pair<Double, Double>,
            clazz: String,
            name: String,
            oneway: Boolean,
            reverse: Boolean,
        ) {
            var k = 0
            while (k < n) {
                if (!present(k)) { k++; continue }
                val len = 3 + rnd.nextInt(4)
                val pts = ArrayList<Pair<Double, Double>>()
                pts.add(node(k))
                var m = 0
                while (k < n && present(k) && m < len) {
                    val a = node(k); val b = node(k + 1)
                    val wob = ((k * 7 + name.length) % 3 - 1).toDouble()
                    pts.add(shape(a.first, a.second, b.first, b.second, 1.0 / 3, wob))
                    pts.add(shape(a.first, a.second, b.first, b.second, 2.0 / 3, -wob))
                    pts.add(b)
                    k++; m++
                }
                val tags = HashMap<String, String>()
                tags["highway"] = clazz
                when (clazz) {
                    "primary" -> tags["maxspeed"] = "60"
                    "secondary" -> tags["maxspeed"] = "50"
                }
                out += RoadWay(id++, if (reverse) pts.reversed() else pts, name, oneway, tags)
            }
        }
        for (i in 0 until rows) {
            val c = cls(i)
            val ow = c == "residential" && i % 5 == 2
            emitLine(cols - 1, { hEdge[i][it] }, { lat[i][it] to lon[i][it] }, c, "вул. Р-$i", ow, ow && (i / 5) % 2 == 1)
        }
        for (j in 0 until cols) {
            val c = cls(j)
            val ow = c == "residential" && j % 5 == 2
            emitLine(rows - 1, { vEdge[it][j] }, { lat[it][j] to lon[it][j] }, c, "вул. К-$j", ow, ow && (j / 5) % 2 == 1)
        }
        // Підʼїзди: з першої третини південного ребра кварталу на північ, тупиком.
        // Тротуари: діагональ кварталу від кута до кута.
        for (i in 0 until rows - 1) for (j in 0 until cols - 1) {
            if (hEdge[i][j] && !river(j) && !river(j + 1) && rnd.nextDouble() < 0.7) {
                val wob = ((j * 7 + "вул. Р-$i".length) % 3 - 1).toDouble()
                val s = shape(lat[i][j], lon[i][j], lat[i][j + 1], lon[i][j + 1], 1.0 / 3, wob)
                out += RoadWay(
                    id++,
                    listOf(s, r5(s.first + 0.00036) to s.second, r5(s.first + 0.00072) to r5(s.second + 0.0002)),
                    null, false, mapOf("highway" to "service", "service" to "driveway"),
                )
            }
            if ((i + j) % 3 == 0 && !river(j) && !river(j + 1)) {
                val a = lat[i][j] to lon[i][j]
                val b = lat[i + 1][j + 1] to lon[i + 1][j + 1]
                out += RoadWay(
                    id++,
                    listOf(a, shape(a.first, a.second, b.first, b.second, 1.0 / 3, 0.0),
                        shape(a.first, a.second, b.first, b.second, 2.0 / 3, 0.0), b),
                    null, false, mapOf("highway" to "footway"),
                )
            }
        }
        return out
    }

    private fun r5(x: Double) = Math.round(x * 1e6) / 1e6
}

/**
 * ОРАКУЛ: звичайна Дейкстра над тими самими дугами й тими самими вагами
 * ([TurnGraph.Metric]), без евристики й без стелі. Якщо пошук
 * відповідає інакше — бреше пошук.
 */
internal object DijkstraOracle {
    fun cost(idx: TurnGraph, pr: TurnGraph.Problem, m: TurnGraph.Metric): Double {
        val n = idx.arcCount
        val dist = DoubleArray(n) { TurnGraph.INF }
        val pq = java.util.PriorityQueue<DoubleArray>(compareBy { it[0] })
        for (i in pr.sourceArcs.indices) {
            val a = pr.sourceArcs[i]
            if (pr.sourceCost[i] < dist[a]) {
                dist[a] = pr.sourceCost[i]
                pq.add(doubleArrayOf(dist[a], a.toDouble()))
            }
        }
        while (pq.isNotEmpty()) {
            val top = pq.poll()
            val a = top[1].toInt()
            if (top[0] > dist[a]) continue
            for (e in idx.firstOut[a] until idx.firstOut[a + 1]) {
                val f = idx.outHead[e]
                val w = m.trans[e] + m.arcCost[f]
                if (w >= TurnGraph.INF) continue
                val nd = dist[a] + w
                if (nd < dist[f]) {
                    dist[f] = nd
                    pq.add(doubleArrayOf(nd, f.toDouble()))
                }
            }
        }
        var best = pr.directCost
        for (i in pr.targetArcs.indices) {
            val tq = pr.targetArcs[i]
            for (k in idx.firstIn[tq] until idx.firstIn[tq + 1]) {
                val e = idx.inEdge[k]
                val from = idx.outTail[e]
                if (dist[from] >= TurnGraph.INF) continue
                val c = dist[from] + m.trans[e] + pr.targetCost[i]
                if (c < best) best = c
            }
        }
        return best
    }

    /** Ціна самого повернутого шляху дугами — перевіряє розпакування, не лише число. */
    fun pathCost(idx: TurnGraph, pr: TurnGraph.Problem, m: TurnGraph.Metric, path: TurnGraph.ArcPath): Double {
        if (path.direct) return pr.directCost
        val arcs = path.arcs
        val si = pr.sourceArcs.indexOf(arcs[0])
        check(si >= 0) { "шлях почався не з джерела" }
        var c = pr.sourceCost[si]
        for (k in 1 until arcs.size) {
            val a = arcs[k - 1]; val b = arcs[k]
            var tr = TurnGraph.INF
            for (e in idx.firstOut[a] until idx.firstOut[a + 1]) {
                if (idx.outHead[e] == b && m.trans[e] < tr) tr = m.trans[e]
            }
            check(tr < TurnGraph.INF) { "у розпакованому шляху немає переходу $a→$b" }
            c += tr
            c += if (k == arcs.size - 1) {
                pr.targetCost[pr.targetArcs.indexOf(b).also { check(it >= 0) { "шлях скінчився не на цілі" } }]
            } else m.arcCost[b]
        }
        return c
    }
}
