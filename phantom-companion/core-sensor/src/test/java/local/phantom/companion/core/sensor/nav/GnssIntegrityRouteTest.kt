package local.phantom.companion.core.sensor.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HORIZON II.1 — the clean-sky router's promises: the integrity map learns
 * where the sky is hostile, and the router detours around it when told to —
 * while never lying about the distance it actually chose.
 */
class GnssIntegrityRouteTest {

    private val lat0 = 50.4500
    private val lon0 = 30.5200
    private fun latAt(northM: Double) = lat0 + Math.toDegrees(northM / 6_371_000.0)
    private fun lonAt(eastM: Double) =
        lon0 + Math.toDegrees(eastM / (6_371_000.0 * Math.cos(Math.toRadians(lat0))))

    /**
     * A ladder: two parallel N-S avenues (west @ x=0, east @ x=80) joined by
     * cross streets at n=100 AND n=300, so a detour onto the east avenue
     * between the rungs is actually possible.
     */
    private fun ladder(): RoadGraph = RoadGraph(
        listOf(
            RoadWay(1L, listOf(
                latAt(0.0) to lonAt(0.0), latAt(100.0) to lonAt(0.0),
                latAt(300.0) to lonAt(0.0), latAt(400.0) to lonAt(0.0),
            ), name = "Західна"),
            RoadWay(2L, listOf(
                latAt(0.0) to lonAt(80.0), latAt(100.0) to lonAt(80.0),
                latAt(300.0) to lonAt(80.0), latAt(400.0) to lonAt(80.0),
            ), name = "Східна"),
            RoadWay(3L, listOf(latAt(100.0) to lonAt(0.0), latAt(100.0) to lonAt(80.0)), name = "Рунг-A"),
            RoadWay(4L, listOf(latAt(300.0) to lonAt(0.0), latAt(300.0) to lonAt(80.0)), name = "Рунг-B"),
        ),
    )

    @Test
    fun `the integrity map learns risk as an EWMA and clean sky stays zero`() {
        val map = GnssIntegrityMap(cellDeg = FINE_CELL)
        // Ten jammed passes over one tile.
        repeat(10) { map.observe(latAt(200.0), lonAt(0.0), GnssIntegrityMap.riskOf(LocationTrust.JAMMED, false)) }
        assertTrue(map.riskAt(latAt(200.0), lonAt(0.0)) > 0.5f)
        // A tile never seen is clean.
        assertEquals(0f, map.riskAt(latAt(200.0), lonAt(80.0)), 1e-6f)
    }

    @Test
    fun `spoof outranks jam outranks degrade in routing risk`() {
        assertTrue(
            GnssIntegrityMap.riskOf(LocationTrust.SPOOFED, false) >
                GnssIntegrityMap.riskOf(LocationTrust.JAMMED, false),
        )
        assertTrue(
            GnssIntegrityMap.riskOf(LocationTrust.JAMMED, false) >
                GnssIntegrityMap.riskOf(LocationTrust.DEGRADED, false),
        )
        assertEquals(0f, GnssIntegrityMap.riskOf(LocationTrust.TRUSTED, false), 1e-6f)
    }

    @Test
    fun `with no avoidance the router takes the straight jammed avenue`() {
        val graph = ladder()
        val map = GnssIntegrityMap(cellDeg = FINE_CELL)
        // Jam the whole west avenue between the rungs.
        for (n in 100..300 step 20) {
            repeat(6) { map.observe(latAt(n.toDouble()), lonAt(0.0), 1.0f) }
        }
        val fastest = LocalRouter().route(
            graph, latAt(120.0), lonAt(0.0), latAt(280.0), lonAt(0.0),
            integrity = map, jammingAvoidance = 0.0,
        )
        assertNotNull(fastest)
        // Straight up the west avenue: ~160 m, and it stays near x=0.
        assertEquals(160.0, fastest!!.distanceMeters, 20.0)
        assertTrue("mean risk should be high on the jammed avenue", fastest.meanRisk > 0.5)
    }

    @Test
    fun `clean-sky routing detours onto the unjammed avenue`() {
        val graph = ladder()
        val map = GnssIntegrityMap(cellDeg = FINE_CELL)
        for (n in 100..300 step 20) {
            repeat(6) { map.observe(latAt(n.toDouble()), lonAt(0.0), 1.0f) }
        }
        // The direct jammed path is 160 m; the detour via the east avenue is
        // ~400 m. Avoidance must exceed the (400/160 − 1) ≈ 1.5 break-even for
        // the detour to win — 2.5 buys it decisively (a fully-jammed metre
        // costs 3.5× a clean one).
        val result = LocalRouter().cleanSky(
            graph, latAt(120.0), lonAt(0.0), latAt(280.0), lonAt(0.0),
            integrity = map, jammingAvoidance = 2.5,
        )
        assertNotNull(result)
        assertTrue("clean-sky route should be a real, lower-risk alternative", result!!.differs)
        // It detours east (via rung A → east avenue → rung B), so it is longer…
        assertTrue("detour must cost extra distance", result.extraMeters > 30.0)
        // …but the reported distance is the REAL distance of the path chosen.
        assertEquals(
            result.cleanSky.distanceMeters,
            pathLen(result.cleanSky.points),
            1.0,
        )
        // And it actually passes the east avenue (x≈80).
        assertTrue(
            "clean route never used the east avenue",
            result.cleanSky.points.any { Math.abs(it.second - lonAt(80.0)) < 1e-5 },
        )
    }

    @Test
    fun `an already-clean sky yields no detour`() {
        val graph = ladder()
        val map = GnssIntegrityMap(cellDeg = FINE_CELL) // nothing observed → all clean
        val result = LocalRouter().cleanSky(
            graph, latAt(120.0), lonAt(0.0), latAt(280.0), lonAt(0.0),
            integrity = map, jammingAvoidance = 1.5,
        )
        assertNotNull(result)
        assertTrue("no jamming means no reason to differ", !result!!.differs)
    }

    private fun pathLen(pts: List<Pair<Double, Double>>): Double {
        var d = 0.0
        for (i in 1 until pts.size) d += haversineM(pts[i - 1].first, pts[i - 1].second, pts[i].first, pts[i].second)
        return d
    }

    private companion object {
        // ~22 m tiles: fine enough to resolve this synthetic 80 m-wide ladder
        // into distinct avenues. Production uses ~278 m — a jammer shadow can't
        // tell two streets 80 m apart apart, and shouldn't pretend to.
        const val FINE_CELL = 0.0002
    }
}
