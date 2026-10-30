package local.phantom.companion.core.data.transit

import local.phantom.companion.core.data.transit.metro.MetroFeed
import local.phantom.companion.core.data.transit.raptor.Raptor
import local.phantom.companion.core.data.transit.raptor.TimetableBuilder
import local.phantom.companion.core.data.transit.raptor.TransitMode
import local.phantom.companion.core.data.transit.surface.SurfaceFeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/**
 * MULTIMODAL CONTINUUM — the surface network's promises: the feed parses,
 * expands its frequency model into ordinary trips (the metro's uniformity
 * contract), shares stops across lines, degrades named colours honestly —
 * and, the load-bearing one, fuses with the metro into ONE mesh where a bus
 * feeds the underground through a proximity footpath no one declared.
 */
class SurfaceFeedTest {

    // Two directional bus lines sharing stop "sB", a named (unparseable)
    // colour, and a tram — hop times constant so nothing overtakes.
    private val surfaceJson = """
    {
      "city": "Київ",
      "serviceStartSec": 21600,
      "serviceEndSec": 28800,
      "lines": [
        {
          "ref": "101", "mode": "bus", "colour": "purple",
          "headway": {"peak": 600, "day": 1200, "evening": 1800},
          "stops": [
            {"id": "sA", "name": "А", "lat": 50.5000, "lon": 30.4000},
            {"id": "sB", "name": "Б", "lat": 50.5050, "lon": 30.4000},
            {"id": "sC", "name": "В", "lat": 50.5100, "lon": 30.4000}
          ],
          "hopSeconds": [120, 120]
        },
        {
          "ref": "101", "mode": "bus", "colour": "purple",
          "headway": {"peak": 600, "day": 1200, "evening": 1800},
          "stops": [
            {"id": "sC", "name": "В", "lat": 50.5100, "lon": 30.4000},
            {"id": "sB", "name": "Б", "lat": 50.5050, "lon": 30.4000},
            {"id": "sA", "name": "А", "lat": 50.5000, "lon": 30.4000}
          ],
          "hopSeconds": [120, 120]
        },
        {
          "ref": "Т3", "mode": "tram", "colour": "#c62828",
          "headway": {"peak": 600, "day": 1200, "evening": 1800},
          "stops": [
            {"id": "sB", "name": "Б", "lat": 50.5050, "lon": 30.4000},
            {"id": "sD", "name": "Г", "lat": 50.5050, "lon": 30.4100}
          ],
          "hopSeconds": [180]
        }
      ]
    }
    """.trimIndent()

    @Test
    fun `stops are deduplicated across lines`() {
        val feed = SurfaceFeed.parse(surfaceJson)
        // sA sB sC sD — sB and sC appear on several lines but count once.
        assertEquals(4, feed.stops.size)
        assertEquals(setOf("sA", "sB", "sC", "sD"), feed.stops.map { it.id }.toSet())
    }

    @Test
    fun `frequency model expands into ordinary trips per direction`() {
        val feed = SurfaceFeed.parse(surfaceJson)
        val trips = feed.trips()
        // 06:00–08:00 at peak 600 s (06:00 falls before the 07:00 peak: day
        // 1200 s until 07:00, then 600 s) — what matters is uniformity, both
        // directions present, and monotone calls.
        val forward = trips.filter { it.routeLabel == "101" && it.calls.first().stopId == "sA" }
        val back = trips.filter { it.routeLabel == "101" && it.calls.first().stopId == "sC" }
        assertTrue(forward.isNotEmpty())
        assertTrue(back.isNotEmpty())
        for (t in trips) {
            for ((a, b) in t.calls.zipWithNext()) {
                assertTrue("calls must be time-ordered", a.departureSec <= b.arrivalSec)
            }
        }
        val tram = trips.filter { it.mode == TransitMode.TRAM }
        assertTrue(tram.isNotEmpty())
        assertEquals(0xC62828, tram.first().colour)
    }

    @Test
    fun `a named OSM colour degrades to the mode colour not a crash`() {
        val feed = SurfaceFeed.parse(surfaceJson)
        val bus = feed.trips().first { it.mode == TransitMode.BUS }
        assertEquals(0x1565C0, bus.colour) // "purple" is not a hex — mode blue
    }

    @Test
    fun `peak headway runs vehicles more often than day`() {
        val feed = SurfaceFeed.parse(surfaceJson)
        val h = feed.doc.lines.first().headway
        assertEquals(600, feed.headwayAt(h, 8 * 3600))   // 08:00 — peak
        assertEquals(1200, feed.headwayAt(h, 12 * 3600)) // noon — day
        assertEquals(1800, feed.headwayAt(h, 23 * 3600)) // late — evening
    }

    /**
     * The fusion promise itself: one timetable, a bus line ending ~80 m from
     * a metro station, and RAPTOR rides BOTH — boarding the underground via
     * the proximity footpath that TimetableBuilder derives from geometry.
     */
    @Test
    fun `bus feeds the metro through a proximity footpath in one fused mesh`() {
        val metroJson = """
        {
          "city": "Київ",
          "serviceStartSec": 21600,
          "serviceEndSec": 86400,
          "headwaySeconds": {"peak": 150, "day": 300, "evening": 480},
          "lines": [
            {
              "ref": "M1", "colour": "#dd2a26",
              "stations": [
                {"id": "m1", "name": "Перша", "lat": 50.5107, "lon": 30.4000},
                {"id": "m2", "name": "Друга", "lat": 50.5300, "lon": 30.4200}
              ],
              "hopSeconds": [180]
            }
          ]
        }
        """.trimIndent()
        val metro = MetroFeed.parse(metroJson)
        val surface = SurfaceFeed.parse(surfaceJson)
        // Bus 101 ends at sC (50.5100) — ~78 m south of station m1 (50.5107):
        // inside the builder's 250 m transfer radius, declared by nobody.
        val timetable = TimetableBuilder.build(
            stops = metro.stops + surface.stops,
            trips = metro.trips() + surface.trips(),
            extraTransfers = metro.transfers,
        )
        val sA = timetable.stopId.indexOf("sA")
        val m2 = timetable.stopId.indexOf("m2")
        assertTrue(sA >= 0 && m2 >= 0)

        val journeys = Raptor(timetable).plan(
            seeds = listOf(Raptor.Seed(sA, 7 * 3600, 0)),
            egress = listOf(Raptor.Egress(m2, 0)),
        )
        assertTrue("no journey across the fused mesh", journeys.isNotEmpty())
        val best = journeys.first()
        val modes = best.hops.filterIsInstance<Raptor.Hop.Board>().map {
            timetable.routeMode[it.route]
        }
        assertEquals(
            "the journey must ride the bus, then the metro",
            listOf(TransitMode.BUS, TransitMode.METRO),
            modes,
        )
    }

    @Test
    @Ignore("SurfaceFeed.liveVehiclesAt прибрано 14.09 (§TransitGlassLaw) — фід більше не виробляє власного флоту")
    fun liveVehiclesProducedDuringServiceHours() {
    }
}
