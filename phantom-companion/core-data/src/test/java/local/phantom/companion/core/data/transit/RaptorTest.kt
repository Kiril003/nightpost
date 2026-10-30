package local.phantom.companion.core.data.transit

import local.phantom.companion.core.data.transit.raptor.RawStop
import local.phantom.companion.core.data.transit.raptor.RawStopTime
import local.phantom.companion.core.data.transit.raptor.RawTrip
import local.phantom.companion.core.data.transit.raptor.Raptor
import local.phantom.companion.core.data.transit.raptor.TimetableBuilder
import local.phantom.companion.core.data.transit.raptor.TransitMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MULTIMODAL CONTINUUM — the solver, on hand-built timetables where the
 * right answer is known by construction.
 */
class RaptorTest {

    private fun hm(h: Int, m: Int) = h * 3600 + m * 60

    /** Stops on a straight east–west line, ~1.4 km apart, so geometry is predictable. */
    private fun stopsOnALine(n: Int, prefix: String = "s") =
        (0 until n).map { RawStop("$prefix$it", "$prefix$it", 50.45, 30.50 + it * 0.02) }

    private fun trip(label: String, mode: TransitMode, stops: List<String>, startSec: Int, hopSec: Int) =
        RawTrip(label, mode, -1, stops.mapIndexed { i, s ->
            val t = startSec + i * hopSec
            RawStopTime(s, t, t)
        }, timesAreModelled = false)

    @Test
    fun `rides a single line end to end`() {
        val stops = stopsOnALine(4)
        val tt = TimetableBuilder.build(
            stops = stops,
            trips = listOf(trip("A", TransitMode.BUS, listOf("s0", "s1", "s2", "s3"), hm(9, 0), 300)),
            maxTransferMeters = 0.0,   // no footpaths: force the answer through the vehicle
        )

        val j = Raptor(tt).plan(
            seeds = listOf(Raptor.Seed(stop = 0, readySec = hm(8, 55), seedId = 0)),
            egress = listOf(Raptor.Egress(stop = 3, seconds = 60)),
        )

        assertEquals("one round should suffice for a one-vehicle ride", 1, j.size)
        assertEquals(0, j[0].transfers)
        // board 09:00 at s0, 3 hops × 5 min → 09:15 at s3, + 60 s walk
        assertEquals(hm(9, 15) + 60, j[0].arrivalSec)
    }

    @Test
    fun `will not board a vehicle that has already gone`() {
        val stops = stopsOnALine(2)
        val tt = TimetableBuilder.build(
            stops = stops,
            trips = listOf(trip("A", TransitMode.BUS, listOf("s0", "s1"), hm(9, 0), 300)),
            maxTransferMeters = 0.0,
        )
        // Ready at 09:01 — the only bus left at 09:00.
        val j = Raptor(tt).plan(
            seeds = listOf(Raptor.Seed(0, hm(9, 1), 0)),
            egress = listOf(Raptor.Egress(1, 0)),
        )
        assertTrue("a departed vehicle must not be boardable", j.isEmpty())
    }

    @Test
    fun `changes vehicle when one line cannot do it alone`() {
        // Line A: s0 → s1 → s2.  Line B: s2 → s3.  Only a transfer at s2 gets to s3.
        val stops = stopsOnALine(4)
        val tt = TimetableBuilder.build(
            stops = stops,
            trips = listOf(
                trip("A", TransitMode.BUS, listOf("s0", "s1", "s2"), hm(9, 0), 300),
                trip("B", TransitMode.TRAM, listOf("s2", "s3"), hm(9, 15), 300),
            ),
            maxTransferMeters = 0.0,
        )

        val j = Raptor(tt).plan(
            seeds = listOf(Raptor.Seed(0, hm(9, 0), 0)),
            egress = listOf(Raptor.Egress(3, 0)),
        )

        val best = j.last()
        assertEquals("one transfer", 1, best.transfers)
        assertEquals(hm(9, 20), best.arrivalSec)
        val rides = best.hops.filterIsInstance<Raptor.Hop.Board>()
        assertEquals(2, rides.size)
        assertEquals("A", tt.routeLabel[rides[0].route])
        assertEquals("B", tt.routeLabel[rides[1].route])
    }

    @Test
    fun `board slack stops us catching a train we could not physically reach`() {
        val stops = stopsOnALine(4)
        val tt = TimetableBuilder.build(
            stops = stops,
            trips = listOf(
                trip("A", TransitMode.BUS, listOf("s0", "s1", "s2"), hm(9, 0), 300),   // arrives s2 at 09:10
                trip("B", TransitMode.TRAM, listOf("s2", "s3"), hm(9, 10), 300),        // leaves s2 at 09:10 — impossible
                trip("B", TransitMode.TRAM, listOf("s2", "s3"), hm(9, 12), 300),        // leaves 09:12 — makeable with 45 s slack
            ),
            maxTransferMeters = 0.0,
        )

        val j = Raptor(tt).plan(
            seeds = listOf(Raptor.Seed(0, hm(9, 0), 0)),
            egress = listOf(Raptor.Egress(3, 0)),
            boardSlackSec = 45,
        )
        // The 09:10 tram departs the instant the bus arrives; you cannot make it.
        assertEquals("must take the 09:12, not the 09:10", hm(9, 17), j.last().arrivalSec)
    }

