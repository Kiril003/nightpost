package local.phantom.companion.core.nav.multimodal

import kotlinx.coroutines.runBlocking
import local.phantom.companion.core.net.map.OsrmClient
import local.phantom.companion.core.net.map.OverpassCenter
import local.phantom.companion.core.net.map.OverpassClient
import local.phantom.companion.core.net.map.OverpassElement
import local.phantom.companion.core.net.map.RouteProfile
import local.phantom.companion.core.net.map.RouteStep
import local.phantom.companion.core.net.map.RouteVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParkAndWalkPlannerTest {

    private class FakeOverpass(private val elements: List<OverpassElement>) : OverpassClient() {
        override suspend fun query(query: String): List<OverpassElement> = elements
    }

    private class FakeOsrm : OsrmClient() {
        override suspend fun routeDetailed(
            profile: RouteProfile,
            waypoints: List<Pair<Double, Double>>,
            alternatives: Boolean,
        ): List<RouteVariant>? {
            val dist = if (profile == RouteProfile.CAR) 5000.0 else 200.0
            val dur = if (profile == RouteProfile.CAR) 600.0 else 150.0
            val step = RouteStep(
                type = if (profile == RouteProfile.CAR) "turn" else "walk",
                modifier = "straight",
                roadName = if (profile == RouteProfile.CAR) "вулиця Хрещатик" else "пішохідна алея",
                distanceMeters = dist,
                durationSeconds = dur,
                lat = waypoints.first().first,
                lon = waypoints.first().second,
            )
            return listOf(
                RouteVariant(
                    points = waypoints,
                    distanceMeters = dist,
                    durationSeconds = dur,
                    steps = listOf(step),
                    speedsMps = listOf(10.0),
                    profile = profile,
                )
            )
        }
    }

    @Test
    fun `fuses drive and walk legs when parking is discovered`() = runBlocking {
        val parkingElement = OverpassElement(
            type = "node",
            id = 555,
            lat = 50.4505,
            lon = 30.5230,
            tags = mapOf("amenity" to "parking", "name" to "Паркінг Gulliver"),
        )
        val overpass = FakeOverpass(listOf(parkingElement))
        val osrm = FakeOsrm()
        val planner = ParkAndWalkPlanner(osrm = osrm, overpass = overpass)

        val start = 50.4400 to 30.5100
        val destination = 50.4510 to 30.5235 // Target inside mall

        val multimodalRoute = planner.planParkAndWalk(start, destination)

        assertNotNull(multimodalRoute)
        assertTrue(multimodalRoute!!.parkAndWalk != null)
        val leg = multimodalRoute.parkAndWalk!!

        assertEquals("Паркінг Gulliver", leg.parkingName)
        assertEquals(5000.0, leg.driveDistanceMeters, 0.1)
        assertEquals(200.0, leg.walkDistanceMeters, 0.1)
        // Total duration includes 600s drive + 120s parking + 150s walk = 870s
        assertEquals(870.0, multimodalRoute.durationSeconds, 0.1)
        assertEquals(5200.0, multimodalRoute.distanceMeters, 0.1)

        // Steps should contain drive step, parking arrival step, and walk step
        assertEquals(3, multimodalRoute.steps.size)
        assertEquals("Паркінг Gulliver", multimodalRoute.steps[1].roadName)
        assertEquals("arrive", multimodalRoute.steps[1].type)
    }
}
