package local.phantom.companion.core.sensor.nav

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Random

/**
 * ПРИЛАД, не ворота: без `-Dphantom.routeMeasure` пропускається.
 *
 *   python3 scripts/extract_trip_corridors.py <kyiv-v5.db> <dir>
 *   bash scripts/gate.sh :core-sensor:testDebugUnitTest --tests '*TurnGraphMeasure*' \
 *       -Dphantom.routeMeasure=1 -Dphantom.tripsDir=<dir> [-Dphantom.cityTsv=<весь пакет.tsv>]
 *
 * Розбіжність з Дейкстрою валить прилад — число без правильності не вимір.
 */
class TurnGraphMeasureTest {

    private val mode = RoadProfile.TravelMode.CAR

    private fun wayFactor(w: RoadWay) =
        if (RoadProfile.blocked(w.tags, mode)) LocalRouter.BLOCKED_PENALTY else RoadProfile.costFactor(w.tags, mode)

    private fun factors(g: RoadGraph) = DoubleArray(g.wayCount) { wayFactor(g.wayAt(it)).coerceAtLeast(1.0) }

    private fun snap(g: RoadGraph, lat: Double, lon: Double) =
        g.near(lat, lon, LocalRouter.SNAP_RADIUS_M, LocalRouter.SNAP_CANDIDATES)
            .firstOrNull { !RoadProfile.blocked(it.way.tags, mode) }

    private fun pct(xs: List<Double>, p: Double): Double =
        if (xs.isEmpty()) Double.NaN else xs.sorted()[((xs.size - 1) * p).toInt()]

    private fun mb(b: Long) = "%.1f".format(b / 1048576.0)

    private fun heapUsed(): Long {
        val rt = Runtime.getRuntime()
        repeat(3) { System.gc(); Thread.sleep(50) }
        return rt.totalMemory() - rt.freeMemory()
    }

    private fun readTsv(f: File): Pair<SyntheticCity.Trip, List<RoadWay>> {
        val lines = f.readLines()
        val head = lines.first().removePrefix("#").split('\t')
        val ways = ArrayList<RoadWay>(lines.size)
        for (l in lines.drop(1)) {
            val c = l.split('\t')
            if (c.size < 5) continue
            val pts = c[3].split(';').mapNotNull {
                val p = it.split(','); if (p.size == 2) p[0].toDouble() to p[1].toDouble() else null
            }
            if (pts.size < 2) continue
            val tags = c[4].split('|').mapNotNull {
                val kv = it.split('='); if (kv.size == 2) kv[0] to kv[1] else null
            }.toMap()
            ways += RoadWay(c[0].toLong(), pts, c[1].ifBlank { null }, c[2] == "1", tags)
        }
        return SyntheticCity.Trip(head[0], head[1].toDouble(), head[2].toDouble(), head[3].toDouble(), head[4].toDouble()) to ways
    }

    private fun agrees(got: Double?, want: Double) =
        Math.abs((got ?: TurnGraph.INF) - want) <= 1e-6 * maxOf(1.0, want)

