package local.phantom.companion.core.sensor.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Пошук графом поворотів дорівнює звичайній Дейкстрі на тих самих дугах і вагах:
 * три профілі, чотири стани полів, випадкові пари з фіксованим зерном. Пішки й
 * без полів ціна — це довжина, і тут незалежний суддя — старий A* без стелі.
 */
class TurnGraphOracleTest {

    private val graph: RoadGraph by lazy {
        RoadGraph(
            SyntheticCity.ways().filter { w ->
                w.points.any { it.first in 50.425..50.475 && it.second in 30.505..30.585 }
            },
        )
    }

    private fun factors(mode: RoadProfile.TravelMode) = DoubleArray(graph.wayCount) {
        val t = graph.wayAt(it).tags
        (if (RoadProfile.blocked(t, mode)) LocalRouter.BLOCKED_PENALTY else RoadProfile.costFactor(t, mode))
            .coerceAtLeast(1.0)
    }

    private fun randomPoint(rnd: Random) =
        (50.425 + rnd.nextDouble() * 0.05) to (30.505 + rnd.nextDouble() * 0.08)

    private fun jamming(seed: Long, spots: Int): GnssIntegrityMap {
        val m = GnssIntegrityMap()
        val r = Random(seed)
        repeat(spots) {
            val (lat, lon) = randomPoint(r)
            repeat(10) { m.observe(lat, lon, trustRisk = 1f) }
        }
        return m
    }

    private val hazard: (Double, Double) -> Double = { lat, lon ->
        1.0 + 1.5 * (0.5 + 0.5 * kotlin.math.sin(lat * 900.0) * kotlin.math.cos(lon * 700.0))
    }

    private data class Tally(var pairs: Int = 0, var reachable: Int = 0)

    private fun check(
        mode: RoadProfile.TravelMode,
        field: ((Double, Double) -> Double)?,
        seed: Long,
        pairs: Int,
        tally: Tally,
    ) {
        val tg = graph.turnGraph()
        val m = tg.metric(mode != RoadProfile.TravelMode.FOOT, factors(mode), field)
        val rnd = Random(seed)
        var done = 0
        while (done < pairs) {
            val (aLat, aLon) = randomPoint(rnd)
            val (bLat, bLon) = randomPoint(rnd)
            val a = graph.near(aLat, aLon, LocalRouter.SNAP_RADIUS_M, 1).firstOrNull() ?: continue
            val b = graph.near(bLat, bLon, LocalRouter.SNAP_RADIUS_M, 1).firstOrNull() ?: continue
            done++
            tally.pairs++
            val pr = tg.problem(a, b, m)
            val want = DijkstraOracle.cost(tg, pr, m)
            val got = tg.search(pr, m, b)
            if (want >= TurnGraph.INF) {
                assertTrue("пошук знайшов шлях, якого Дейкстра не бачить ($mode, пара $done)", got == null)
                continue
            }
            tally.reachable++
            assertNotNull("пошук не знайшов шлях, який є ($mode, пара $done)", got)
            val tol = 1e-6 * maxOf(1.0, want)
            assertEquals("пошук ≠ Дейкстра ($mode, пара $done)", want, got!!.cost, tol)
            assertEquals("шлях коштує не те, що заявлено ($mode, пара $done)", got.cost, DijkstraOracle.pathCost(tg, pr, m, got), tol)
        }
    }

    @Test
    fun `пошук дорівнює Дейкстрі на трьох профілях і чотирьох станах полів`() {
        val t = Tally()
        for (mode in RoadProfile.TravelMode.values()) {
            check(mode, null, 1709L, 120, t)
            check(mode, hazard, 1809L, 120, t)
            val jamA = jamming(11, 40)
            check(mode, { lat, lon -> jamA.costFactorAt(lat, lon, LocalRouter.DEFAULT_AVOIDANCE) }, 1909L, 120, t)
            val jamB = jamming(12, 80)
            check(
                mode,
                { lat, lon -> jamB.costFactorAt(lat, lon, LocalRouter.DEFAULT_AVOIDANCE) * hazard(lat, lon) },
                2009L, 120, t,
            )
        }
        println("ОРАКУЛ: пар=${t.pairs} досяжних=${t.reachable} дуг=${graph.turnGraph().arcCount}")
        assertTrue("оракул має бачити переважно досяжні пари", t.reachable > t.pairs * 9 / 10)
    }

    @Test
    fun `пішки без полів довжина дорівнює старому A* без стелі`() {
        val rnd = Random(2409L)
        var compared = 0
        while (compared < 120) {
            val (aLat, aLon) = randomPoint(rnd)
            val (bLat, bLon) = randomPoint(rnd)
            val a = graph.near(aLat, aLon, LocalRouter.SNAP_RADIUS_M, 1).firstOrNull() ?: continue
            val b = graph.near(bLat, bLon, LocalRouter.SNAP_RADIUS_M, 1).firstOrNull() ?: continue
            val old = graph.routeTraced(a, b, maxExpandedNodes = Int.MAX_VALUE) ?: continue
            val neu = graph.routeTurnAware(a, b)
            assertNotNull("A* дійшов, граф поворотів — ні (пара $compared)", neu)
            val lo = polylineM(old.points)
            assertEquals("довжина пішого маршруту розійшлась (пара $compared)", lo, polylineM(neu!!.points), 0.5 + lo * 1e-4)
            compared++
        }
    }

    private fun polylineM(p: List<Pair<Double, Double>>) =
        (1 until p.size).sumOf { haversineM(p[it - 1].first, p[it - 1].second, p[it].first, p[it].second) }
}
