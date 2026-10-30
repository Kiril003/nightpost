package local.phantom.companion.core.data.transit

import local.phantom.companion.core.data.transit.raptor.RawStop
import local.phantom.companion.core.data.transit.raptor.RawStopTime
import local.phantom.companion.core.data.transit.raptor.RawTrip
import local.phantom.companion.core.data.transit.raptor.RouteShape
import local.phantom.companion.core.data.transit.raptor.TimetableBuilder
import local.phantom.companion.core.data.transit.raptor.TransitMode
import local.phantom.companion.core.data.transit.surface.Polyline5
import local.phantom.companion.core.data.transit.surface.SurfaceFeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitShapeTest {

    @Test
    fun `polyline5 roundtrips within a metre, negative deltas included`() {
        val pts = listOf(
            50.45010 to 30.52340,
            50.45120 to 30.52110,
            50.44980 to 30.51890,
            -33.90000 to 151.20000,
        )
        val decoded = Polyline5.decode(Polyline5.encode(pts))
        checkNotNull(decoded)
        assertEquals(pts.size, decoded.size)
        for (i in pts.indices) {
            assertEquals(pts[i].first, decoded[i].first, 1e-5)
            assertEquals(pts[i].second, decoded[i].second, 1e-5)
        }
    }

    @Test
    fun `polyline5 rejects garbage`() {
        assertNull(Polyline5.decode(""))
        assertNull(Polyline5.decode("##"))
    }

    @Test
    fun `surface feed with a shape yields rides that bend with the track`() {
        // 3 stops; shape has a bend vertex between each pair.
        val shape = listOf(
            50.4000 to 30.5000,
            50.4010 to 30.5030, // bend
            50.4020 to 30.5010, // stop B anchor
            50.4030 to 30.5040, // bend
            50.4040 to 30.5020, // stop C anchor
        )
        val raw = """
        {"city":"Т","serviceStartSec":21600,"serviceEndSec":22600,
         "lines":[{"ref":"Т1","mode":"tram",
           "headway":{"peak":600,"day":600,"evening":600},
           "stops":[
             {"id":"a","name":"А","lat":50.4000,"lon":30.5000},
             {"id":"b","name":"Б","lat":50.4020,"lon":30.5010},
             {"id":"c","name":"В","lat":50.4040,"lon":30.5020}],
           "hopSeconds":[60,60],
           "shape":"${Polyline5.encode(shape)}",
           "stopShapeIdx":[0,2,4]}]}
        """.trimIndent()
        val feed = SurfaceFeed.parse(raw)
        val trips = feed.trips()
        assertTrue(trips.isNotEmpty())
        assertTrue(trips.all { it.shape != null })

        val tt = TimetableBuilder.build(feed.stops, trips)
        val between = tt.shapeBetween(0, 0, 2)
        checkNotNull(between)
        assertEquals(5, between.size) // full track incl. both bends
        assertEquals(50.4010, between[1].first, 1e-6)

        val half = tt.shapeBetween(0, 1, 2)
        checkNotNull(half)
        assertEquals(3, half.size)
    }

    @Test
    fun `malformed shapes degrade to stop chain, never crash`() {
        fun feedWith(shape: String, idx: String): SurfaceFeed = SurfaceFeed.parse(
            """
            {"city":"Т","serviceStartSec":21600,"serviceEndSec":22600,
             "lines":[{"ref":"1","mode":"bus",
               "headway":{"peak":600,"day":600,"evening":600},
               "stops":[
                 {"id":"a","name":"А","lat":50.40,"lon":30.50},
                 {"id":"b","name":"Б","lat":50.41,"lon":30.51}],
               "hopSeconds":[60],
               "shape":$shape,"stopShapeIdx":$idx}]}
            """.trimIndent(),
        )
        // anchor count mismatch / non-monotonic / out-of-range / bad polyline
        val good = "\"${Polyline5.encode(listOf(50.40 to 30.50, 50.41 to 30.51))}\""
        for ((s, i) in listOf(
            good to "[0]",
            good to "[1,0]",
            good to "[0,9]",
            "\"##\"" to "[0,1]",
        )) {
            val trips = feedWith(s, i).trips()
            assertTrue(trips.isNotEmpty())
            assertTrue("shape should be dropped for $s $i", trips.all { it.shape == null })
        }
    }

    @Test
    fun `real bundled kyiv asset parses, shapes survive into the timetable`() {
        // Unit tests run with the module as working dir; the bundled asset is
        // the real product artifact, so a regeneration that breaks parsing or
        // drops every shape fails HERE, not on the device.
        val f = java.io.File("src/main/assets/country/ua/surface.json")
        if (!f.exists()) return // fresh checkout without assets — skip, don't lie
        val feed = SurfaceFeed.parse(f.readText())
        val trips = feed.trips()
        assertTrue(feed.stops.size > 1_000)
        assertTrue(trips.isNotEmpty())
        val shapedTrips = trips.count { it.shape != null }
        assertTrue("expected most trips shaped, got $shapedTrips/${trips.size}", shapedTrips > trips.size / 2)

        val tt = TimetableBuilder.build(feed.stops, trips)
        var shapedRoutes = 0
        for (r in 0 until tt.routeCount) {
            val g = tt.shapeBetween(r, 0, tt.routeStopCount[r] - 1) ?: continue
            shapedRoutes++
            // A real track is denser than its stop chain and starts/ends at it.
            assertTrue(g.size >= tt.routeStopCount[r])
        }
        assertTrue("no route kept a shape after build", shapedRoutes > tt.routeCount / 2)
    }

    @Test
    fun `builder drops a shape whose anchors mismatch the sequence`() {
        val stops = listOf(
            RawStop("a", "А", 50.40, 30.50),
            RawStop("b", "Б", 50.41, 30.51),
        )
        val badShape = RouteShape(
            lat = doubleArrayOf(50.40, 50.41),
            lon = doubleArrayOf(30.50, 30.51),
            stopIdx = intArrayOf(0), // 1 anchor for a 2-stop sequence
        )
        val trip = RawTrip(
            "1", TransitMode.BUS, -1,
            listOf(RawStopTime("a", 100, 100), RawStopTime("b", 200, 200)),
            timesAreModelled = false,
            shape = badShape,
        )
        val tt = TimetableBuilder.build(stops, listOf(trip))
        assertNull(tt.shapeBetween(0, 0, 1))
    }
}
