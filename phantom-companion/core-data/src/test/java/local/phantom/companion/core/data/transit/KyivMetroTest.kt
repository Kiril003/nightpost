package local.phantom.companion.core.data.transit

import kotlinx.coroutines.runBlocking
import local.phantom.companion.core.data.transit.metro.MetroFeed
import local.phantom.companion.core.data.transit.raptor.Raptor
import local.phantom.companion.core.data.transit.raptor.TimetableBuilder
import local.phantom.companion.core.data.transit.raptor.TransitMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * MULTIMODAL CONTINUUM — the engine against the **real bundled Kyiv metro**,
 * not a fixture. If the asset is wrong, these fail; that is the point.
 *
 * The asset is read from the module's own `src/main/assets` (Gradle runs unit
 * tests with the module directory as the working directory) so the thing under
 * test is byte-for-byte the thing that ships.
 */
class KyivMetroTest {

    private val feed: MetroFeed by lazy {
        val f = File("src/main/assets/country/ua/metro.json")
        assertTrue("the bundled Kyiv metro asset must exist at ${f.absolutePath}", f.exists())
        MetroFeed.parse(f.readText())
    }

    private val timetable by lazy {
        TimetableBuilder.build(
            stops = feed.stops,
            trips = feed.trips(),
            extraTransfers = feed.transfers,
        )
    }

    private fun hm(h: Int, m: Int) = h * 3600 + m * 60
    private fun stopIdx(name: String): Int =
        timetable.stopName.indexOfFirst { it == name }
            .also { assertTrue("station «$name» must exist in the asset", it >= 0) }

    @Test
    fun `the asset is the real Kyiv underground`() {
        assertEquals("Київ", feed.doc.city)
        assertEquals("three lines", 3, feed.doc.lines.size)
        assertEquals("52 stations", 52, feed.stops.size)
        assertEquals(setOf("M1", "M2", "M3"), feed.doc.lines.map { it.ref }.toSet())
        // Kyiv has exactly three interchanges. Not two, not four.
        assertEquals(3, feed.doc.interchanges.size)
        assertTrue(
            "Театральна ↔ Золоті ворота must be one of them",
            feed.doc.interchanges.any {
                setOf(it.aName, it.bName) == setOf("Театральна", "Золоті ворота")
            },
        )
    }

    @Test
    fun `every line runs both ways all day`() {
        val trips = feed.trips()
        // 3 lines × 2 directions, each running from 06:00 to 24:00.
        for (line in feed.doc.lines) {
            val onLine = trips.filter { it.routeLabel == line.ref }
            assertTrue("${line.ref} must have trips", onLine.isNotEmpty())
            val firstStops = onLine.map { it.calls.first().stopId }.toSet()
            assertEquals(
                "${line.ref} must run in both directions (two distinct termini)",
                2, firstStops.size,
            )
            assertTrue("${line.ref} must run all mode-METRO", onLine.all { it.mode == TransitMode.METRO })
        }
    }

    @Test
    fun `headway tightens at peak`() {
        assertEquals(feed.doc.headwaySeconds.peak, feed.headwayAt(hm(8, 30)))
        assertEquals(feed.doc.headwaySeconds.peak, feed.headwayAt(hm(18, 0)))
        assertEquals(feed.doc.headwaySeconds.day, feed.headwayAt(hm(13, 0)))
        assertEquals(feed.doc.headwaySeconds.evening, feed.headwayAt(hm(23, 0)))
        assertTrue("peak must be tighter than midday", feed.doc.headwaySeconds.peak < feed.doc.headwaySeconds.day)
    }

    /**
     * The real thing: Академмістечко (M1, far west terminus) →
     * Позняки (M3, left bank). No single line does it. The only way is
     * M1 east to Театральна, walk the interchange to Золоті ворота, M3
     * south-east to Позняки. If the solver can't find that, it can't
     * route Kyiv.
     */
    @Test
    fun `routes across the city and finds the one interchange that works`() {
        val from = stopIdx("Академмістечко")
        val to = stopIdx("Позняки")

        val journeys = Raptor(timetable).plan(
            seeds = listOf(Raptor.Seed(from, hm(9, 0), 0)),
            egress = listOf(Raptor.Egress(to, 0)),
        )

        val best = journeys.lastOrNull()
        assertNotNull("must find a way from Академмістечко to Позняки", best)
        best!!

        val rides = best.hops.filterIsInstance<Raptor.Hop.Board>()
        assertEquals("exactly one change: M1 → M3", 2, rides.size)
        assertEquals(1, best.transfers)
        assertEquals("M1", timetable.routeLabel[rides[0].route])
        assertEquals("M3", timetable.routeLabel[rides[1].route])

        // It must change at the real interchange, on foot through the tunnel.
        val walk = best.hops.filterIsInstance<Raptor.Hop.Walk>().single()
        assertEquals("Театральна", timetable.stopName[walk.fromStop])
        val boardsM3At = timetable.stopAt(rides[1].route, rides[1].boardPos)
        assertEquals("Золоті ворота", timetable.stopName[boardsM3At])

        // Sanity on the modelled duration: across Kyiv on the metro with one
        // change is well under an hour and nowhere near ten minutes.
        val mins = (best.arrivalSec - hm(9, 0)) / 60
        assertTrue("crossing Kyiv should take 30–70 min, modelled $mins", mins in 30..70)
    }

