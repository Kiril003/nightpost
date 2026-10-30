package local.phantom.companion.core.sensor.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * СТЕЛІ НЕМАЄ — і це доводиться на поїздці, де вона є.
 *
 * Стеля A* [RoadGraph.MAX_ASTAR_NODES] = 200 000 стоїть з 17.09 і вже тоді
 * була названа межею, а не властивістю міста: Київ → Біла Церква вимагає
 * 600 000 і чесно впирається. Тут — поїздка через усю рамку міста (Святошин-
 * ський край → Дарниця, по діагоналі через міст), на якій чинний A* здається
 * ([RoadGraph.Refusal.EXHAUSTED]), а справжній вхід застосунку
 * [LocalRouter.route] мусить вести і вести ОПТИМАЛЬНО — вартість дорівнює
 * Дейкстрі на тих самих вагах.
 *
 * Без графа поворотів цей тест ЧЕРВОНИЙ: [LocalRouter] тоді йшов у той
 * самий A* і повертав `null` з [LocalRouter.Refusal.EXHAUSTED].
 */
class NoSearchCeilingTest {

    private val mode = RoadProfile.TravelMode.CAR

    private fun wayFactor(w: RoadWay) =
        if (RoadProfile.blocked(w.tags, mode)) LocalRouter.BLOCKED_PENALTY else RoadProfile.costFactor(w.tags, mode)

    @Test
    fun `поїздка, на якій A* здається, доходить через вхід застосунку й оптимально`() {
        val fromLat = 50.312; val fromLon = 30.318
        val toLat = 50.588; val toLon = 30.735
        val g = RoadGraph(SyntheticCity.ways())
        val snap = { lat: Double, lon: Double ->
            g.near(lat, lon, LocalRouter.SNAP_RADIUS_M, LocalRouter.SNAP_CANDIDATES)
                .first { !RoadProfile.blocked(it.way.tags, mode) }
        }
        val from = snap(fromLat, fromLon)
        val to = snap(toLat, toLon)

        // ДО: чинний A* на чинній стелі.
        var whyA: RoadGraph.Refusal? = null
        val astar = g.routeTraced(from, to, true, RoadGraph.MAX_ASTAR_NODES, null, ::wayFactor) { whyA = it }
        assertNull("ця поїздка мусить бути за стелею A* — інакше тест нічого не доводить", astar)
        assertEquals(RoadGraph.Refusal.EXHAUSTED, whyA)

        // ПІСЛЯ: вхід застосунку.
        var why: LocalRouter.Refusal? = null
        val r = LocalRouter().route(g, fromLat, fromLon, toLat, toLon, mode, onRefusal = { why = it })
        assertNotNull("маршруту через місто немає: $why", r)
        assertNull("дійшовши, роутер не має права називати причину", why)

        val tg = g.turnGraph()
        val wf = DoubleArray(g.wayCount) { wayFactor(g.wayAt(it)).coerceAtLeast(1.0) }
        val m = tg.metric(true, wf, null)
        val pr = tg.problem(from, to, m)
        val want = DijkstraOracle.cost(tg, pr, m)
        val got = tg.search(pr, m, to)!!
        assertEquals("через місто — не оптимально", want, got.cost, 1e-6 * want)
        println("ЧЕРЕЗ МІСТО: ${"%.2f".format(r!!.distanceMeters / 1000)} км, ціна ${"%.1f".format(got.cost)} = Дейкстра ${"%.1f".format(want)}")
    }
}