    /**
     * The load-bearing claim of the whole design: seeds compete, and the
     * timetable — not a heuristic — decides which access leg wins.
     *
     * Station FAR is a longer drive but its train leaves at once. Station
     * NEAR is a short drive but the next train is 40 minutes away. Every
     * "nearest lot" heuristic in the industry picks NEAR and loses. RAPTOR
     * explores both and picks FAR, because it can see the timetable.
     */
    @Test
    fun `park and ride picks the further station when its train leaves sooner`() {
        val stops = listOf(
            RawStop("near", "NEAR", 50.45, 30.50),
            RawStop("far", "FAR", 50.45, 30.30),
            RawStop("dest", "DEST", 50.45, 30.60),
        )
        val tt = TimetableBuilder.build(
            stops = stops,
            trips = listOf(
                // From NEAR: nothing until 10:00.
                trip("slow", TransitMode.METRO, listOf("near", "dest"), hm(10, 0), 600),
                // From FAR: a train right now, and it passes through to DEST.
                trip("fast", TransitMode.METRO, listOf("far", "dest"), hm(9, 20), 900),
            ),
            maxTransferMeters = 0.0,
        )

        val j = Raptor(tt).plan(
            seeds = listOf(
                // Drive 10 min to NEAR, ready 09:10.
                Raptor.Seed(stop = 0, readySec = hm(9, 10), seedId = 0),
                // Drive 18 min to FAR, ready 09:18 — worse access, better journey.
                Raptor.Seed(stop = 1, readySec = hm(9, 18), seedId = 1),
            ),
            egress = listOf(Raptor.Egress(2, 0)),
        )

        val best = j.first()
        assertEquals("must park at FAR — its train leaves at 09:20", 1, best.seedId)
        assertEquals(hm(9, 35), best.arrivalSec)   // vs 10:10 via NEAR
    }

    @Test
    fun `egress set lets us get off early when the walk from there is shorter`() {
        // s2 is 300 m from the door; s3 is 1200 m. Riding on to s3 arrives sooner
        // by vehicle but lands us with a long walk — the door is what counts.
        val stops = listOf(
            RawStop("s0", "s0", 50.4500, 30.5000),
            RawStop("s1", "s1", 50.4500, 30.5100),
            RawStop("s2", "s2", 50.4500, 30.5200),
            RawStop("s3", "s3", 50.4500, 30.5400),
        )
        val tt = TimetableBuilder.build(
            stops = stops,
            trips = listOf(trip("A", TransitMode.BUS, listOf("s0", "s1", "s2", "s3"), hm(9, 0), 120)),
            maxTransferMeters = 0.0,
        )

        val j = Raptor(tt).plan(
            seeds = listOf(Raptor.Seed(0, hm(9, 0), 0)),
            egress = listOf(
                Raptor.Egress(stop = 2, seconds = 300),    // off at s2 (09:04) + 5 min = 09:09
                Raptor.Egress(stop = 3, seconds = 1200),   // ride to s3 (09:06) + 20 min = 09:26
            ),
        )

        val best = j.first()
        assertEquals("should alight at s2 and walk", 2, best.egressStop)
        assertEquals(hm(9, 9), best.arrivalSec)
    }

    @Test
    fun `no journey when the timetable simply cannot get there`() {
        val stops = stopsOnALine(3)
        val tt = TimetableBuilder.build(
            stops = stops,
            trips = listOf(trip("A", TransitMode.BUS, listOf("s0", "s1"), hm(9, 0), 300)),
            maxTransferMeters = 0.0,
        )
        val j = Raptor(tt).plan(
            seeds = listOf(Raptor.Seed(0, hm(9, 0), 0)),
            egress = listOf(Raptor.Egress(2, 0)),   // s2 is served by nothing
        )
        assertTrue("must return nothing rather than invent a route", j.isEmpty())
    }

    @Test
    fun `overtaking express is split into its own route so binary search stays sound`() {
        // Same stop sequence, but the 09:05 express reaches s2 before the 09:00 local.
        // If both landed in one RAPTOR route, departures would not be monotone in
        // trip index and earliestTrip()'s binary search would quietly return wrong trips.
        val stops = stopsOnALine(3)
        val tt = TimetableBuilder.build(
            stops = stops,
            trips = listOf(
                trip("X", TransitMode.BUS, listOf("s0", "s1", "s2"), hm(9, 0), 600),   // local: s2 at 09:20
                trip("X", TransitMode.BUS, listOf("s0", "s1", "s2"), hm(9, 5), 60),    // express: s2 at 09:07
            ),
            maxTransferMeters = 0.0,
        )

        assertEquals("the overtaking pair must not share a RAPTOR route", 2, tt.routeCount)

        // And the solver must still find the express.
        val j = Raptor(tt).plan(
            seeds = listOf(Raptor.Seed(0, hm(8, 50), 0)),
            egress = listOf(Raptor.Egress(2, 0)),
        )
        assertNotNull(j.firstOrNull())
        assertEquals("the express is the right answer", hm(9, 7), j.first().arrivalSec)
    }
}