    @Test
    fun `граф поворотів на шести поїздках і на цілому місті`() {
        assumeTrue("немає -Dphantom.routeMeasure", System.getProperty("phantom.routeMeasure") != null)
        val dir = System.getProperty("phantom.tripsDir")
        val trips = if (dir != null) {
            File(dir).listFiles { f: File -> f.name.endsWith(".tsv") }!!.sortedBy { it.name }.map { readTsv(it) }
        } else {
            SyntheticCity.TRIPS.map { it to SyntheticCity.corridor(it) }
        }
        println("ДЖЕРЕЛО: ${dir ?: "SyntheticCity"}")
        println("ПОЇЗДКА\tДОРІГ\tДУГ\tПЕРЕХОДІВ\tA*60k\tA*200k\tмс_A*200k\tграф_мс\tпіч_мс\tМБ_графа_поворотів\tпошук_мс\tціна\tДейкстра\tкм_вхід\tвхід_1_мс\tвхід_2_мс")
        var mism = 0
        for ((t, ways) in trips) {
            val g0 = System.nanoTime()
            val g = RoadGraph(ways)
            val graphMs = (System.nanoTime() - g0) / 1_000_000
            val from = snap(g, t.fromLat, t.fromLon)!!
            val to = snap(g, t.toLat, t.toLon)!!
            var why60: RoadGraph.Refusal? = null
            g.routeTraced(from, to, true, 60_000, null, ::wayFactor) { why60 = it }
            var why200: RoadGraph.Refusal? = null
            val a0 = System.nanoTime()
            g.routeTraced(from, to, true, RoadGraph.MAX_ASTAR_NODES, null, ::wayFactor) { why200 = it }
            val astarMs = (System.nanoTime() - a0) / 1_000_000
            val tg = g.turnGraph()
            val m = tg.metric(true, factors(g), null)
            val pr = tg.problem(from, to, m)
            val s0 = System.nanoTime()
            val path = tg.search(pr, m, to)
            val searchMs = (System.nanoTime() - s0) / 1e6
            val want = DijkstraOracle.cost(tg, pr, m)
            if (!agrees(path?.cost, want)) mism++

            val fresh = RoadGraph(ways)
            val r0 = System.nanoTime()
            val first = LocalRouter().route(fresh, t.fromLat, t.fromLon, t.toLat, t.toLon, mode)
            val r1 = System.nanoTime()
            LocalRouter().route(fresh, t.fromLat, t.fromLon, t.toLat, t.toLon, mode)
            val r2 = System.nanoTime()
            println(
                "${t.name}\t${g.wayCount}\t${tg.arcCount}\t${tg.transitionCount}\t${why60 ?: "OK"}\t${why200 ?: "OK"}\t$astarMs\t" +
                    "$graphMs\t${tg.buildMs}\t${mb(tg.topologyBytes + tg.searchBytes)}\t${"%.1f".format(searchMs)}\t" +
                    "${"%.1f".format(path?.cost ?: -1.0)}\t${"%.1f".format(want)}\t" +
                    "${first?.let { "%.2f".format(it.distanceMeters / 1000) } ?: "НЕМАЄ"}\t${(r1 - r0) / 1_000_000}\t${(r2 - r1) / 1_000_000}",
            )
        }
        assertEquals("пошук ≠ Дейкстра на поїздках", 0, mism)

        val cityTsv = System.getProperty("phantom.cityTsv") ?: return
        val all = readTsv(File(cityTsv)).second
        val latLo = all.minOf { w -> w.points.minOf { it.first } }
        val latSpan = all.maxOf { w -> w.points.maxOf { it.first } } - latLo
        val lonLo = all.minOf { w -> w.points.minOf { it.second } }
        val lonSpan = all.maxOf { w -> w.points.maxOf { it.second } } - lonLo
        val h0 = heapUsed()
        val g0 = System.nanoTime()
        val g = RoadGraph(all)
        val graphMs = (System.nanoTime() - g0) / 1_000_000
        val h1 = heapUsed()
        val tg = g.turnGraph()
        val h2 = heapUsed()
        val wf = factors(g)
        val m0 = System.nanoTime()
        val m = tg.metric(true, wf, null)
        val metricMs = (System.nanoTime() - m0) / 1e6
        println(
            "МІСТО: доріг=${g.wayCount} дуг=${tg.arcCount} переходів=${tg.transitionCount} граф_мс=$graphMs піч_мс=${tg.buildMs} " +
                "купа_графа_МБ=${mb(h1 - h0)} купа_графа_поворотів_МБ=${mb(h2 - h1)} " +
                "за_формулою_МБ=${mb(tg.topologyBytes)}+${mb(tg.searchBytes)} ваги_мс=${"%.0f".format(metricMs)}",
        )
        val rnd = Random(20260917L)
        val qs = ArrayList<Double>()
        val kms = ArrayList<Double>()
        var cityMism = 0
        var astarFail = 0
        var astarTried = 0
        repeat(400) { k ->
            val a = snap(g, latLo + rnd.nextDouble() * latSpan, lonLo + rnd.nextDouble() * lonSpan) ?: return@repeat
            val b = snap(g, latLo + rnd.nextDouble() * latSpan, lonLo + rnd.nextDouble() * lonSpan) ?: return@repeat
            val pr = tg.problem(a, b, m)
            val t0 = System.nanoTime()
            val p = tg.search(pr, m, b)
            qs += (System.nanoTime() - t0) / 1e6
            if (p != null) kms += haversineM(a.lat, a.lon, b.lat, b.lon) / 1000
            if (k < 40) {
                if (!agrees(p?.cost, DijkstraOracle.cost(tg, pr, m))) cityMism++
                astarTried++
                var why: RoadGraph.Refusal? = null
                g.routeTraced(a, b, true, RoadGraph.MAX_ASTAR_NODES, null, ::wayFactor) { why = it }
                if (why == RoadGraph.Refusal.EXHAUSTED) astarFail++
            }
        }
        println(
            "МІСТО_ЗАПИТ: пар=${qs.size} досяжних=${kms.size} пряма_км_p50=${"%.1f".format(pct(kms, 0.5))} " +
                "p50_мс=${"%.1f".format(pct(qs, 0.5))} p95_мс=${"%.1f".format(pct(qs, 0.95))} max_мс=${"%.1f".format(qs.maxOrNull() ?: 0.0)} " +
                "розбіжностей_з_Дейкстрою=$cityMism/40 A*200k_здався=$astarFail/$astarTried",
        )
        val jam = GnssIntegrityMap()
        val rr = Random(7)
        repeat(400) {
            val lat = latLo + rr.nextDouble() * latSpan
            val lon = lonLo + rr.nextDouble() * lonSpan
            repeat(12) { jam.observe(lat, lon, trustRisk = 1f) }
        }
        val f0 = System.nanoTime()
        tg.metric(true, wf) { lat, lon -> jam.costFactorAt(lat, lon, LocalRouter.DEFAULT_AVOIDANCE) }
        println("ПОЛЕ_ГЛУШІННЯ: ваги_з_полем_мс=${"%.0f".format((System.nanoTime() - f0) / 1e6)}")
        assertEquals("пошук ≠ Дейкстра на місті", 0, cityMism)
    }
}
