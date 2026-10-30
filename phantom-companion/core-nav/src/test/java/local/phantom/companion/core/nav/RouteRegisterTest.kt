package local.phantom.companion.core.nav

import local.phantom.companion.core.net.map.RouteOrigin
import local.phantom.companion.core.net.map.RouteProfile
import local.phantom.companion.core.net.map.RouteStep
import local.phantom.companion.core.net.map.RouteVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * design-critic §S5 — the two rules this schema puts in force must land on
 * two different channels: [GuidanceController.registerPointsFor] tags every
 * POINT on its own (consumed per-segment by the car's drawn line), and
 * [GuidanceController.registerConfidenceText] applies worst-register-wins
 * only to a single summary sentence. A route where the ends are DERIVED and
 * only the middle is GUESSED must never make the summary fire on the whole
 * drive, and must never make the drawn line uniform either.
 */
class RouteRegisterTest {

    // ~111.2 m per point, due north — same fixture shape as GuidanceSessionTest.
    private val points = (0..20).map { (50.0 + it * 0.001) to 30.0 }

    private fun variant(steps: List<RouteStep>) = RouteVariant(
        points = points,
        distanceMeters = haversineM(points[0], points[20]),
        durationSeconds = 1200.0,
        steps = steps,
        speedsMps = emptyList(),
        profile = RouteProfile.CAR,
    )

    @Test
    fun `a named step earns DERIVED, an unnamed one GUESSED`() {
        val v = variant(
            listOf(
                RouteStep("depart", null, "Вулиця Хрещата", 0.0, 0.0, points[0].first, points[0].second),
                RouteStep("turn", "right", "", 0.0, 0.0, points[10].first, points[10].second),
                RouteStep("arrive", null, "", 0.0, 0.0, points[20].first, points[20].second),
            ),
        )
        val out = GuidanceController.registerPointsFor(v)
        assertEquals(21, out.size)
        // Before the turn at point 10: on the named road.
        assertEquals(RouteRegister.DERIVED, out[0].register)
        assertEquals(RouteRegister.DERIVED, out[9].register)
        // From the turn onward: the unnamed way.
        assertEquals(RouteRegister.GUESSED, out[10].register)
        assertEquals(RouteRegister.GUESSED, out[20].register)
    }

    @Test
    fun `every point keeps its own lat lon unchanged`() {
        val v = variant(
            listOf(
                RouteStep("depart", null, "Вулиця", 0.0, 0.0, points[0].first, points[0].second),
                RouteStep("arrive", null, "Вулиця", 0.0, 0.0, points[20].first, points[20].second),
            ),
        )
        val out = GuidanceController.registerPointsFor(v)
        out.forEachIndexed { i, p -> assertEquals(points[i], p.lat to p.lon) }
    }

    @Test
    fun `a route with no usable geometry falls back to GUESSED, never MEASURED`() {
        val flat = RouteVariant(
            points = listOf(50.0 to 30.0),
            distanceMeters = 0.0,
            durationSeconds = 0.0,
            steps = emptyList(),
            speedsMps = emptyList(),
            profile = RouteProfile.CAR,
        )
        val out = GuidanceController.registerPointsFor(flat)
        assertEquals(1, out.size)
        assertEquals(RouteRegister.GUESSED, out[0].register)
    }

    @Test
    fun `one short unnamed stretch is routine and speaks nothing`() {
        // Only points 9..11 (~222 m) are unnamed — well under the 1.5 km bar.
        val v = variant(
            listOf(
                RouteStep("depart", null, "Вулиця А", 0.0, 0.0, points[0].first, points[0].second),
                RouteStep("turn", "left", "", 0.0, 0.0, points[9].first, points[9].second),
                RouteStep("turn", "right", "Вулиця Б", 0.0, 0.0, points[12].first, points[12].second),
                RouteStep("arrive", null, "Вулиця Б", 0.0, 0.0, points[20].first, points[20].second),
            ),
        )
        val path = RoutePath.of(v)!!
        val out = GuidanceController.registerPointsFor(v, path)
        assertNull(GuidanceController.registerConfidenceText(path, out))
    }

    @Test
    fun `a genuinely long unnamed stretch earns the one summary sentence`() {
        // Points 2..20 unnamed — ~2 km, past the 1.5 km bar.
        val v = variant(
            listOf(
                RouteStep("depart", null, "Вулиця А", 0.0, 0.0, points[0].first, points[0].second),
                RouteStep("turn", "left", "", 0.0, 0.0, points[2].first, points[2].second),
                RouteStep("arrive", null, "", 0.0, 0.0, points[20].first, points[20].second),
            ),
        )
        val path = RoutePath.of(v)!!
        val out = GuidanceController.registerPointsFor(v, path)
        val text = GuidanceController.registerConfidenceText(path, out)
        assertNotNull(text)
        assertEquals(GuidanceController.REGISTER_CONFIDENCE_TEXT, text)
    }

    /**
     * ATLAS-2.5 §11 — the owner's штраф: the on-device mesh router carries no
     * turn-restriction relations, so a named road on ITS line is no more
     * legally certain than an unnamed one. Only the origin can say that; the
     * road name cannot.
     */
    @Test
    fun `an offline mesh route is GUESSED even where every road is named`() {
        val named = listOf(
            RouteStep("depart", null, "Вулиця Хрещата", 0.0, 0.0, points[0].first, points[0].second),
            RouteStep("turn", "right", "Проспект", 0.0, 0.0, points[10].first, points[10].second),
            RouteStep("arrive", null, "Проспект", 0.0, 0.0, points[20].first, points[20].second),
        )
        val offline = variant(named).copy(origin = RouteOrigin.OFFLINE_MESH)
        assertEquals(RouteRegister.GUESSED, offline.legalityCeiling())
        assertTrue(offline.hasGuessedTurnLaw())
        GuidanceController.registerPointsFor(offline).forEach {
            assertEquals(RouteRegister.GUESSED, it.register)
        }
    }

    @Test
    fun `an online route keeps DERIVED on the roads OSRM could name`() {
        val online = variant(
            listOf(
                RouteStep("depart", null, "Вулиця Хрещата", 0.0, 0.0, points[0].first, points[0].second),
                RouteStep("arrive", null, "Вулиця Хрещата", 0.0, 0.0, points[20].first, points[20].second),
            ),
        )
        assertEquals(RouteOrigin.ONLINE_ROUTER, online.origin)
        assertEquals(RouteRegister.DERIVED, online.legalityCeiling())
        assertFalse(online.hasGuessedTurnLaw())
        GuidanceController.registerPointsFor(online).forEach {
            assertEquals(RouteRegister.DERIVED, it.register)
        }
    }

    @Test
    fun `one offline leg makes the whole stitched route offline`() {
        assertEquals(
            RouteOrigin.OFFLINE_MESH,
            worstOrigin(RouteOrigin.ONLINE_ROUTER, RouteOrigin.OFFLINE_MESH),
        )
        assertEquals(
            RouteOrigin.OFFLINE_MESH,
            worstOrigin(RouteOrigin.OFFLINE_MESH, RouteOrigin.ONLINE_ROUTER),
        )
        assertEquals(
            RouteOrigin.ONLINE_ROUTER,
            worstOrigin(RouteOrigin.ONLINE_ROUTER, RouteOrigin.ONLINE_ROUTER),
        )
    }

    @Test
    fun `a fully confident route never speaks the summary`() {
        val v = variant(
            listOf(
                RouteStep("depart", null, "Вулиця А", 0.0, 0.0, points[0].first, points[0].second),
                RouteStep("arrive", null, "Вулиця А", 0.0, 0.0, points[20].first, points[20].second),
            ),
        )
        val path = RoutePath.of(v)!!
        val out = GuidanceController.registerPointsFor(v, path)
        assertNull(GuidanceController.registerConfidenceText(path, out))
    }
}
