package local.phantom.companion.core.sensor.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ШІСТЬ ПОЇЗДОК доходять — через справжній вхід [LocalRouter.route], яким
 * ходить `AppContainer.offlineMeshRoute`, і з вартістю, що дорівнює Дейкстрі.
 *
 * Кінці — СЛОВО В СЛОВО з `scripts/extract_trip_corridors.py` (ті самі, що
 * заміряно 16–17.09 на `kyiv-v5`); коридор — рамка кінців + 0,02°, як у
 * застосунку. Граф — [SyntheticCity], замінник київського пакета: справжній
 * лежить поза репозиторієм, і це назване, а не приховане.
 *
 * «ДО» доводиться тут же, а не на слово: старий A* зі стелею 60 000 (та, що
 * стояла 17.09) на тих самих коридорах здається — і каже про це
 * [RoadGraph.Refusal.EXHAUSTED]. «ПІСЛЯ» — жодна з шести не має права
 * відмовити, бо в графа поворотів стелі немає.
 */
class SixTripsReachTest {

    private val mode = RoadProfile.TravelMode.CAR

    /** Стеля, з якої 17.09 знято чотири з шести київських поїздок. */
    private val ceiling1709 = 60_000

    private fun wayFactor(w: RoadWay) =
        if (RoadProfile.blocked(w.tags, mode)) LocalRouter.BLOCKED_PENALTY else RoadProfile.costFactor(w.tags, mode)

    private fun snap(g: RoadGraph, lat: Double, lon: Double) =
        g.near(lat, lon, LocalRouter.SNAP_RADIUS_M, LocalRouter.SNAP_CANDIDATES)
            .firstOrNull { !RoadProfile.blocked(it.way.tags, mode) }!!

    @Test
    fun `шість поїздок доходять, і кожна коштує рівно стільки, скільки Дейкстра`() {
        var cutByOldCeiling = 0
        for (t in SyntheticCity.TRIPS) {
            val g = RoadGraph(SyntheticCity.corridor(t))
            val from = snap(g, t.fromLat, t.fromLon)
            val to = snap(g, t.toLat, t.toLon)

            // ДО: A* зі стелею 17.09.
            var whyOld: RoadGraph.Refusal? = null
            val old = g.routeTraced(from, to, true, ceiling1709, null, ::wayFactor) { whyOld = it }
            if (old == null) {
                assertEquals("${t.name}: старий A* мусить казати «здався», а не «доріг нема»",
                    RoadGraph.Refusal.EXHAUSTED, whyOld)
                cutByOldCeiling++
            }

            // ПІСЛЯ: справжній вхід застосунку.
            var why: LocalRouter.Refusal? = null
            val r = LocalRouter().route(g, t.fromLat, t.fromLon, t.toLat, t.toLon, mode, onRefusal = { why = it })
            assertNotNull("${t.name}: маршруту немає ($why)", r)
            assertNull("${t.name}: дійшовши, роутер не має права називати причину", why)
            assertTrue("${t.name}: маршрут пішов графом поворотів", g.hasTurnGraph)

            // ОРАКУЛ на тій самій поїздці.
            val tg = g.turnGraph()
            val wf = DoubleArray(g.wayCount) { wayFactor(g.wayAt(it)).coerceAtLeast(1.0) }
            val m = tg.metric(true, wf, null)
            val pr = tg.problem(from, to, m)
            val want = DijkstraOracle.cost(tg, pr, m)
            val got = tg.search(pr, m, to)!!
            assertEquals("${t.name}: вартість ≠ Дейкстра", want, got.cost, 1e-6 * want)
            println("${t.name}: доріг=${g.wayCount} A*@60k=${whyOld ?: "OK"} пошук=${"%.1f".format(got.cost)} " +
                "Дейкстра=${"%.1f".format(want)} км=${"%.2f".format(r!!.distanceMeters / 1000)}")
        }
        // Число «до» заміряно на цьому замінникові й
        // закріплене: якщо генератор чи A* зміняться так, що стара стеля
        // перестане обривати поїздки, цей тест перестане доводити «до».
        assertEquals("скільки поїздок обривала стеля 17.09 на замінникові", EXPECTED_CUT_1709, cutByOldCeiling)
    }

    private companion object {
        /** Заміряно 24.09 на [SyntheticCity]: 1 з 6 (Голосіїв → Виноградар). На kyiv-v5 17.09 — 4 з 6. */
        const val EXPECTED_CUT_1709 = 1
    }
}