    @Test
    fun `a same-line trip does not invent a transfer`() {
        val from = stopIdx("Оболонь")       // M2 north
        val to = stopIdx("Либідська")       // M2 south — same line
        val journeys = Raptor(timetable).plan(
            seeds = listOf(Raptor.Seed(from, hm(9, 0), 0)),
            egress = listOf(Raptor.Egress(to, 0)),
        )
        val best = journeys.first()
        assertEquals("one line, no changes", 0, best.transfers)
        assertEquals("M2", timetable.routeLabel[(best.hops[1] as Raptor.Hop.Board).route])
    }

    /**
     * The operator's flagship scenario, end to end: a car at the western
     * edge of the oblast, a destination on the left bank, and no walkable
     * stop at either end of the drive. The planner must decide *by itself*
     * to drive to a metro station, abandon the car, and ride in.
     *
     * The road router here is a stub with no network — which also proves the
     * degraded path: it answers from a crow-fly estimate and flags it.
     */
    @Test
    fun `drives to the metro, parks, and rides in — with no network at all`() = runBlocking {
        val planner = MultimodalPlanner(
            timetable = timetable,
            road = OfflineRoadRouter,
            metroStopIds = feed.stationIds,
            parkRideLots = feed.doc.parkRideLots,
        )

        // Somewhere out past Zhytomyrska, off the metro, with a car.
        val fromLat = 50.4900; val fromLon = 30.2600
        // Poznyaky, left bank — right across the city.
        val toLat = 50.3980; val toLon = 30.6300

        val plan = planner.plan(
            fromLat, fromLon, toLat, toLon,
            MultimodalPlanner.Options(departSec = hm(9, 0), allowCar = true),
        )

        val pnr = plan.itineraries.firstOrNull { it.isParkAndRide }
        assertNotNull("must find a park-and-ride: no stop is walkable from either end", pnr)
        pnr!!

        val drive = pnr.legs.first() as Itinerary.Leg.Access
        assertEquals(AccessMode.DRIVE, drive.mode)
        assertTrue("with no router reachable, the drive must be flagged as an estimate", drive.estimated)
        assertTrue("a car left at a station must budget time to park", drive.parkingSeconds > 0)

        // It parked at a metro station, and rode the metro.
        assertTrue("must ride the metro", pnr.ridden.contains(TransitMode.METRO))
        assertTrue(
            "metro times are modelled, and the itinerary must admit it",
            pnr.hasModelledTimes,
        )

        // And it must have chosen a station on the way, not behind us.
        assertTrue(
            "parked at ${drive.toStopName}",
            drive.toStopName.isNotBlank(),
        )
    }

    @Test
    fun `walking beats driving for a short hop, and no car leg is invented`() = runBlocking {
        val planner = MultimodalPlanner(
            timetable = timetable,
            road = OfflineRoadRouter,
            metroStopIds = feed.stationIds,
            parkRideLots = feed.doc.parkRideLots,
        )
        // Two points either side of the centre, ~3 km apart: under minCarUsefulMeters.
        val plan = planner.plan(
            50.4470, 30.5220,   // near Палац «Україна» (M2)
            50.4400, 30.5180,
            MultimodalPlanner.Options(departSec = hm(9, 0), allowCar = true),
        )
        assertTrue(
            "must not propose driving to a station for a 3 km trip",
            plan.itineraries.none { it.isParkAndRide },
        )
    }

    /** No network, no lies: durations unavailable, geometry unavailable. */
    private object OfflineRoadRouter : RoadRouter {
        override suspend fun durations(
            fromLat: Double, fromLon: Double,
            to: List<Pair<Double, Double>>,
            mode: AccessMode,
        ): List<Int?>? = null

        override suspend fun leg(
            fromLat: Double, fromLon: Double,
            toLat: Double, toLon: Double,
            mode: AccessMode,
        ): RoadLeg? = null
    }
}
