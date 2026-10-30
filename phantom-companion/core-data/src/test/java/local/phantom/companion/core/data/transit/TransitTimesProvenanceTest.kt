package local.phantom.companion.core.data.transit

import kotlinx.coroutines.runBlocking
import local.phantom.companion.core.data.transit.gtfs.GtfsStaticParser
import local.phantom.companion.core.data.transit.raptor.Timetable
import local.phantom.companion.core.data.transit.raptor.TimetableBuilder
import local.phantom.companion.core.data.transit.raptor.TransitMode
import local.phantom.companion.core.data.transit.surface.SurfaceDoc
import local.phantom.companion.core.data.transit.surface.SurfaceFeed
import local.phantom.companion.core.data.transit.surface.SurfaceHeadway
import local.phantom.companion.core.data.transit.surface.SurfaceLine
import local.phantom.companion.core.data.transit.surface.SurfaceStop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * MULTIMODAL CONTINUUM — `timesAreModelled` must follow the SOURCE of the
 * minutes, never the kind of vehicle. Both tests reach red through data:
 * a headway-modelled bus must raise the flag, and a schedule read from
 * stop_times must keep it down even when the vehicle is a metro. A mode-based
 * `if` fails both directions at once.
 */
class TransitTimesProvenanceTest {

    private fun hm(h: Int, m: Int) = h * 3600 + m * 60

    private fun planner(tt: Timetable) = MultimodalPlanner(
        timetable = tt,
        road = object : RoadRouter {
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
        },
        metroStopIds = emptyList(),
    )

    @Test
    fun `a headway-modelled bus ride admits its times are modelled`() = runBlocking {
        val doc = SurfaceDoc(
            city = "Тест",
            serviceStartSec = hm(6, 0),
            serviceEndSec = hm(23, 0),
            lines = listOf(
                SurfaceLine(
                    ref = "42",
                    mode = "bus",
                    headway = SurfaceHeadway(peak = 600, day = 900, evening = 1500),
                    stops = listOf(
                        SurfaceStop("b0", "Перша", 50.45, 30.50),
                        SurfaceStop("b1", "Друга", 50.45, 30.52),
                        SurfaceStop("b2", "Третя", 50.45, 30.54),
                    ),
                    hopSeconds = listOf(300, 300),
                ),
            ),
        )
        val feed = SurfaceFeed(doc)
        val tt = TimetableBuilder.build(stops = feed.stops, trips = feed.trips())

        val plan = planner(tt).plan(
            50.45, 30.50, 50.45, 30.54,
            MultimodalPlanner.Options(departSec = hm(9, 0), allowCar = false),
        )

        val itinerary = plan.itineraries.first()
        val ride = itinerary.legs.filterIsInstance<Itinerary.Leg.Ride>().single()
        assertEquals(TransitMode.BUS, ride.mode)
        assertTrue(
            "times synthesized from a headway model must say so on the leg",
            ride.timesAreModelled,
        )
        assertTrue(
            "an itinerary carrying a modelled leg must admit it",
            itinerary.hasModelledTimes,
        )
    }

    @Test
    fun `a scheduled ride keeps the flag down even on the metro`() = runBlocking {
        val zip = gtfsZip(
            "stops.txt" to """
                stop_id,stop_name,stop_lat,stop_lon
                g0,Alpha,50.45,30.50
                g1,Beta,50.45,30.52
                g2,Gamma,50.45,30.54
            """,
            "routes.txt" to """
                route_id,route_short_name,route_type
                r1,M9,1
            """,
            "trips.txt" to """
                route_id,service_id,trip_id
                r1,daily,t1
                r1,daily,t2
            """,
            "calendar.txt" to """
                service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date
                daily,1,1,1,1,1,1,1,20200101,20301231
            """,
            "stop_times.txt" to """
                trip_id,arrival_time,departure_time,stop_id,stop_sequence
                t1,09:05:00,09:05:00,g0,1
                t1,09:10:00,09:10:00,g1,2
                t1,09:15:00,09:15:00,g2,3
                t2,09:20:00,09:20:00,g0,1
                t2,09:25:00,09:25:00,g1,2
                t2,09:30:00,09:30:00,g2,3
            """,
        )
        val feed = GtfsStaticParser.parse(ByteArrayInputStream(zip), "20260831", 1)
        val tt = TimetableBuilder.build(stops = feed.stops, trips = feed.trips)

        val plan = planner(tt).plan(
            50.45, 30.50, 50.45, 30.54,
            MultimodalPlanner.Options(departSec = hm(9, 0), allowCar = false),
        )

        val itinerary = plan.itineraries.first()
        val ride = itinerary.legs.filterIsInstance<Itinerary.Leg.Ride>().single()
        assertEquals(TransitMode.METRO, ride.mode)
        assertFalse(
            "a schedule read from stop_times must not be branded a model",
            ride.timesAreModelled,
        )
        assertFalse(itinerary.hasModelledTimes)
    }

    @Test
    fun `live route ids map to the sign number and rides know their stops`() = runBlocking {
        val zip = gtfsZip(
            "stops.txt" to """
                stop_id,stop_name,stop_lat,stop_lon
                g0,Alpha,50.45,30.50
                g1,Beta,50.45,30.52
                g2,Gamma,50.45,30.54
            """,
            "routes.txt" to """
                route_id,route_short_name,route_long_name,route_type
                2285,А25,ТЦ Кінг Кросс - вул. Заклинських,3
                915108,915108,Hlavní nádraží - Hulváky,3
            """,
            "trips.txt" to """
                route_id,service_id,trip_id
                2285,daily,t1
            """,
            "calendar.txt" to """
                service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date
                daily,1,1,1,1,1,1,1,20200101,20301231
            """,
            "stop_times.txt" to """
                trip_id,arrival_time,departure_time,stop_id,stop_sequence
                t1,09:05:00,09:05:00,g0,1
                t1,09:10:00,09:10:00,g1,2
                t1,09:15:00,09:15:00,g2,3
            """,
        )
        val feed = GtfsStaticParser.parse(ByteArrayInputStream(zip), "20260831", 1)
        assertEquals("А25", feed.routeLabels["2285"])
        assertEquals("108", feed.routeLabels["915108"])

        val tt = TimetableBuilder.build(stops = feed.stops, trips = feed.trips)
        val ride = planner(tt).plan(
            50.45, 30.50, 50.45, 30.54,
            MultimodalPlanner.Options(departSec = hm(9, 0), allowCar = false),
        ).itineraries.first().legs.filterIsInstance<Itinerary.Leg.Ride>().single()
        assertEquals("А25", ride.routeLabel)
        assertEquals(listOf("Beta"), ride.stops.map { it.name })
    }

    private fun gtfsZip(vararg files: Pair<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((name, text) in files) {
                z.putNextEntry(ZipEntry(name))
                z.write(text.trimIndent().toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
        }
        return bos.toByteArray()
    }
}
