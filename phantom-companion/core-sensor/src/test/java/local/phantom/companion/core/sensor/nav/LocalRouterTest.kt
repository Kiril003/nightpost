package local.phantom.companion.core.sensor.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LEVIATHAN-1 — the offline router's promises on the same synthetic block
 * as the matcher: it walks the network (not the crow's line), it hops
 * junctions, it respects oneway for cars and ignores it on foot, and it
 * refuses honestly when the goal is off the loaded world.
 */
class LocalRouterTest {

    private val lat0 = 50.4500
    private val lon0 = 30.5200

    private fun latAt(northM: Double) = lat0 + Math.toDegrees(northM / 6_371_000.0)
    private fun lonAt(eastM: Double) =
        lon0 + Math.toDegrees(eastM / (6_371_000.0 * Math.cos(Math.toRadians(lat0))))

    /** Same city block as RoadMatcherTest: two avenues + one cross street. */
    private fun cityBlock(onewayCross: Boolean = false): RoadGraph = RoadGraph(
        listOf(
            RoadWay(
                id = 1L, name = "Західна",
                points = listOf(
                    latAt(0.0) to lonAt(0.0),
                    latAt(200.0) to lonAt(0.0),
                    latAt(400.0) to lonAt(0.0),
                ),
            ),
            RoadWay(
                id = 2L, name = "Східна",
                points = listOf(
                    latAt(0.0) to lonAt(80.0),
                    latAt(200.0) to lonAt(80.0),
                    latAt(400.0) to lonAt(80.0),
                ),
            ),
            RoadWay(
                id = 3L, name = "Поперечна", oneway = onewayCross,
                points = listOf(
                    latAt(200.0) to lonAt(0.0),
                    latAt(200.0) to lonAt(80.0),
                ),
            ),
        ),
    )

    private fun pathLengthM(pts: List<Pair<Double, Double>>): Double {
        var d = 0.0
        for (i in 1 until pts.size) {
            d += haversineM(pts[i - 1].first, pts[i - 1].second, pts[i].first, pts[i].second)
        }
        return d
    }

    @Test
    fun `routes along one street between two snapped points`() {
        val route = LocalRouter().route(
            cityBlock(),
            fromLat = latAt(20.0), fromLon = lonAt(3.0),
            toLat = latAt(350.0), toLon = lonAt(-2.0),
        )
        assertNotNull(route)
        // ~330 m along the avenue, snapped onto the centreline.
        assertEquals(330.0, route!!.distanceMeters, 15.0)
        assertTrue(route.approximate)
        assertTrue(route.durationSeconds > 200) // ~4 min on foot
    }

    @Test
    fun `crosses a junction onto the other avenue instead of flying`() {
        val route = LocalRouter().route(
            cityBlock(),
            fromLat = latAt(20.0), fromLon = lonAt(2.0), // west avenue, south end
            toLat = latAt(380.0), toLon = lonAt(78.0), // east avenue, north end
        )
        assertNotNull(route)
        // Network path: 180 north + 80 east + 180 north ≈ 440 m. The crow
        // flies ~368 m — a straight-line cheat would be visibly shorter.
        assertEquals(440.0, route!!.distanceMeters, 25.0)
        assertEquals(route.distanceMeters, pathLengthM(route.points), 1.0)
        // The path physically passes through BOTH junction vertices — the
        // cross street is a 2-vertex way, so its traversal is exactly the
        // pair of its endpoints appearing on the polyline.
        fun visits(latM: Double, lonM: Double) = route.points.any {
            Math.abs(it.first - latAt(latM)) < 1e-5 && Math.abs(it.second - lonAt(lonM)) < 1e-5
        }
        assertTrue("route never entered the west junction", visits(200.0, 0.0))
        assertTrue("route never exited the east junction", visits(200.0, 80.0))
    }

    @Test
    fun `a goal outside the loaded world is an honest null`() {
        val route = LocalRouter().route(
            cityBlock(),
            fromLat = latAt(20.0), fromLon = lonAt(2.0),
            toLat = latAt(20.0), toLon = lonAt(5_000.0), // 5 km east: not loaded
        )
        assertNull(route)
    }

    @Test
    fun `an empty graph refuses`() {
        assertNull(
            LocalRouter().route(RoadGraph(emptyList()), lat0, lon0, lat0 + 0.001, lon0),
        )
    }

    @Test
    fun `foot ignores oneway, car respects it`() {
        // Cross street oneway west→east. Route east-avenue → west-avenue must
        // traverse it BACKWARD (east→west).
        val graph = cityBlock(onewayCross = true)
        val router = LocalRouter()
        val onFoot = router.route(
            graph,
            fromLat = latAt(200.0), fromLon = lonAt(75.0),
            toLat = latAt(200.0), toLon = lonAt(5.0),
            footProfile = true,
        )
        assertNotNull("пішки oneway не діє", onFoot)
        assertEquals(70.0, onFoot!!.distanceMeters, 10.0)

        val byCar = router.route(
            graph,
            fromLat = latAt(200.0), fromLon = lonAt(75.0),
            toLat = latAt(200.0), toLon = lonAt(5.0),
            footProfile = false,
        )
        // The only legal car path would need another street; this grid has
        // none, so the honest answer is null (or a route that is NOT the
        // forbidden 70 m backward hop).
        if (byCar != null) {
            assertTrue("car took the forbidden oneway", byCar.distanceMeters > 100.0)
        }
    }
}
